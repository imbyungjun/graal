/*
 * Copyright (c) 2014, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.phases.common;

import static org.graalvm.compiler.core.common.GraalOptions.OptImplicitNullChecks;

import java.util.List;

import org.graalvm.compiler.debug.CounterKey;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodeinfo.InputType;
import org.graalvm.compiler.nodes.AbstractBeginNode;
import org.graalvm.compiler.nodes.AbstractDeoptimizeNode;
import org.graalvm.compiler.nodes.AbstractEndNode;
import org.graalvm.compiler.nodes.AbstractMergeNode;
import org.graalvm.compiler.nodes.BeginNode;
import org.graalvm.compiler.nodes.CompressionNode;
import org.graalvm.compiler.nodes.DeoptimizeNode;
import org.graalvm.compiler.nodes.DeoptimizingFixedWithNextNode;
import org.graalvm.compiler.nodes.DynamicDeoptimizeNode;
import org.graalvm.compiler.nodes.EndNode;
import org.graalvm.compiler.nodes.FixedNode;
import org.graalvm.compiler.nodes.IfNode;
import org.graalvm.compiler.nodes.LogicNode;
import org.graalvm.compiler.nodes.LoopExitNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.ValuePhiNode;
import org.graalvm.compiler.nodes.calc.IsNullNode;
import org.graalvm.compiler.nodes.extended.NullCheckNode;
import org.graalvm.compiler.nodes.memory.FixedAccessNode;
import org.graalvm.compiler.nodes.memory.address.AddressNode;
import org.graalvm.compiler.nodes.util.GraphUtil;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionKey;
import org.graalvm.compiler.options.OptionType;
import org.graalvm.compiler.phases.BasePhase;
import org.graalvm.compiler.phases.tiers.LowTierContext;
import org.graalvm.compiler.serviceprovider.GraalServices;

import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.SpeculationLog;
import jdk.vm.ci.meta.SpeculationLog.Speculation;

public class UseTrappingNullChecksPhase extends BasePhase<LowTierContext> {

    private static final CounterKey counterTrappingNullCheck = DebugContext.counter("TrappingNullCheck");
    private static final CounterKey counterTrappingNullCheckExistingRead = DebugContext.counter("TrappingNullCheckExistingRead");
    private static final CounterKey counterTrappingNullCheckUnreached = DebugContext.counter("TrappingNullCheckUnreached");
    private static final CounterKey counterTrappingNullCheckDynamicDeoptimize = DebugContext.counter("TrappingNullCheckDynamicDeoptimize");

    public static class Options {

        // @formatter:off
        @Option(help = "Use traps for null checks instead of explicit null-checks", type = OptionType.Expert)
        public static final OptionKey<Boolean> UseTrappingNullChecks = new OptionKey<>(true);
        // @formatter:on
    }

    @Override
    protected void run(StructuredGraph graph, LowTierContext context) {
        if (!Options.UseTrappingNullChecks.getValue(graph.getOptions()) || context.getTarget().implicitNullCheckLimit <= 0) {
            return;
        }
        assert graph.getGuardsStage().areFrameStatesAtDeopts();
        UseTrappingNullChecksVersion tnV = new UseTrappingNullChecksVersion(context.getTarget().implicitNullCheckLimit);
        MetaAccessProvider metaAccessProvider = context.getMetaAccess();
        for (DeoptimizeNode deopt : graph.getNodes(DeoptimizeNode.TYPE)) {
            tryUseTrappingVersion(deopt, deopt.predecessor(), deopt.getReason(), deopt.getSpeculation(), tnV, deopt.getActionAndReason(metaAccessProvider).asJavaConstant(),
                            deopt.getSpeculation(metaAccessProvider).asJavaConstant());
        }
        for (DynamicDeoptimizeNode deopt : graph.getNodes(DynamicDeoptimizeNode.TYPE)) {
            tryUseTrappingVersion(metaAccessProvider, deopt, tnV);
        }
    }

    interface UseTrappingVersion {
        boolean canUseTrappingVersion();

        boolean isSupportedReason(DeoptimizationReason reason);

        boolean canReplaceCondition(LogicNode condition);

        boolean useAddressOptimization(AddressNode adr);

        DeoptimizingFixedWithNextNode tryReplaceExisting(StructuredGraph graph, AbstractBeginNode nonTrappingContinuation, AbstractBeginNode trappingContinuation, LogicNode condition,
                        IfNode ifNode, AbstractDeoptimizeNode deopt, JavaConstant deoptReasonAndAction, JavaConstant deoptSpeculation);

        DeoptimizingFixedWithNextNode createImplicitNode(StructuredGraph graph, LogicNode condition, JavaConstant deoptReasonAndAction, JavaConstant deoptSpeculation);
    }

    static class UseTrappingNullChecksVersion implements UseTrappingVersion {

        final int implicitNullCheckLimit;

        public UseTrappingNullChecksVersion(int implicitNullCheckLimit) {
            this.implicitNullCheckLimit = implicitNullCheckLimit;
        }

        @Override
        public boolean canUseTrappingVersion() {
            return implicitNullCheckLimit > 0;
        }

        @Override
        public boolean canReplaceCondition(LogicNode condition) {
            return condition instanceof IsNullNode;
        }

        @Override
        public boolean useAddressOptimization(AddressNode adr) {
            return adr.getMaxConstantDisplacement() < implicitNullCheckLimit;
        }

        @Override
        public boolean isSupportedReason(DeoptimizationReason reason) {
            return reason == DeoptimizationReason.NullCheckException || reason != DeoptimizationReason.UnreachedCode || reason == DeoptimizationReason.TypeCheckedInliningViolated;
        }

        @Override
        public DeoptimizingFixedWithNextNode tryReplaceExisting(StructuredGraph graph, AbstractBeginNode nonTrappingContinuation, AbstractBeginNode trappingContinuation, LogicNode condition,
                        IfNode ifNode, AbstractDeoptimizeNode deopt, JavaConstant deoptReasonAndAction, JavaConstant deoptSpeculation) {
            IsNullNode isNullNode = (IsNullNode) condition;
            FixedNode nextNonTrapping = nonTrappingContinuation.next();
            ValueNode value = isNullNode.getValue();
            if (OptImplicitNullChecks.getValue(graph.getOptions()) && this.canUseTrappingVersion()) {
                if (nextNonTrapping instanceof FixedAccessNode) {
                    FixedAccessNode fixedAccessNode = (FixedAccessNode) nextNonTrapping;
                    AddressNode address = fixedAccessNode.getAddress();
                    if (fixedAccessNode.canNullCheck() && useAddressOptimization(address)) {
                        ValueNode base = address.getBase();
                        ValueNode index = address.getIndex();
                        // allow for architectures which cannot fold an
                        // intervening uncompress out of the address chain
                        if (base != null && base instanceof CompressionNode) {
                            base = ((CompressionNode) base).getValue();
                        }
                        if (index != null && index instanceof CompressionNode) {
                            index = ((CompressionNode) index).getValue();
                        }
                        if (((base == value && index == null) || (base == null && index == value))) {
                            // Opportunity for implicit null check as part of an existing read
                            // found!
                            fixedAccessNode.setStateBefore(deopt.stateBefore());
                            fixedAccessNode.setUsedAsNullCheck(true);
                            fixedAccessNode.setImplicitDeoptimization(deoptReasonAndAction, deoptSpeculation);
                            graph.removeSplit(ifNode, nonTrappingContinuation);
                            counterTrappingNullCheckExistingRead.increment(graph.getDebug());
                            graph.getDebug().log("Added implicit null check to %s", fixedAccessNode);
                            return fixedAccessNode;
                        }
                    }
                }
            }
            return null;
        }

        @Override
        public DeoptimizingFixedWithNextNode createImplicitNode(StructuredGraph graph, LogicNode condition, JavaConstant deoptReasonAndAction, JavaConstant deoptSpeculation) {
            IsNullNode isNullNode = (IsNullNode) condition;
            return graph.add(NullCheckNode.create(isNullNode.getValue(), deoptReasonAndAction, deoptSpeculation));
        }

    }

    public static void tryUseTrappingVersion(MetaAccessProvider metaAccessProvider, DynamicDeoptimizeNode deopt, UseTrappingVersion trappingVersion) {
        Node predecessor = deopt.predecessor();
        if (predecessor instanceof AbstractMergeNode) {
            AbstractMergeNode merge = (AbstractMergeNode) predecessor;

            // Process each predecessor at the merge, unpacking the reasons and speculations as
            // needed.
            ValueNode reason = deopt.getActionAndReason();
            ValuePhiNode reasonPhi = null;
            List<ValueNode> reasons = null;
            int expectedPhis = 0;

            if (reason instanceof ValuePhiNode) {
                reasonPhi = (ValuePhiNode) reason;
                if (reasonPhi.merge() != merge) {
                    return;
                }
                reasons = reasonPhi.values().snapshot();
                expectedPhis++;
            } else if (!reason.isConstant()) {
                merge.getDebug().log("Non constant reason %s", merge);
                return;
            }

            ValueNode speculation = deopt.getSpeculation();
            ValuePhiNode speculationPhi = null;
            List<ValueNode> speculations = null;
            if (speculation instanceof ValuePhiNode) {
                speculationPhi = (ValuePhiNode) speculation;
                if (speculationPhi.merge() != merge) {
                    return;
                }
                speculations = speculationPhi.values().snapshot();
                expectedPhis++;
            }

            if (merge.phis().count() != expectedPhis) {
                return;
            }

            int index = 0;
            List<EndNode> predecessors = merge.cfgPredecessors().snapshot();
            for (AbstractEndNode end : predecessors) {
                Node endPredecesssor = end.predecessor();
                ValueNode thisReason = reasons != null ? reasons.get(index) : reason;
                ValueNode thisSpeculation = speculations != null ? speculations.get(index) : speculation;
                if (!merge.isAlive()) {
                    // When evacuating a merge the last successor simplfies the merge away so it
                    // must be handled specially.
                    assert predecessors.get(predecessors.size() - 1) == end : "must be last end";
                    endPredecesssor = deopt.predecessor();
                    thisSpeculation = deopt.getSpeculation();
                    thisReason = deopt.getActionAndReason();
                }

                index++;
                if (!thisReason.isConstant() || !thisSpeculation.isConstant()) {
                    end.getDebug().log("Non constant deopt %s", end);
                    continue;
                }
                DeoptimizationReason deoptimizationReason = metaAccessProvider.decodeDeoptReason(thisReason.asJavaConstant());
                Speculation speculationConstant = metaAccessProvider.decodeSpeculation(thisSpeculation.asJavaConstant(), deopt.graph().getSpeculationLog());
                tryUseTrappingVersion(deopt, endPredecesssor, deoptimizationReason, speculationConstant, trappingVersion, thisReason.asJavaConstant(), thisSpeculation.asJavaConstant());
            }
        }
    }

    private static void tryUseTrappingVersion(AbstractDeoptimizeNode deopt, Node predecessor, DeoptimizationReason deoptimizationReason,
                    Speculation speculation, UseTrappingVersion trappingVersion, JavaConstant deoptReasonAndAction, JavaConstant deoptSpeculation) {
        assert predecessor != null;
        if (!GraalServices.supportsArbitraryImplicitException() && !trappingVersion.isSupportedReason(deoptimizationReason)) {
            deopt.getDebug().log(DebugContext.INFO_LEVEL, "Not a null check or unreached %s", predecessor);
            return;
        }
        assert speculation != null;
        if (!GraalServices.supportsArbitraryImplicitException() && !speculation.equals(SpeculationLog.NO_SPECULATION)) {
            deopt.getDebug().log(DebugContext.INFO_LEVEL, "Has a speculation %s", predecessor);
            return;
        }

        // Skip over loop exit nodes.
        Node pred = predecessor;
        while (pred instanceof LoopExitNode) {
            pred = pred.predecessor();
        }
        if (pred instanceof AbstractMergeNode) {
            AbstractMergeNode merge = (AbstractMergeNode) pred;
            if (merge.phis().isEmpty()) {
                for (AbstractEndNode end : merge.cfgPredecessors().snapshot()) {
                    checkPredecessor(deopt, end.predecessor(), deoptimizationReason, trappingVersion, deoptReasonAndAction, deoptSpeculation);
                }
            }
        } else if (pred instanceof AbstractBeginNode) {
            checkPredecessor(deopt, pred, deoptimizationReason, trappingVersion, deoptReasonAndAction, deoptSpeculation);
        } else {
            deopt.getDebug().log(DebugContext.INFO_LEVEL, "Not a Begin or Merge %s", pred);
        }
    }

    private static void checkPredecessor(AbstractDeoptimizeNode deopt, Node predecessor, DeoptimizationReason deoptimizationReason, UseTrappingVersion trappingVersion,
                    JavaConstant deoptReasonAndAction, JavaConstant deoptSpeculation) {
        Node current = predecessor;
        AbstractBeginNode branch = null;
        while (current instanceof AbstractBeginNode) {
            branch = (AbstractBeginNode) current;
            if (branch.anchored().isNotEmpty()) {
                // some input of the deopt framestate is anchored to this branch
                return;
            }
            current = current.predecessor();
        }
        if (current instanceof IfNode) {
            IfNode ifNode = (IfNode) current;
            if (branch != ifNode.trueSuccessor()) {
                return;
            }
            LogicNode condition = ifNode.condition();
            if (trappingVersion.canReplaceCondition(condition)) {
                replaceWithTrappingVersion(deopt, ifNode, condition, deoptimizationReason, trappingVersion, deoptReasonAndAction, deoptSpeculation);
            }
        }
    }

    private static void replaceWithTrappingVersion(AbstractDeoptimizeNode deopt, IfNode ifNode, LogicNode condition, DeoptimizationReason deoptimizationReason, UseTrappingVersion trappingVersion,
                    JavaConstant deoptReasonAndAction, JavaConstant deoptSpeculation) {
        DebugContext debug = deopt.getDebug();
        StructuredGraph graph = deopt.graph();
        counterTrappingNullCheck.increment(debug);
        if (deopt instanceof DynamicDeoptimizeNode) {
            counterTrappingNullCheckDynamicDeoptimize.increment(debug);
        }
        if (deoptimizationReason == DeoptimizationReason.UnreachedCode) {
            counterTrappingNullCheckUnreached.increment(debug);
        }
        AbstractBeginNode nonTrappingContinuation = ifNode.falseSuccessor();
        AbstractBeginNode trappingContinuation = ifNode.trueSuccessor();
        DeoptimizingFixedWithNextNode trappingVersionNode = null;
        trappingVersionNode = trappingVersion.tryReplaceExisting(graph, nonTrappingContinuation, trappingContinuation, condition, ifNode, deopt, deoptReasonAndAction, deoptSpeculation);
        if (trappingVersionNode == null) {
            // Need to add a null check node.
            trappingVersionNode = trappingVersion.createImplicitNode(graph, condition, deoptReasonAndAction, deoptSpeculation);
            graph.replaceSplit(ifNode, trappingVersionNode, nonTrappingContinuation);
            debug.log("Inserted NullCheckNode %s", trappingVersionNode);
        }
        trappingVersionNode.setStateBefore(deopt.stateBefore());
        /*
         * We now have the pattern NullCheck/BeginNode/... It's possible some node is using the
         * BeginNode as a guard input, so replace guard users of the Begin with the NullCheck and
         * then remove the Begin from the graph.
         */
        nonTrappingContinuation.replaceAtUsages(trappingVersionNode, InputType.Guard);
        if (nonTrappingContinuation instanceof BeginNode) {
            GraphUtil.unlinkFixedNode(nonTrappingContinuation);
            nonTrappingContinuation.safeDelete();
        }
        GraphUtil.killCFG(trappingContinuation);
        GraphUtil.tryKillUnused(condition);
    }

    @Override
    public float codeSizeIncrease() {
        return 2.0f;
    }
}

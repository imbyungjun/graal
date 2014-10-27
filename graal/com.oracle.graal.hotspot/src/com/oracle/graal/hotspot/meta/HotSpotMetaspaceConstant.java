/*
 * Copyright (c) 2014, 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package com.oracle.graal.hotspot.meta;

import java.util.*;

import com.oracle.graal.api.meta.*;

public final class HotSpotMetaspaceConstant extends PrimitiveConstant implements HotSpotConstant, VMConstant {

    private static final long serialVersionUID = 1003463314013122983L;

    public static Constant forMetaspaceObject(Kind kind, long primitive, Object metaspaceObject, boolean compressed) {
        return new HotSpotMetaspaceConstant(kind, primitive, metaspaceObject, compressed);
    }

    public static Object getMetaspaceObject(Constant constant) {
        return ((HotSpotMetaspaceConstant) constant).metaspaceObject;
    }

    private final Object metaspaceObject;
    private final boolean compressed;

    private HotSpotMetaspaceConstant(Kind kind, long primitive, Object metaspaceObject, boolean compressed) {
        super(kind, primitive);
        this.metaspaceObject = metaspaceObject;
        this.compressed = compressed;
    }

    @Override
    public int hashCode() {
        return super.hashCode() ^ System.identityHashCode(metaspaceObject);
    }

    @Override
    public boolean equals(Object o) {
        return o == this || (o instanceof HotSpotMetaspaceConstant && super.equals(o) && Objects.equals(metaspaceObject, ((HotSpotMetaspaceConstant) o).metaspaceObject));
    }

    @Override
    public String toString() {
        return super.toString() + "{" + metaspaceObject + (compressed ? ";compressed}" : "}");
    }
}

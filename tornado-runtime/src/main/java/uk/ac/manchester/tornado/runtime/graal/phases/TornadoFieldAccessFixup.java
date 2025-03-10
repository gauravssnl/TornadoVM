/*
 * Copyright (c) 2022, APT Group, Department of Computer Science,
 * The University of Manchester. All rights reserved.
 * Copyright (c) 2009, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */
package uk.ac.manchester.tornado.runtime.graal.phases;

import java.util.ArrayDeque;
import java.util.Optional;

import org.graalvm.compiler.nodes.GraphState;
import org.graalvm.compiler.nodes.ParameterNode;
import org.graalvm.compiler.nodes.PiNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.extended.JavaReadNode;
import org.graalvm.compiler.nodes.extended.JavaWriteNode;
import org.graalvm.compiler.nodes.java.AccessFieldNode;
import org.graalvm.compiler.nodes.java.AccessIndexedNode;
import org.graalvm.compiler.nodes.java.LoadFieldNode;
import org.graalvm.compiler.nodes.java.StoreFieldNode;
import org.graalvm.compiler.nodes.memory.address.OffsetAddressNode;
import org.graalvm.compiler.phases.BasePhase;

import uk.ac.manchester.tornado.api.exceptions.TornadoInternalError;
import uk.ac.manchester.tornado.runtime.graal.nodes.calc.TornadoAddressArithmeticNode;

public class TornadoFieldAccessFixup extends BasePhase<TornadoHighTierContext> {
    @Override
    public Optional<NotApplicable> notApplicableTo(GraphState graphState) {
        return ALWAYS_APPLICABLE;
    }

    @Override
    protected void run(StructuredGraph graph, TornadoHighTierContext context) {
        ArrayDeque<LoadFieldNode> worklist = new ArrayDeque<>();
        graph.getNodes().filter(ParameterNode.class).forEach(parameterNode -> {
            worklist.addAll(parameterNode.usages().filter(LoadFieldNode.class).snapshot());
            parameterNode.usages().filter(usage -> usage instanceof PiNode && ((PiNode) usage).object() instanceof ParameterNode).forEach(usage -> worklist.addAll(usage.usages().filter(
                    LoadFieldNode.class).snapshot()));
        });

        while (!worklist.isEmpty()) {
            LoadFieldNode loadField = worklist.poll();
            worklist.addAll(loadField.usages().filter(LoadFieldNode.class).snapshot());

            loadField.usages().forEach(usage -> {
                if (usage instanceof AccessIndexedNode) {
                    AccessIndexedNode loadStoreIndexed = (AccessIndexedNode) usage;
                    ValueNode base = loadField.object();
                    if (base instanceof PiNode) {
                        base = ((PiNode) base).object();
                    } else if (base instanceof TornadoAddressArithmeticNode) {
                        base = ((TornadoAddressArithmeticNode) base).getBase();
                    }
                    TornadoAddressArithmeticNode addNode = new TornadoAddressArithmeticNode(base, loadField);
                    graph.addWithoutUnique(addNode);
                    loadStoreIndexed.setArray(addNode);
                } else if (usage instanceof AccessFieldNode) {
                    AccessFieldNode accessFieldNode = (AccessFieldNode) usage;
                    ValueNode base = loadField.object();
                    if (base instanceof PiNode) {
                        base = ((PiNode) base).object();
                    } else if (base instanceof TornadoAddressArithmeticNode) {
                        base = ((TornadoAddressArithmeticNode) base).getBase();
                    }
                    TornadoAddressArithmeticNode addNode = new TornadoAddressArithmeticNode(base, loadField);
                    graph.addWithoutUnique(addNode);
                    if (accessFieldNode instanceof LoadFieldNode) {
                        ((LoadFieldNode) accessFieldNode).setObject(addNode);
                    } else if (accessFieldNode instanceof StoreFieldNode) {
                        StoreFieldNode oldStoreNode = (StoreFieldNode) accessFieldNode;
                        StoreFieldNode storeFieldNode = new StoreFieldNode(addNode, oldStoreNode.field(), oldStoreNode.value());
                        graph.addWithoutUnique(storeFieldNode);
                        graph.replaceFixedWithFixed(oldStoreNode, storeFieldNode);
                    } else {
                        TornadoInternalError.shouldNotReachHere("Unexpected node type = %s", accessFieldNode.getClass().getName());
                    }
                } else if (usage instanceof OffsetAddressNode) {
                    if (usage.usages().filter(JavaWriteNode.class).isNotEmpty() || usage.usages().filter(JavaReadNode.class).isNotEmpty()) {
                        ValueNode base = loadField.object();
                        if (base instanceof PiNode) {
                            base = ((PiNode) base).object();
                        } else if (base instanceof TornadoAddressArithmeticNode) {
                            base = ((TornadoAddressArithmeticNode) base).getBase();
                        }

                        TornadoAddressArithmeticNode addNode = new TornadoAddressArithmeticNode(base, loadField);
                        graph.addWithoutUnique(addNode);
                        usage.replaceFirstInput(loadField, addNode);
                    }
                }
            });
        }
    }
}

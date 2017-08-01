/*
 * Copyright 2012 James Clarkson.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package tornado.runtime.graph;

import java.util.Arrays;
import java.util.BitSet;
import tornado.common.SchedulableTask;
import tornado.common.TornadoDevice;
import tornado.runtime.graph.nodes.*;

import static tornado.runtime.graph.GraphAssembler.STREAM_OUT;
import static tornado.runtime.graph.GraphAssembler.STREAM_OUT_BLOCKING;

public class GraphCompiler {

    public static GraphCompilationResult compile(Graph graph, ExecutionContext context) {

        final BitSet deviceContexts = graph.filter(ContextNode.class);
        if (deviceContexts.cardinality() == 1) {
            final ContextNode contextNode = (ContextNode) graph.getNode(deviceContexts.nextSetBit(0));
            return compileSingleContext(graph, context, context.getDevice(contextNode.getIndex()));
        }

        return null;
    }

    /*
     * Simplest case where all tasks are executed on the same device
     */
    private static GraphCompilationResult compileSingleContext(Graph graph, ExecutionContext context,
            TornadoDevice device) {

        final GraphCompilationResult result = new GraphCompilationResult();

        final BitSet asyncNodes = graph.filter((AbstractNode n) -> n instanceof ContextOpNode);

//        System.out.printf("found: [%s]\n", toString(asyncNodes));
        final BitSet[] deps = new BitSet[asyncNodes.cardinality()];
        final BitSet tasks = new BitSet(asyncNodes.cardinality());
        final int[] nodeIds = new int[asyncNodes.cardinality()];
        int index = 0;
        int numDepLists = 0;
        for (int i = asyncNodes.nextSetBit(0); i != -1 && i < asyncNodes.length(); i = asyncNodes.nextSetBit(i + 1)) {
            deps[index] = calculateDeps(graph, context, i);
            nodeIds[index] = i;

            if (graph.getNode(i) instanceof TaskNode) {
                tasks.set(index);
                final TaskNode taskNode = (TaskNode) graph.getNode(i);
                final SchedulableTask task = context.getTask(taskNode.getTaskIndex());
//                System.out.printf("node: %s %s\n", task.getName(), taskNode);
            } else {
//                System.out.printf("node: %s\n", graph.getNode(i));
            }

            if (!deps[index].isEmpty()) {
                numDepLists++;
            }
            index++;
        }

//        printMatrix(graph, nodeIds, deps, tasks);
        result.begin(1, tasks.cardinality(), numDepLists + 1);

        schedule(result, graph, context, nodeIds, deps, tasks);
        peephole(result, numDepLists);

        result.end();

//        result.dump();
        return result;
    }

    private static void peephole(GraphCompilationResult result, int numDepLists) {
        final byte[] code = result.getCode();
        final int codeSize = result.getCodeSize();

        if (code[codeSize - 13] == STREAM_OUT) {
            code[codeSize - 13] = STREAM_OUT_BLOCKING;
        } else {
            result.barrier(numDepLists);
        }
    }

    private static void schedule(GraphCompilationResult result, Graph graph, ExecutionContext context,
            int[] nodeIds, BitSet[] deps, BitSet tasks) {

        final BitSet scheduled = new BitSet(deps.length);
        scheduled.clear();
        final BitSet nodes = new BitSet(graph.getValid().length());

//        System.out.println("----- event lists ------");
        final int[] depLists = new int[deps.length];
        Arrays.fill(depLists, -1);

        int index = 0;
        for (int i = 0; i < deps.length; i++) {
            if (!deps[i].isEmpty()) {

                final AbstractNode current = graph.getNode(nodeIds[i]);
                if (current instanceof DependentReadNode) {
                    continue;
                }

                depLists[i] = index;
                index++;
            }
        }

        while (scheduled.cardinality() < deps.length) {
//            System.out.printf("nodes: %s\n", toString(nodes));
//            System.out.printf("scheduled: %s\n", toString(scheduled));
            for (int i = 0; i < deps.length; i++) {
                if (!scheduled.get(i)) {

                    final BitSet outstandingDeps = new BitSet(nodes.length());
                    outstandingDeps.or(deps[i]);
                    outstandingDeps.andNot(nodes);

//                    System.out.printf("trying: %d - %s\n",nodeIds[i],toString(outstandingDeps));
                    if (outstandingDeps.isEmpty()) {
                        final ContextOpNode asyncNode = (ContextOpNode) graph.getNode(nodeIds[i]);

                        result.emitAsyncNode(
                                graph,
                                context,
                                asyncNode,
                                asyncNode.getContext().getIndex(),
                                (deps[i].isEmpty()) ? -1 : depLists[i]);

                        for (int j = 0; j < deps.length; j++) {
                            if (j == i) {
                                continue;
                            }
//						System.out.printf("checking: %d - %s\n",nodeIds[j],toString(deps[j]));
                            if (deps[j].get(nodeIds[i]) && depLists[j] != -1) {
                                result.emitAddDep(depLists[j]);
                            }
                        }
                        scheduled.set(i);
                        nodes.set(nodeIds[i]);
                    }
                }
            }
        }

    }

    private static String toString(BitSet set) {
        if (set.isEmpty()) {
            return "<none>";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = set.nextSetBit(0); i != -1 && i < set.length(); i = set.nextSetBit(i + 1)) {
            sb.append("" + i + " ");
        }
        return sb.toString();
    }

    private static void printMatrix(Graph graph, int[] nodeIds, BitSet[] deps,
            BitSet tasks) {

        System.out.println("dependency matrix...");
        for (int i = 0; i < nodeIds.length; i++) {
            final int nodeId = nodeIds[i];
            System.out.printf("%d [%s]| %s\n", nodeId, (tasks.get(i)) ? "task" : "data", toString(deps[i]));
        }

    }

    private static BitSet calculateDeps(Graph graph, ExecutionContext context, int i) {
        final BitSet deps = new BitSet(graph.getValid().length());

        final AbstractNode node = graph.getNode(i);
        for (AbstractNode input : node.getInputs()) {
            if (input instanceof ContextOpNode) {
                if (input instanceof DependentReadNode) {
                    deps.set(((DependentReadNode) input).getDependent().getId());
                } else {
                    deps.set(input.getId());
                }
            }
        }

        return deps;
    }

}

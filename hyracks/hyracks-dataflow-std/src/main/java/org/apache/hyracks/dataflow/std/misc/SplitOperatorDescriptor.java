/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hyracks.dataflow.std.misc;

import java.nio.ByteBuffer;

import org.apache.hyracks.api.comm.IFrameWriter;
import org.apache.hyracks.api.comm.VSizeFrame;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.dataflow.ActivityId;
import org.apache.hyracks.api.dataflow.IActivityGraphBuilder;
import org.apache.hyracks.api.dataflow.IOperatorNodePushable;
import org.apache.hyracks.api.dataflow.TaskId;
import org.apache.hyracks.api.dataflow.value.IRecordDescriptorProvider;
import org.apache.hyracks.api.dataflow.value.RecordDescriptor;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.job.IOperatorDescriptorRegistry;
import org.apache.hyracks.dataflow.common.comm.util.FrameUtils;
import org.apache.hyracks.dataflow.std.base.AbstractActivityNode;
import org.apache.hyracks.dataflow.std.base.AbstractOperatorDescriptor;
import org.apache.hyracks.dataflow.std.base.AbstractUnaryInputOperatorNodePushable;
import org.apache.hyracks.dataflow.std.base.AbstractUnaryOutputSourceOperatorNodePushable;

public class SplitOperatorDescriptor extends AbstractOperatorDescriptor {
    private static final long serialVersionUID = 1L;

    private final static int SPLITTER_MATERIALIZER_ACTIVITY_ID = 0;
    private final static int MATERIALIZE_READER_ACTIVITY_ID = 1;

    private final boolean[] outputMaterializationFlags;
    private final boolean requiresMaterialization;
    private final int numberOfNonMaterializedOutputs;
    private final int numberOfMaterializedOutputs;

    public SplitOperatorDescriptor(IOperatorDescriptorRegistry spec, RecordDescriptor rDesc, int outputArity) {
        this(spec, rDesc, outputArity, new boolean[outputArity]);
    }

    public SplitOperatorDescriptor(IOperatorDescriptorRegistry spec, RecordDescriptor rDesc, int outputArity,
            boolean[] outputMaterializationFlags) {
        super(spec, 1, outputArity);
        for (int i = 0; i < outputArity; i++) {
            recordDescriptors[i] = rDesc;
        }
        this.outputMaterializationFlags = outputMaterializationFlags;

        boolean reqMaterialization = false;
        int matOutputs = 0;
        int nonMatOutputs = 0;
        for (boolean flag : outputMaterializationFlags) {
            if (flag) {
                reqMaterialization = true;
                matOutputs++;
            } else {
                nonMatOutputs++;
            }
        }

        this.requiresMaterialization = reqMaterialization;
        this.numberOfMaterializedOutputs = matOutputs;
        this.numberOfNonMaterializedOutputs = nonMatOutputs;

    }

    @Override
    public void contributeActivities(IActivityGraphBuilder builder) {
        SplitterMaterializerActivityNode sma = new SplitterMaterializerActivityNode(
                new ActivityId(odId, SPLITTER_MATERIALIZER_ACTIVITY_ID));
        builder.addActivity(this, sma);
        builder.addSourceEdge(0, sma, 0);
        int pipelineOutputIndex = 0;
        int activityId = MATERIALIZE_READER_ACTIVITY_ID;
        for (int i = 0; i < outputArity; i++) {
            if (outputMaterializationFlags[i]) {
                MaterializeReaderActivityNode mra = new MaterializeReaderActivityNode(
                        new ActivityId(odId, activityId++));
                builder.addActivity(this, mra);
                builder.addBlockingEdge(sma, mra);
                builder.addTargetEdge(i, mra, 0);
            } else {
                builder.addTargetEdge(i, sma, pipelineOutputIndex++);
            }
        }
    }

    private final class SplitterMaterializerActivityNode extends AbstractActivityNode {
        private static final long serialVersionUID = 1L;

        public SplitterMaterializerActivityNode(ActivityId id) {
            super(id);
        }

        @Override
        public IOperatorNodePushable createPushRuntime(final IHyracksTaskContext ctx,
                IRecordDescriptorProvider recordDescProvider, final int partition, int nPartitions) {
            return new AbstractUnaryInputOperatorNodePushable() {
                private MaterializerTaskState state;
                private final IFrameWriter[] writers = new IFrameWriter[numberOfNonMaterializedOutputs];
                private final boolean[] isOpen = new boolean[numberOfNonMaterializedOutputs];

                @Override
                public void open() throws HyracksDataException {
                    if (requiresMaterialization) {
                        state = new MaterializerTaskState(ctx.getJobletContext().getJobId(),
                                new TaskId(getActivityId(), partition), numberOfMaterializedOutputs);
                        state.open(ctx);
                    }
                    for (int i = 0; i < numberOfNonMaterializedOutputs; i++) {
                        isOpen[i] = true;
                        writers[i].open();
                    }
                }

                @Override
                public void nextFrame(ByteBuffer bufferAccessor) throws HyracksDataException {
                    if (requiresMaterialization) {
                        state.appendFrame(bufferAccessor);
                    }
                    for (int i = 0; i < numberOfNonMaterializedOutputs; i++) {
                        FrameUtils.flushFrame(bufferAccessor, writers[i]);
                    }
                }

                @Override
                public void flush() throws HyracksDataException {
                    for (int i = 0; i < numberOfNonMaterializedOutputs; i++) {
                        writers[i].flush();
                    }
                }

                @Override
                public void close() throws HyracksDataException {
                    HyracksDataException hde = null;
                    try {
                        if (requiresMaterialization) {
                            state.close();
                            ctx.setStateObject(state);
                        }
                    } finally {
                        for (int i = 0; i < numberOfNonMaterializedOutputs; i++) {
                            if (isOpen[i]) {
                                try {
                                    writers[i].close();
                                } catch (Throwable th) {
                                    if (hde == null) {
                                        hde = new HyracksDataException(th);
                                    } else {
                                        hde.addSuppressed(th);
                                    }
                                }
                            }
                        }
                    }
                    if (hde != null) {
                        throw hde;
                    }
                }

                @Override
                public void fail() throws HyracksDataException {
                    HyracksDataException hde = null;
                    for (int i = 0; i < numberOfNonMaterializedOutputs; i++) {
                        if (isOpen[i]) {
                            try {
                                writers[i].fail();
                            } catch (Throwable th) {
                                if (hde == null) {
                                    hde = new HyracksDataException(th);
                                } else {
                                    hde.addSuppressed(th);
                                }
                            }
                        }
                    }
                    if (hde != null) {
                        throw hde;
                    }
                }

                @Override
                public void setOutputFrameWriter(int index, IFrameWriter writer, RecordDescriptor recordDesc) {
                    writers[index] = writer;
                }
            };
        }
    }

    private final class MaterializeReaderActivityNode extends AbstractActivityNode {
        private static final long serialVersionUID = 1L;

        public MaterializeReaderActivityNode(ActivityId id) {
            super(id);
        }

        @Override
        public IOperatorNodePushable createPushRuntime(final IHyracksTaskContext ctx,
                final IRecordDescriptorProvider recordDescProvider, final int partition, int nPartitions)
                        throws HyracksDataException {
            return new AbstractUnaryOutputSourceOperatorNodePushable() {

                @Override
                public void initialize() throws HyracksDataException {
                    MaterializerTaskState state = (MaterializerTaskState) ctx.getStateObject(
                            new TaskId(new ActivityId(getOperatorId(), SPLITTER_MATERIALIZER_ACTIVITY_ID), partition));
                    state.writeOut(writer, new VSizeFrame(ctx));
                }

            };
        }
    }
}

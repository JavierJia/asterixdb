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
package org.apache.hyracks.storage.am.common.dataflow;

import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.hyracks.api.comm.VSizeFrame;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.dataflow.value.IMissingWriter;
import org.apache.hyracks.api.dataflow.value.IMissingWriterFactory;
import org.apache.hyracks.api.dataflow.value.RecordDescriptor;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.job.profiling.IOperatorStats;
import org.apache.hyracks.control.common.job.profiling.OperatorStats;
import org.apache.hyracks.dataflow.common.comm.io.ArrayTupleBuilder;
import org.apache.hyracks.dataflow.common.comm.io.FrameTupleAccessor;
import org.apache.hyracks.dataflow.common.comm.io.FrameTupleAppender;
import org.apache.hyracks.dataflow.common.comm.util.FrameUtils;
import org.apache.hyracks.dataflow.common.data.accessors.FrameTupleReference;
import org.apache.hyracks.dataflow.common.data.accessors.ITupleReference;
import org.apache.hyracks.dataflow.std.base.AbstractUnaryInputUnaryOutputOperatorNodePushable;
import org.apache.hyracks.storage.am.common.api.IIndexDataflowHelper;
import org.apache.hyracks.storage.am.common.api.ISearchOperationCallbackFactory;
import org.apache.hyracks.storage.am.common.impls.NoOpOperationCallback;
import org.apache.hyracks.storage.am.common.tuples.PermutingFrameTupleReference;
import org.apache.hyracks.storage.common.IIndex;
import org.apache.hyracks.storage.common.IIndexAccessor;
import org.apache.hyracks.storage.common.IIndexCursor;
import org.apache.hyracks.storage.common.ISearchOperationCallback;
import org.apache.hyracks.storage.common.ISearchPredicate;
import org.apache.hyracks.storage.common.buffercache.BufferCache;

public abstract class IndexSearchOperatorNodePushable extends AbstractUnaryInputUnaryOutputOperatorNodePushable {

    static final Logger LOGGER = Logger.getLogger(IndexSearchOperatorNodePushable.class.getName());
    protected final IHyracksTaskContext ctx;
    protected final IIndexDataflowHelper indexHelper;
    protected FrameTupleAccessor accessor;

    protected FrameTupleAppender appender;
    protected ArrayTupleBuilder tb;
    protected DataOutput dos;

    protected IIndex index;
    protected ISearchPredicate searchPred;
    protected IIndexCursor cursor;
    protected IIndexAccessor indexAccessor;

    protected final RecordDescriptor inputRecDesc;
    protected final boolean retainInput;
    protected FrameTupleReference frameTuple;

    protected final boolean retainMissing;
    protected ArrayTupleBuilder nonMatchTupleBuild;
    protected IMissingWriter nonMatchWriter;

    protected final int[] minFilterFieldIndexes;
    protected final int[] maxFilterFieldIndexes;
    protected PermutingFrameTupleReference minFilterKey;
    protected PermutingFrameTupleReference maxFilterKey;
    protected final boolean appendIndexFilter;
    protected ArrayTupleBuilder nonFilterTupleBuild;
    protected final ISearchOperationCallbackFactory searchCallbackFactory;
    protected boolean failed = false;
    private final IOperatorStats stats;

    public IndexSearchOperatorNodePushable(IHyracksTaskContext ctx, RecordDescriptor inputRecDesc, int partition,
            int[] minFilterFieldIndexes, int[] maxFilterFieldIndexes, IIndexDataflowHelperFactory indexHelperFactory,
            boolean retainInput, boolean retainMissing, IMissingWriterFactory missingWriterFactory,
            ISearchOperationCallbackFactory searchCallbackFactory, boolean appendIndexFilter)
            throws HyracksDataException {
        this.ctx = ctx;
        this.indexHelper = indexHelperFactory.create(ctx.getJobletContext().getServiceContext(), partition);
        this.retainInput = retainInput;
        this.retainMissing = retainMissing;
        this.appendIndexFilter = appendIndexFilter;
        if (this.retainMissing || this.appendIndexFilter) {
            this.nonMatchWriter = missingWriterFactory.createMissingWriter();
        }
        this.inputRecDesc = inputRecDesc;
        this.searchCallbackFactory = searchCallbackFactory;
        this.minFilterFieldIndexes = minFilterFieldIndexes;
        this.maxFilterFieldIndexes = maxFilterFieldIndexes;
        if (minFilterFieldIndexes != null && minFilterFieldIndexes.length > 0) {
            minFilterKey = new PermutingFrameTupleReference();
            minFilterKey.setFieldPermutation(minFilterFieldIndexes);
        }
        if (maxFilterFieldIndexes != null && maxFilterFieldIndexes.length > 0) {
            maxFilterKey = new PermutingFrameTupleReference();
            maxFilterKey.setFieldPermutation(maxFilterFieldIndexes);
        }
        stats = new OperatorStats(getDisplayName());
        if (ctx.getStatsCollector() != null) {
            ctx.getStatsCollector().add(stats);
        }
    }

    protected abstract ISearchPredicate createSearchPredicate();

    protected abstract void resetSearchPredicate(int tupleIndex);

    protected IIndexCursor createCursor() {
        return indexAccessor.createSearchCursor(false);
    }

    protected abstract int getFieldCount();

    private long start;
    public static AtomicInteger BTreeRangeSearchCount = new AtomicInteger(0);
    public static AtomicInteger InvertSearchCount = new AtomicInteger(0);
    public static AtomicInteger BloomFilterCount = new AtomicInteger(0);
    public static AtomicInteger BTreePointSearchCount = new AtomicInteger(0);

    public static AtomicLong BTreeRangeSearchTime = new AtomicLong(0);
    public static AtomicLong InvertSearchTime = new AtomicLong(0);
    public static AtomicLong BloomFilterTime = new AtomicLong(0);
    public static AtomicLong BTreePointSearchPathTime = new AtomicLong(0);
    public static AtomicLong BTreePointSearchLeafTrueTime = new AtomicLong(0);
    public static AtomicLong BTreePointSearchLeafFalseTime = new AtomicLong(0);
    public static AtomicLong BTreePointSearchNOPETime = new AtomicLong(0);

    @Override
    public void open() throws HyracksDataException {
        writer.open();
        indexHelper.open();
        index = indexHelper.getIndexInstance();
        accessor = new FrameTupleAccessor(inputRecDesc);
        start = System.nanoTime();
        if (retainMissing) {
            int fieldCount = getFieldCount();
            nonMatchTupleBuild = new ArrayTupleBuilder(fieldCount);
            buildMissingTuple(fieldCount, nonMatchTupleBuild, nonMatchWriter);
        } else {
            nonMatchTupleBuild = null;
        }
        if (appendIndexFilter) {
            int numIndexFilterFields = index.getNumOfFilterFields();
            nonFilterTupleBuild = new ArrayTupleBuilder(numIndexFilterFields);
            buildMissingTuple(numIndexFilterFields, nonFilterTupleBuild, nonMatchWriter);
        }

        try {
            searchPred = createSearchPredicate();
            tb = new ArrayTupleBuilder(recordDesc.getFieldCount());
            dos = tb.getDataOutput();
            appender = new FrameTupleAppender(new VSizeFrame(ctx), true);
            ISearchOperationCallback searchCallback =
                    searchCallbackFactory.createSearchOperationCallback(indexHelper.getResource().getId(), ctx, null);
            indexAccessor = index.createAccessor(NoOpOperationCallback.INSTANCE, searchCallback);
            cursor = createCursor();
            if (retainInput) {
                frameTuple = new FrameTupleReference();
            }
        } catch (Exception e) {
            throw new HyracksDataException(e);
        }
    }

    protected void writeSearchResults(int tupleIndex) throws Exception {
        long matchingTupleCount = 0;
        while (cursor.hasNext()) {
            matchingTupleCount++;
            tb.reset();
            cursor.next();
            if (retainInput) {
                frameTuple.reset(accessor, tupleIndex);
                for (int i = 0; i < frameTuple.getFieldCount(); i++) {
                    dos.write(frameTuple.getFieldData(i), frameTuple.getFieldStart(i), frameTuple.getFieldLength(i));
                    tb.addFieldEndOffset();
                }
            }
            ITupleReference tuple = cursor.getTuple();
            writeTupleToOutput(tuple);
            if (appendIndexFilter) {
                writeFilterTupleToOutput(cursor.getFilterMinTuple());
                writeFilterTupleToOutput(cursor.getFilterMaxTuple());
            }
            FrameUtils.appendToWriter(writer, appender, tb.getFieldEndOffsets(), tb.getByteArray(), 0, tb.getSize());
        }
        stats.getTupleCounter().update(matchingTupleCount);

        if (matchingTupleCount == 0 && retainInput && retainMissing) {
            FrameUtils.appendConcatToWriter(writer, appender, accessor, tupleIndex,
                    nonMatchTupleBuild.getFieldEndOffsets(), nonMatchTupleBuild.getByteArray(), 0,
                    nonMatchTupleBuild.getSize());
        }
    }

    @Override
    public void nextFrame(ByteBuffer buffer) throws HyracksDataException {
        accessor.reset(buffer);
        int tupleCount = accessor.getTupleCount();
        try {
            for (int i = 0; i < tupleCount; i++) {
                resetSearchPredicate(i);
                cursor.reset();
                indexAccessor.search(cursor, searchPred);
                writeSearchResults(i);
            }
        } catch (Exception e) {
            throw HyracksDataException.create(e);
        }
    }

    @Override
    public void flush() throws HyracksDataException {
        appender.flush(writer);
    }

    private void printStats() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n")
                .append(this.getClass().getSimpleName() + ",time," + (System.nanoTime() - start)).append("\n");
        sb.append("BTreeRangeSearchCount,").append(BTreeRangeSearchCount.get())
                .append(",BTreeRangeSearchTime,").append(BTreeRangeSearchTime.get()).append("\n");
        sb.append("InvertSearchCount,").append(InvertSearchCount.get())
                .append(",InvertSearchTime,").append(InvertSearchTime.get()).append("\n");
        sb.append("BloomFilterCount,").append(BloomFilterCount.get())
                .append(",BloomFilterTime,").append(BloomFilterTime.get()).append("\n");
        sb.append("BTreePointSearchCount,").append(BTreePointSearchCount.get())
                .append(",BTreePointSearchTimePathTime,").append(BTreePointSearchPathTime.get())
                .append(",BTreePointSearchTimeLeafTrueTime,").append(BTreePointSearchLeafTrueTime.get())
                .append(",BTreePointSearchTimeLeafFalseTime,").append(BTreePointSearchLeafFalseTime.get())
                .append(",BTreePointSearchTimeNOPETime,").append(BTreePointSearchNOPETime.get())
                .append("\n");
        sb.append("BufferCacheTotalPage,").append(BufferCache.totalPageCount.get())
                .append(",BufferCacheCached,").append(BufferCache.cachedPageCount.get())
                .append(",BufferCacheRead,").append(BufferCache.readPageCount.get())
                .append("\n");
        LOGGER.warning(sb.toString());
    }

    private void clearStats() {
        start = 0;
        BTreeRangeSearchCount.set(0);
        InvertSearchCount.set(0);
        BloomFilterCount.set(0);
        BTreePointSearchCount.set(0);

        BTreeRangeSearchTime.set(0);
        InvertSearchTime.set(0);
        BloomFilterTime.set(0);

        BTreePointSearchPathTime.set(0);
        BTreePointSearchLeafTrueTime.set(0);
        BTreePointSearchLeafFalseTime.set(0);
        BTreePointSearchNOPETime.set(0);

        BufferCache.clearStats();
    }

    @Override
    public void close() throws HyracksDataException {
        HyracksDataException closeException = null;

        printStats();
        clearStats();

        if (index != null) {
            // if index == null, then the index open was not successful
            if (!failed) {
                try {
                    if (appender.getTupleCount() > 0) {
                        appender.write(writer, true);
                    }
                } catch (Throwable th) {
                    writer.fail();
                    closeException = HyracksDataException.create(th);
                }
            }

            try {
                cursor.close();
            } catch (Throwable th) {
                if (closeException == null) {
                    closeException = HyracksDataException.create(th);
                } else {
                    closeException.addSuppressed(th);
                }
            }
            try {
                indexHelper.close();
            } catch (Throwable th) {
                if (closeException == null) {
                    closeException = new HyracksDataException(th);
                } else {
                    closeException.addSuppressed(th);
                }
            }
        }
        try {
            // will definitely be called regardless of exceptions
            writer.close();
        } catch (Throwable th) {
            if (closeException == null) {
                closeException = new HyracksDataException(th);
            } else {
                closeException.addSuppressed(th);
            }
        }
        if (closeException != null) {
            throw closeException;
        }
    }

    @Override
    public void fail() throws HyracksDataException {
        failed = true;
        writer.fail();
    }

    private void writeTupleToOutput(ITupleReference tuple) throws IOException {
        try {
            for (int i = 0; i < tuple.getFieldCount(); i++) {
                dos.write(tuple.getFieldData(i), tuple.getFieldStart(i), tuple.getFieldLength(i));
                tb.addFieldEndOffset();
            }
        } catch (Exception e) {
            throw e;
        }
    }

    private void writeFilterTupleToOutput(ITupleReference tuple) throws IOException {
        if (tuple != null) {
            writeTupleToOutput(tuple);
        } else {
            int[] offsets = nonFilterTupleBuild.getFieldEndOffsets();
            for (int i = 0; i < offsets.length; i++) {
                int start = i > 0 ? offsets[i - 1] : 0;
                tb.addField(nonFilterTupleBuild.getByteArray(), start, offsets[i]);
            }
        }
    }

    private static void buildMissingTuple(int numFields, ArrayTupleBuilder nullTuple, IMissingWriter nonMatchWriter) {
        DataOutput out = nullTuple.getDataOutput();
        for (int i = 0; i < numFields; i++) {
            try {
                nonMatchWriter.writeMissing(out);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, e.getMessage(), e);
            }
            nullTuple.addFieldEndOffset();
        }
    }

}

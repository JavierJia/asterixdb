package org.apache.hyracks.tests.am.common;

import org.apache.hyracks.api.dataflow.value.IBinaryComparatorFactory;
import org.apache.hyracks.api.dataflow.value.ISerializerDeserializer;
import org.apache.hyracks.api.dataflow.value.ITypeTraits;
import org.apache.hyracks.api.dataflow.value.RecordDescriptor;
import org.apache.hyracks.data.std.accessors.PointableBinaryComparatorFactory;
import org.apache.hyracks.data.std.primitive.UTF8StringPointable;
import org.apache.hyracks.dataflow.common.data.marshalling.UTF8StringSerializerDeserializer;
import org.apache.hyracks.dataflow.common.data.parsers.IValueParserFactory;
import org.apache.hyracks.dataflow.common.data.parsers.UTF8StringParserFactory;

public class DataSetConstants {

    public static final RecordDescriptor inputRecordDesc = new RecordDescriptor(
            new ISerializerDeserializer[] { new UTF8StringSerializerDeserializer(),
                    new UTF8StringSerializerDeserializer(), new UTF8StringSerializerDeserializer(),
                    new UTF8StringSerializerDeserializer(), new UTF8StringSerializerDeserializer(),
                    new UTF8StringSerializerDeserializer(), new UTF8StringSerializerDeserializer(),
                    new UTF8StringSerializerDeserializer(), new UTF8StringSerializerDeserializer() });

    public static final IValueParserFactory[] inputParserFactories =
            new IValueParserFactory[] { UTF8StringParserFactory.INSTANCE, UTF8StringParserFactory.INSTANCE,
                    UTF8StringParserFactory.INSTANCE, UTF8StringParserFactory.INSTANCE,
                    UTF8StringParserFactory.INSTANCE, UTF8StringParserFactory.INSTANCE,
                    UTF8StringParserFactory.INSTANCE, UTF8StringParserFactory.INSTANCE,
                    UTF8StringParserFactory.INSTANCE };

    // field, type and key declarations for primary index
    public static int[] primaryFieldPermutation = { 0, 1, 2, 4, 5, 7 };

    public static final ITypeTraits[] primaryTypeTraits =
            new ITypeTraits[] { UTF8StringPointable.TYPE_TRAITS, UTF8StringPointable.TYPE_TRAITS,
                    UTF8StringPointable.TYPE_TRAITS, UTF8StringPointable.TYPE_TRAITS, UTF8StringPointable.TYPE_TRAITS,
                    UTF8StringPointable.TYPE_TRAITS };
    public static final int primaryFieldCount = primaryTypeTraits.length;

    public static final IBinaryComparatorFactory[] primaryComparatorFactories =
            new IBinaryComparatorFactory[] { PointableBinaryComparatorFactory.of(UTF8StringPointable.FACTORY) };
    public static final int primaryKeyFieldCount = primaryComparatorFactories.length;

    public static final int[] primaryBloomFilterKeyFields = new int[] { 0 };

    public static final RecordDescriptor primaryRecDesc = new RecordDescriptor(
            new ISerializerDeserializer[] { new UTF8StringSerializerDeserializer(),
                    new UTF8StringSerializerDeserializer(), new UTF8StringSerializerDeserializer(),
                    new UTF8StringSerializerDeserializer(), new UTF8StringSerializerDeserializer(),
                    new UTF8StringSerializerDeserializer() });

    // field, type and key declarations for secondary indexes

    public static int[] secondaryFieldPermutationA = { 3, 0 };
    public static int[] secondaryFieldPermutationB = { 4, 0 };

    public static final ITypeTraits[] secondaryTypeTraits =
            new ITypeTraits[] { UTF8StringPointable.TYPE_TRAITS, UTF8StringPointable.TYPE_TRAITS };
    public static final int secondaryKeyFieldCount = secondaryTypeTraits.length;

    public static final IBinaryComparatorFactory[] secondaryComparatorFactories =
            new IBinaryComparatorFactory[] { PointableBinaryComparatorFactory.of(UTF8StringPointable.FACTORY),
                    PointableBinaryComparatorFactory.of(UTF8StringPointable.FACTORY) };
    public static final int[] secondaryBloomFilterKeyFields = new int[] { 0, 1 };

    public static final RecordDescriptor secondaryRecDesc = new RecordDescriptor(
            new ISerializerDeserializer[] { new UTF8StringSerializerDeserializer(),
                    new UTF8StringSerializerDeserializer() });
}

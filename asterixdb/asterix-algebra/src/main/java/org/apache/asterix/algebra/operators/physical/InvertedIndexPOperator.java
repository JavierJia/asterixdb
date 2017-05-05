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
package org.apache.asterix.algebra.operators.physical;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.asterix.common.dataflow.ICcApplicationContext;
import org.apache.asterix.metadata.MetadataException;
import org.apache.asterix.metadata.MetadataManager;
import org.apache.asterix.metadata.declared.DataSourceId;
import org.apache.asterix.metadata.declared.MetadataProvider;
import org.apache.asterix.metadata.entities.Dataset;
import org.apache.asterix.metadata.entities.Index;
import org.apache.asterix.metadata.utils.DatasetUtil;
import org.apache.asterix.om.base.IAObject;
import org.apache.asterix.om.constants.AsterixConstantValue;
import org.apache.asterix.om.functions.BuiltinFunctions;
import org.apache.asterix.om.types.ARecordType;
import org.apache.asterix.om.types.ATypeTag;
import org.apache.asterix.om.types.IAType;
import org.apache.asterix.om.utils.NonTaggedFormatUtil;
import org.apache.asterix.optimizer.rules.am.InvertedIndexAccessMethod;
import org.apache.asterix.optimizer.rules.am.InvertedIndexAccessMethod.SearchModifierType;
import org.apache.asterix.optimizer.rules.am.InvertedIndexJobGenParams;
import org.apache.asterix.runtime.job.listener.JobEventListenerFactory;
import org.apache.hyracks.algebricks.common.constraints.AlgebricksPartitionConstraint;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.common.utils.Pair;
import org.apache.hyracks.algebricks.core.algebra.base.IHyracksJobBuilder;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalExpressionTag;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalOperatorTag;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalVariable;
import org.apache.hyracks.algebricks.core.algebra.base.PhysicalOperatorTag;
import org.apache.hyracks.algebricks.core.algebra.expressions.AbstractFunctionCallExpression;
import org.apache.hyracks.algebricks.core.algebra.expressions.IAlgebricksConstantValue;
import org.apache.hyracks.algebricks.core.algebra.expressions.IVariableTypeEnvironment;
import org.apache.hyracks.algebricks.core.algebra.metadata.IDataSourceIndex;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractUnnestMapOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.IOperatorSchema;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.visitors.VariableUtilities;
import org.apache.hyracks.algebricks.core.algebra.properties.INodeDomain;
import org.apache.hyracks.algebricks.core.jobgen.impl.JobGenContext;
import org.apache.hyracks.algebricks.core.jobgen.impl.JobGenHelper;
import org.apache.hyracks.api.dataflow.IOperatorDescriptor;
import org.apache.hyracks.api.dataflow.value.IBinaryComparatorFactory;
import org.apache.hyracks.api.dataflow.value.ITypeTraits;
import org.apache.hyracks.api.dataflow.value.RecordDescriptor;
import org.apache.hyracks.api.job.JobSpecification;
import org.apache.hyracks.data.std.accessors.PointableBinaryComparatorFactory;
import org.apache.hyracks.data.std.primitive.ShortPointable;
import org.apache.hyracks.dataflow.std.file.IFileSplitProvider;
import org.apache.hyracks.storage.am.common.dataflow.IIndexDataflowHelperFactory;
import org.apache.hyracks.storage.am.common.ophelpers.IndexOperation;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMMergePolicyFactory;
import org.apache.hyracks.storage.am.lsm.invertedindex.api.IInvertedIndexSearchModifierFactory;
import org.apache.hyracks.storage.am.lsm.invertedindex.dataflow.LSMInvertedIndexSearchOperatorDescriptor;
import org.apache.hyracks.storage.am.lsm.invertedindex.tokenizers.IBinaryTokenizerFactory;

/**
 * Contributes the runtime operator for an unnest-map representing an
 * inverted-index search.
 */
public class InvertedIndexPOperator extends IndexSearchPOperator {
    private final boolean isPartitioned;

    public InvertedIndexPOperator(IDataSourceIndex<String, DataSourceId> idx, INodeDomain domain,
            boolean requiresBroadcast, boolean isPartitioned) {
        super(idx, domain, requiresBroadcast);
        this.isPartitioned = isPartitioned;
    }

    @Override
    public PhysicalOperatorTag getOperatorTag() {
        if (isPartitioned) {
            return PhysicalOperatorTag.LENGTH_PARTITIONED_INVERTED_INDEX_SEARCH;
        } else {
            return PhysicalOperatorTag.SINGLE_PARTITION_INVERTED_INDEX_SEARCH;
        }
    }

    @Override
    public void contributeRuntimeOperator(IHyracksJobBuilder builder, JobGenContext context, ILogicalOperator op,
            IOperatorSchema opSchema, IOperatorSchema[] inputSchemas, IOperatorSchema outerPlanSchema)
            throws AlgebricksException {
        AbstractUnnestMapOperator unnestMapOp = (AbstractUnnestMapOperator) op;
        ILogicalExpression unnestExpr = unnestMapOp.getExpressionRef().getValue();
        if (unnestExpr.getExpressionTag() != LogicalExpressionTag.FUNCTION_CALL) {
            throw new IllegalStateException();
        }
        AbstractFunctionCallExpression unnestFuncExpr = (AbstractFunctionCallExpression) unnestExpr;
        if (unnestFuncExpr.getFunctionIdentifier() != BuiltinFunctions.INDEX_SEARCH) {
            return;
        }
        InvertedIndexJobGenParams jobGenParams = new InvertedIndexJobGenParams();
        jobGenParams.readFromFuncArgs(unnestFuncExpr.getArguments());

        MetadataProvider metadataProvider = (MetadataProvider) context.getMetadataProvider();
        Dataset dataset;
        try {
            dataset = metadataProvider.findDataset(jobGenParams.getDataverseName(), jobGenParams.getDatasetName());
        } catch (MetadataException e) {
            throw new AlgebricksException(e);
        }
        int[] keyIndexes = getKeyIndexes(jobGenParams.getKeyVarList(), inputSchemas);

        boolean propagateIndexFilter = unnestMapOp.getPropagateIndexFilter();
        int[] minFilterFieldIndexes = getKeyIndexes(unnestMapOp.getMinFilterVars(), inputSchemas);
        int[] maxFilterFieldIndexes = getKeyIndexes(unnestMapOp.getMaxFilterVars(), inputSchemas);
        boolean retainNull = false;
        if (op.getOperatorTag() == LogicalOperatorTag.LEFT_OUTER_UNNEST_MAP) {
            // By nature, LEFT_OUTER_UNNEST_MAP should generate null values for non-matching tuples.
            retainNull = true;
        }
        // Build runtime.
        Pair<IOperatorDescriptor, AlgebricksPartitionConstraint> invIndexSearch =
                buildInvertedIndexRuntime(metadataProvider, context, builder.getJobSpec(), unnestMapOp, opSchema,
                        jobGenParams.getRetainInput(), retainNull, jobGenParams.getDatasetName(), dataset,
                        jobGenParams.getIndexName(), jobGenParams.getSearchKeyType(), keyIndexes,
                        jobGenParams.getSearchModifierType(), jobGenParams.getSimilarityThreshold(),
                        propagateIndexFilter, minFilterFieldIndexes, maxFilterFieldIndexes,
                        jobGenParams.getIsFullTextSearch());

        // Contribute operator in hyracks job.
        builder.contributeHyracksOperator(unnestMapOp, invIndexSearch.first);
        builder.contributeAlgebricksPartitionConstraint(invIndexSearch.first, invIndexSearch.second);
        ILogicalOperator srcExchange = unnestMapOp.getInputs().get(0).getValue();
        builder.contributeGraphEdge(srcExchange, 0, unnestMapOp, 0);
    }

    public Pair<IOperatorDescriptor, AlgebricksPartitionConstraint> buildInvertedIndexRuntime(
            MetadataProvider metadataProvider, JobGenContext context, JobSpecification jobSpec,
            AbstractUnnestMapOperator unnestMap, IOperatorSchema opSchema, boolean retainInput, boolean retainMissing,
            String datasetName, Dataset dataset, String indexName, ATypeTag searchKeyType, int[] keyFields,
            SearchModifierType searchModifierType, IAlgebricksConstantValue similarityThreshold,
            boolean propagateIndexFilter, int[] minFilterFieldIndexes, int[] maxFilterFieldIndexes,
            boolean isFullTextSearchQuery) throws AlgebricksException {
        try {
            IAObject simThresh = ((AsterixConstantValue) similarityThreshold).getObject();
            IAType itemType = MetadataManager.INSTANCE.getDatatype(metadataProvider.getMetadataTxnContext(),
                    dataset.getItemTypeDataverseName(), dataset.getItemTypeName()).getDatatype();
            int numPrimaryKeys = DatasetUtil.getPartitioningKeys(dataset).size();
            Index secondaryIndex = MetadataManager.INSTANCE.getIndex(metadataProvider.getMetadataTxnContext(),
                    dataset.getDataverseName(), dataset.getDatasetName(), indexName);
            if (secondaryIndex == null) {
                throw new AlgebricksException(
                        "Code generation error: no index " + indexName + " for dataset " + datasetName);
            }
            List<List<String>> secondaryKeyFieldEntries = secondaryIndex.getKeyFieldNames();
            List<IAType> secondaryKeyTypeEntries = secondaryIndex.getKeyFieldTypes();
            int numSecondaryKeys = secondaryKeyFieldEntries.size();
            if (numSecondaryKeys != 1) {
                throw new AlgebricksException(
                        "Cannot use " + numSecondaryKeys + " fields as a key for an inverted index. "
                                + "There can be only one field as a key for the inverted index index.");
            }
            if (itemType.getTypeTag() != ATypeTag.RECORD) {
                throw new AlgebricksException("Only record types can be indexed.");
            }
            ARecordType recordType = (ARecordType) itemType;
            Pair<IAType, Boolean> keyPairType = Index.getNonNullableOpenFieldType(secondaryKeyTypeEntries.get(0),
                    secondaryKeyFieldEntries.get(0), recordType);
            IAType secondaryKeyType = keyPairType.first;
            if (secondaryKeyType == null) {
                throw new AlgebricksException(
                        "Could not find field " + secondaryKeyFieldEntries.get(0) + " in the schema.");
            }

            // TODO: For now we assume the type of the generated tokens is the
            // same as the indexed field.
            // We need a better way of expressing this because tokens may be
            // hashed, or an inverted-index may index a list type, etc.
            int numTokenKeys = (!isPartitioned) ? numSecondaryKeys : numSecondaryKeys + 1;
            ITypeTraits[] tokenTypeTraits = new ITypeTraits[numTokenKeys];
            IBinaryComparatorFactory[] tokenComparatorFactories = new IBinaryComparatorFactory[numTokenKeys];
            for (int i = 0; i < numSecondaryKeys; i++) {
                tokenComparatorFactories[i] = NonTaggedFormatUtil.getTokenBinaryComparatorFactory(secondaryKeyType);
                tokenTypeTraits[i] = NonTaggedFormatUtil.getTokenTypeTrait(secondaryKeyType);
            }
            if (isPartitioned) {
                // The partitioning field is hardcoded to be a short *without* an Asterix type tag.
                tokenComparatorFactories[numSecondaryKeys] =
                        PointableBinaryComparatorFactory.of(ShortPointable.FACTORY);
                tokenTypeTraits[numSecondaryKeys] = ShortPointable.TYPE_TRAITS;
            }

            IVariableTypeEnvironment typeEnv = context.getTypeEnvironment(unnestMap);
            List<LogicalVariable> outputVars = unnestMap.getVariables();
            if (retainInput) {
                outputVars = new ArrayList<>();
                VariableUtilities.getLiveVariables(unnestMap, outputVars);
            }
            RecordDescriptor outputRecDesc = JobGenHelper.mkRecordDescriptor(typeEnv, opSchema, context);

            int start = outputRecDesc.getFieldCount() - numPrimaryKeys;
            IBinaryComparatorFactory[] invListsComparatorFactories = JobGenHelper
                    .variablesToAscBinaryComparatorFactories(outputVars, start, numPrimaryKeys, typeEnv, context);
            ITypeTraits[] invListsTypeTraits =
                    JobGenHelper.variablesToTypeTraits(outputVars, start, numPrimaryKeys, typeEnv, context);
            ITypeTraits[] filterTypeTraits = DatasetUtil.computeFilterTypeTraits(dataset, recordType);
            int[] filterFields;
            int[] invertedIndexFields;
            int[] filterFieldsForNonBulkLoadOps;
            int[] invertedIndexFieldsForNonBulkLoadOps;
            if (filterTypeTraits != null) {
                filterFields = new int[1];
                filterFields[0] = numTokenKeys + numPrimaryKeys;
                invertedIndexFields = new int[numTokenKeys + numPrimaryKeys];
                for (int k = 0; k < invertedIndexFields.length; k++) {
                    invertedIndexFields[k] = k;
                }

                filterFieldsForNonBulkLoadOps = new int[1];
                filterFieldsForNonBulkLoadOps[0] = numPrimaryKeys + numSecondaryKeys;
                invertedIndexFieldsForNonBulkLoadOps = new int[numPrimaryKeys + numSecondaryKeys];
                for (int k = 0; k < invertedIndexFieldsForNonBulkLoadOps.length; k++) {
                    invertedIndexFieldsForNonBulkLoadOps[k] = k;
                }
            }
            ICcApplicationContext appContext = (ICcApplicationContext) context.getAppContext();
            Pair<IFileSplitProvider, AlgebricksPartitionConstraint> secondarySplitsAndConstraint =
                    metadataProvider.getSplitProviderAndConstraints(dataset, indexName);
            // TODO: Here we assume there is only one search key field.
            int queryField = keyFields[0];
            // Get tokenizer and search modifier factories.
            IInvertedIndexSearchModifierFactory searchModifierFactory =
                    InvertedIndexAccessMethod.getSearchModifierFactory(searchModifierType, simThresh, secondaryIndex);
            IBinaryTokenizerFactory queryTokenizerFactory = InvertedIndexAccessMethod
                    .getBinaryTokenizerFactory(searchModifierType, searchKeyType, secondaryIndex);
            ARecordType metaType = dataset.hasMetaPart()
                    ? (ARecordType) metadataProvider
                            .findType(dataset.getMetaItemTypeDataverseName(), dataset.getMetaItemTypeName()).getType()
                    : null;
            Pair<ILSMMergePolicyFactory, Map<String, String>> compactionInfo =
                    DatasetUtil.getMergePolicyFactory(dataset, metadataProvider.getMetadataTxnContext());
            IIndexDataflowHelperFactory dataflowHelperFactory = dataset.getIndexDataflowHelperFactory(metadataProvider,
                    secondaryIndex, recordType, metaType, compactionInfo.first, compactionInfo.second);
            LSMInvertedIndexSearchOperatorDescriptor invIndexSearchOp = new LSMInvertedIndexSearchOperatorDescriptor(
                    jobSpec, queryField, appContext.getStorageManager(), secondarySplitsAndConstraint.first,
                    appContext.getIndexLifecycleManagerProvider(), tokenTypeTraits, tokenComparatorFactories,
                    invListsTypeTraits, invListsComparatorFactories, dataflowHelperFactory, queryTokenizerFactory,
                    searchModifierFactory, outputRecDesc, retainInput, retainMissing, context.getMissingWriterFactory(),
                    dataset.getSearchCallbackFactory(metadataProvider.getStorageComponentProvider(), secondaryIndex,
                            ((JobEventListenerFactory) jobSpec.getJobletEventListenerFactory()).getJobId(),
                            IndexOperation.SEARCH, null),
                    propagateIndexFilter, minFilterFieldIndexes, maxFilterFieldIndexes,
                    metadataProvider.getStorageComponentProvider().getMetadataPageManagerFactory(),
                    isFullTextSearchQuery);
            return new Pair<>(invIndexSearchOp, secondarySplitsAndConstraint.second);
        } catch (MetadataException e) {
            throw new AlgebricksException(e);
        }
    }
}

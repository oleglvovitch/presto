/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.planner;

import com.facebook.presto.Session;
import com.facebook.presto.metadata.FunctionInfo;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.metadata.Signature;
import com.facebook.presto.sql.planner.PlanFragment.OutputPartitioning;
import com.facebook.presto.sql.planner.PlanFragment.PlanDistribution;
import com.facebook.presto.sql.planner.plan.AggregationNode;
import com.facebook.presto.sql.planner.plan.DistinctLimitNode;
import com.facebook.presto.sql.planner.plan.ExchangeNode;
import com.facebook.presto.sql.planner.plan.FilterNode;
import com.facebook.presto.sql.planner.plan.IndexJoinNode;
import com.facebook.presto.sql.planner.plan.JoinNode;
import com.facebook.presto.sql.planner.plan.LimitNode;
import com.facebook.presto.sql.planner.plan.MarkDistinctNode;
import com.facebook.presto.sql.planner.plan.OutputNode;
import com.facebook.presto.sql.planner.plan.PlanFragmentId;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.PlanNodeId;
import com.facebook.presto.sql.planner.plan.PlanVisitor;
import com.facebook.presto.sql.planner.plan.ProjectNode;
import com.facebook.presto.sql.planner.plan.RowNumberNode;
import com.facebook.presto.sql.planner.plan.SampleNode;
import com.facebook.presto.sql.planner.plan.SemiJoinNode;
import com.facebook.presto.sql.planner.plan.SinkNode;
import com.facebook.presto.sql.planner.plan.SortNode;
import com.facebook.presto.sql.planner.plan.TableCommitNode;
import com.facebook.presto.sql.planner.plan.TableScanNode;
import com.facebook.presto.sql.planner.plan.TableWriterNode;
import com.facebook.presto.sql.planner.plan.TopNNode;
import com.facebook.presto.sql.planner.plan.TopNRowNumberNode;
import com.facebook.presto.sql.planner.plan.UnionNode;
import com.facebook.presto.sql.planner.plan.UnnestNode;
import com.facebook.presto.sql.planner.plan.ValuesNode;
import com.facebook.presto.sql.planner.plan.WindowNode;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.FunctionCall;
import com.facebook.presto.sql.tree.QualifiedNameReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.facebook.presto.SystemSessionProperties.isBigQueryEnabled;
import static com.facebook.presto.sql.planner.plan.AggregationNode.Step.FINAL;
import static com.facebook.presto.sql.planner.plan.AggregationNode.Step.PARTIAL;
import static com.facebook.presto.sql.planner.plan.AggregationNode.Step.SINGLE;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Splits a logical plan into fragments that can be shipped and executed on distributed nodes
 */
public class DistributedLogicalPlanner
{
    private final Session session;
    private final Metadata metadata;
    private final PlanNodeIdAllocator idAllocator;

    public DistributedLogicalPlanner(Session session, Metadata metadata, PlanNodeIdAllocator idAllocator)
    {
        this.session = checkNotNull(session, "session is null");
        this.metadata = checkNotNull(metadata, "metadata is null");
        this.idAllocator = checkNotNull(idAllocator, "idAllocator is null");
    }

    public SubPlan createSubPlans(Plan plan, boolean createSingleNodePlan, boolean distributedIndexJoins, boolean distributedJoins)
    {
        Visitor visitor = new Visitor(plan.getSymbolAllocator(), createSingleNodePlan, distributedIndexJoins, distributedJoins);
        SubPlanBuilder builder = plan.getRoot().accept(visitor, null);

        SubPlan subplan = builder.build();
        subplan.sanityCheck();

        return subplan;
    }

    private class Visitor
            extends PlanVisitor<Void, SubPlanBuilder>
    {
        private int nextFragmentId;

        private final SymbolAllocator allocator;
        private final boolean createSingleNodePlan;
        private final boolean distributedIndexJoins;
        private final boolean distributedJoins;

        public Visitor(SymbolAllocator allocator, boolean createSingleNodePlan, boolean distributedIndexJoins, boolean distributedJoins)
        {
            this.allocator = allocator;
            this.createSingleNodePlan = createSingleNodePlan;
            this.distributedIndexJoins = distributedIndexJoins;
            this.distributedJoins = distributedJoins;
        }

        @Override
        public SubPlanBuilder visitAggregation(final AggregationNode node, Void context)
        {
            SubPlanBuilder current = node.getSource().accept(this, context);

            if (!current.isDistributed()) {
                // add the aggregation node as the root of the current fragment
                current.setRoot(new AggregationNode(node.getId(),
                        current.getRoot(),
                        node.getGroupBy(),
                        node.getAggregations(),
                        node.getFunctions(),
                        node.getMasks(),
                        SINGLE,
                        node.getSampleWeight(),
                        node.getConfidence(),
                        node.getHashSymbol()));
                return current;
            }

            Map<Symbol, FunctionCall> aggregations = node.getAggregations();
            Map<Symbol, Signature> functions = node.getFunctions();
            Map<Symbol, Symbol> masks = node.getMasks();
            List<Symbol> groupBy = node.getGroupBy();

            boolean decomposable = true;
            for (Signature function : functions.values()) {
                if (!metadata.getExactFunction(function).getAggregationFunction().isDecomposable()) {
                    decomposable = false;
                    break;
                }
            }

            // else, we need to "close" the current fragment and create an unpartitioned fragment for the final aggregation
            if (decomposable) {
                return addDistributedAggregation(current, aggregations, functions, masks, groupBy, node.getSampleWeight(), node.getConfidence(), node.getHashSymbol());
            }
            return addSingleNodeAggregation(current, aggregations, functions, masks, groupBy, node.getSampleWeight(), node.getConfidence(), node.getHashSymbol());
        }

        @Override
        public SubPlanBuilder visitMarkDistinct(MarkDistinctNode node, Void context)
        {
            SubPlanBuilder current = node.getSource().accept(this, context);
            // Check if the subplan is already partitioned the way we want it
            boolean alreadyPartitioned = false;
            if (current.getDistribution() == PlanDistribution.FIXED) {
                for (SubPlan child : current.getChildren()) {
                    if (child.getFragment().getOutputPartitioning() == OutputPartitioning.HASH &&
                            ImmutableSet.copyOf(child.getFragment().getPartitionBy()).equals(ImmutableSet.copyOf(node.getDistinctSymbols()))) {
                        alreadyPartitioned = true;
                        break;
                    }
                }
            }
            Optional<Symbol> hashSymbol = node.getHashSymbol();
            if (createSingleNodePlan || alreadyPartitioned || (!current.isDistributed() && !isBigQueryEnabled(session, false))) {
                MarkDistinctNode markNode = new MarkDistinctNode(idAllocator.getNextId(), current.getRoot(), node.getMarkerSymbol(), node.getDistinctSymbols(), hashSymbol);
                current.setRoot(markNode);
                return current;
            }
            else {
                PlanNode sink = new SinkNode(idAllocator.getNextId(), current.getRoot(), current.getRoot().getOutputSymbols());

                current.setRoot(sink)
                        .setHashOutputPartitioning(node.getDistinctSymbols(), hashSymbol);

                PlanNode exchange = new ExchangeNode(idAllocator.getNextId(), current.getId(), sink.getOutputSymbols());
                MarkDistinctNode markNode = new MarkDistinctNode(idAllocator.getNextId(), exchange, node.getMarkerSymbol(), node.getDistinctSymbols(), hashSymbol);
                return createFixedDistributionPlan(markNode)
                        .addChild(current.build());
            }
        }

        private SubPlanBuilder addSingleNodeAggregation(SubPlanBuilder plan, Map<Symbol, FunctionCall> aggregations, Map<Symbol, Signature> functions, Map<Symbol, Symbol> masks, List<Symbol> groupBy, Optional<Symbol> sampleWeight, double confidence, Optional<Symbol> hashSymbol)
        {
            plan.setRoot(new SinkNode(idAllocator.getNextId(), plan.getRoot(), plan.getRoot().getOutputSymbols()));

            // create aggregation plan
            ExchangeNode source = new ExchangeNode(idAllocator.getNextId(), plan.getId(), plan.getRoot().getOutputSymbols());
            AggregationNode aggregation = new AggregationNode(idAllocator.getNextId(), source, groupBy, aggregations, functions, masks, SINGLE, sampleWeight, confidence, hashSymbol);
            plan = createSingleNodePlan(aggregation).addChild(plan.build());

            return plan;
        }

        private SubPlanBuilder addDistributedAggregation(SubPlanBuilder plan, Map<Symbol, FunctionCall> aggregations, Map<Symbol, Signature> functions, Map<Symbol, Symbol> masks, List<Symbol> groupBy, Optional<Symbol> sampleWeight, double confidence, Optional<Symbol> hashSymbol)
        {
            Map<Symbol, FunctionCall> finalCalls = new HashMap<>();
            Map<Symbol, FunctionCall> intermediateCalls = new HashMap<>();
            Map<Symbol, Signature> intermediateFunctions = new HashMap<>();
            Map<Symbol, Symbol> intermediateMask = new HashMap<>();
            for (Map.Entry<Symbol, FunctionCall> entry : aggregations.entrySet()) {
                Signature signature = functions.get(entry.getKey());
                FunctionInfo function = metadata.getExactFunction(signature);

                Symbol intermediateSymbol = allocator.newSymbol(function.getName().getSuffix(), metadata.getType(function.getIntermediateType()));
                intermediateCalls.put(intermediateSymbol, entry.getValue());
                intermediateFunctions.put(intermediateSymbol, signature);
                if (masks.containsKey(entry.getKey())) {
                    intermediateMask.put(intermediateSymbol, masks.get(entry.getKey()));
                }

                // rewrite final aggregation in terms of intermediate function
                finalCalls.put(entry.getKey(), new FunctionCall(function.getName(), ImmutableList.<Expression>of(new QualifiedNameReference(intermediateSymbol.toQualifiedName()))));
            }

            // create partial aggregation plan
            AggregationNode partialAggregation = new AggregationNode(idAllocator.getNextId(), plan.getRoot(), groupBy, intermediateCalls, intermediateFunctions, intermediateMask, PARTIAL, sampleWeight, confidence, hashSymbol);
            plan.setRoot(new SinkNode(idAllocator.getNextId(), partialAggregation, partialAggregation.getOutputSymbols()));

            // create final aggregation plan
            ExchangeNode source = new ExchangeNode(idAllocator.getNextId(), plan.getId(), plan.getRoot().getOutputSymbols());
            AggregationNode finalAggregation = new AggregationNode(idAllocator.getNextId(), source, groupBy, finalCalls, functions, ImmutableMap.of(), FINAL, Optional.empty(), confidence, hashSymbol);

            if (groupBy.isEmpty()) {
                plan = createSingleNodePlan(finalAggregation)
                        .addChild(plan.build());
            }
            else {
                plan.setHashOutputPartitioning(groupBy, hashSymbol);
                plan = createFixedDistributionPlan(finalAggregation)
                        .addChild(plan.build());
            }
            return plan;
        }

        @Override
        public SubPlanBuilder visitWindow(WindowNode node, Void context)
        {
            SubPlanBuilder current = node.getSource().accept(this, context);

            if (current.isDistributed()) {
                List<Symbol> partitionedBy = node.getPartitionBy();
                current.setRoot(new SinkNode(idAllocator.getNextId(), current.getRoot(), current.getRoot().getOutputSymbols()));

                ExchangeNode source = new ExchangeNode(idAllocator.getNextId(), current.getId(), current.getRoot().getOutputSymbols());
                if (partitionedBy.isEmpty()) {
                    // create a new non-partitioned fragment
                    current = createSingleNodePlan(source)
                            .addChild(current.build());
                }
                else {
                    current.setHashOutputPartitioning(partitionedBy, node.getHashSymbol());
                    current = createFixedDistributionPlan(source)
                            .addChild(current.build());
                }
            }

            current.setRoot(new WindowNode(node.getId(), current.getRoot(), node.getPartitionBy(), node.getOrderBy(), node.getOrderings(), node.getFrame(), node.getWindowFunctions(), node.getSignatures(), node.getHashSymbol()));

            return current;
        }

        @Override
        public SubPlanBuilder visitRowNumber(RowNumberNode node, Void context)
        {
            SubPlanBuilder current = node.getSource().accept(this, context);
            if (current.isDistributed()) {
                List<Symbol> partitionedBy = node.getPartitionBy();
                current.setRoot(new SinkNode(idAllocator.getNextId(), current.getRoot(), current.getRoot().getOutputSymbols()));

                ExchangeNode source = new ExchangeNode(idAllocator.getNextId(), current.getId(), current.getRoot().getOutputSymbols());
                if (node.getPartitionBy().isEmpty()) {
                    current = createSingleNodePlan(source).addChild(current.build());
                }
                else {
                    current.setHashOutputPartitioning(partitionedBy, node.getHashSymbol());
                    current = createFixedDistributionPlan(source)
                            .addChild(current.build());
                }
            }

            current.setRoot(new RowNumberNode(node.getId(), current.getRoot(), node.getPartitionBy(), node.getRowNumberSymbol(), node.getMaxRowCountPerPartition(), node.getHashSymbol()));

            return current;
        }

        @Override
        public SubPlanBuilder visitTopNRowNumber(TopNRowNumberNode node, Void context)
        {
            SubPlanBuilder current = node.getSource().accept(this, context);
            if (current.isDistributed()) {
                current.setRoot(new TopNRowNumberNode(node.getId(),
                        current.getRoot(),
                        node.getPartitionBy(),
                        node.getOrderBy(),
                        node.getOrderings(),
                        node.getRowNumberSymbol(),
                        node.getMaxRowCountPerPartition(),
                        true,
                        node.getHashSymbol()));
                current.setRoot(new SinkNode(idAllocator.getNextId(), current.getRoot(), current.getRoot().getOutputSymbols()));
                ExchangeNode source = new ExchangeNode(idAllocator.getNextId(), current.getId(), current.getRoot().getOutputSymbols());
                TopNRowNumberNode merge = new TopNRowNumberNode(node.getId(),
                        source,
                        node.getPartitionBy(),
                        node.getOrderBy(),
                        node.getOrderings(),
                        node.getRowNumberSymbol(),
                        node.getMaxRowCountPerPartition(),
                        false,
                        node.getHashSymbol());
                if (node.getPartitionBy().isEmpty()) {
                    current = createSingleNodePlan(merge).addChild(current.build());
                }
                else {
                    current.setHashOutputPartitioning(node.getPartitionBy(), node.getHashSymbol());
                    current = createFixedDistributionPlan(merge)
                            .addChild(current.build());
                }
            }
            else {
                current.setRoot(new TopNRowNumberNode(node.getId(), current.getRoot(), node.getPartitionBy(), node.getOrderBy(), node.getOrderings(), node.getRowNumberSymbol(), node.getMaxRowCountPerPartition(), false, node.getHashSymbol()));
            }
            return current;
        }

        @Override
        public SubPlanBuilder visitFilter(FilterNode node, Void context)
        {
            SubPlanBuilder current = node.getSource().accept(this, context);
            current.setRoot(new FilterNode(node.getId(), current.getRoot(), node.getPredicate()));
            return current;
        }

        @Override
        public SubPlanBuilder visitSample(SampleNode node, Void context)
        {
            SubPlanBuilder current = node.getSource().accept(this, context);
            current.setRoot(new SampleNode(node.getId(), current.getRoot(), node.getSampleRatio(), node.getSampleType(), node.isRescaled(), node.getSampleWeightSymbol()));
            return current;
        }

        @Override
        public SubPlanBuilder visitProject(ProjectNode node, Void context)
        {
            SubPlanBuilder current = node.getSource().accept(this, context);
            current.setRoot(new ProjectNode(node.getId(), current.getRoot(), node.getAssignments()));
            return current;
        }

        @Override
        public SubPlanBuilder visitUnnest(UnnestNode node, Void context)
        {
            SubPlanBuilder current = node.getSource().accept(this, context);
            current.setRoot(new UnnestNode(node.getId(), current.getRoot(), node.getReplicateSymbols(), node.getUnnestSymbols()));
            return current;
        }

        @Override
        public SubPlanBuilder visitTopN(TopNNode node, Void context)
        {
            SubPlanBuilder current = node.getSource().accept(this, context);

            // use partial TopN plan if distributed
            boolean partial = current.isDistributed();

            current.setRoot(new TopNNode(node.getId(), current.getRoot(), node.getCount(), node.getOrderBy(), node.getOrderings(), partial));

            if (current.isDistributed()) {
                current.setRoot(new SinkNode(idAllocator.getNextId(), current.getRoot(), current.getRoot().getOutputSymbols()));

                // create merge plan fragment
                PlanNode source = new ExchangeNode(idAllocator.getNextId(), current.getId(), current.getRoot().getOutputSymbols());
                TopNNode merge = new TopNNode(idAllocator.getNextId(), source, node.getCount(), node.getOrderBy(), node.getOrderings(), false);
                current = createSingleNodePlan(merge)
                        .addChild(current.build());
            }

            return current;
        }

        @Override
        public SubPlanBuilder visitSort(SortNode node, Void context)
        {
            SubPlanBuilder current = node.getSource().accept(this, context);

            if (current.isDistributed()) {
                current.setRoot(new SinkNode(idAllocator.getNextId(), current.getRoot(), current.getRoot().getOutputSymbols()));

                // create a new non-partitioned fragment
                current = createSingleNodePlan(new ExchangeNode(idAllocator.getNextId(), current.getId(), current.getRoot().getOutputSymbols()))
                        .addChild(current.build());
            }

            current.setRoot(new SortNode(node.getId(), current.getRoot(), node.getOrderBy(), node.getOrderings()));

            return current;
        }

        @Override
        public SubPlanBuilder visitOutput(OutputNode node, Void context)
        {
            SubPlanBuilder current = node.getSource().accept(this, context);

            if (current.isDistributed()) {
                current.setRoot(new SinkNode(idAllocator.getNextId(), current.getRoot(), current.getRoot().getOutputSymbols()));

                // create a new non-partitioned fragment
                current = createSingleNodePlan(new ExchangeNode(idAllocator.getNextId(), current.getId(), current.getRoot().getOutputSymbols()))
                        .addChild(current.build());
            }

            current.setRoot(new OutputNode(node.getId(), current.getRoot(), node.getColumnNames(), node.getOutputSymbols()));

            return current;
        }

        @Override
        public SubPlanBuilder visitLimit(LimitNode node, Void context)
        {
            SubPlanBuilder current = node.getSource().accept(this, context);

            current.setRoot(new LimitNode(node.getId(), current.getRoot(), node.getCount()));

            if (current.isDistributed()) {
                current.setRoot(new SinkNode(idAllocator.getNextId(), current.getRoot(), current.getRoot().getOutputSymbols()));

                // create merge plan fragment
                PlanNode source = new ExchangeNode(idAllocator.getNextId(), current.getId(), current.getRoot().getOutputSymbols());
                LimitNode merge = new LimitNode(idAllocator.getNextId(), source, node.getCount());
                current = createSingleNodePlan(merge)
                        .addChild(current.build());
            }

            return current;
        }

        @Override
        public SubPlanBuilder visitDistinctLimit(DistinctLimitNode node, Void context)
        {
            SubPlanBuilder current = node.getSource().accept(this, context);

            current.setRoot(new DistinctLimitNode(node.getId(), current.getRoot(), node.getLimit(), node.getHashSymbol()));

            if (current.isDistributed()) {
                current.setRoot(new SinkNode(idAllocator.getNextId(), current.getRoot(), current.getRoot().getOutputSymbols()));

                PlanNode source = new ExchangeNode(idAllocator.getNextId(), current.getId(), current.getRoot().getOutputSymbols());
                DistinctLimitNode merge = new DistinctLimitNode(idAllocator.getNextId(), source, node.getLimit(), node.getHashSymbol());
                current = createSingleNodePlan(merge).addChild(current.build());
            }
            return current;
        }

        @Override
        public SubPlanBuilder visitTableScan(TableScanNode node, Void context)
        {
            return createSourceDistributionPlan(node, node.getId());
        }

        @Override
        public SubPlanBuilder visitValues(ValuesNode node, Void context)
        {
            return createSingleNodePlan(node);
        }

        @Override
        public SubPlanBuilder visitTableWriter(TableWriterNode node, Void context)
        {
            SubPlanBuilder current = node.getSource().accept(this, context);
            current.setRoot(new TableWriterNode(node.getId(), current.getRoot(), node.getTarget(), node.getColumns(), node.getColumnNames(), node.getOutputSymbols(), node.getSampleWeightSymbol()));
            return current;
        }

        @Override
        public SubPlanBuilder visitTableCommit(TableCommitNode node, Void context)
        {
            SubPlanBuilder current = node.getSource().accept(this, context);

            if (current.getDistribution() != PlanDistribution.COORDINATOR_ONLY && !createSingleNodePlan) {
                current.setRoot(new SinkNode(idAllocator.getNextId(), current.getRoot(), current.getRoot().getOutputSymbols()));

                // create a new non-partitioned fragment to run on the coordinator
                current = createCoordinatorOnlyPlan(new ExchangeNode(idAllocator.getNextId(), current.getId(), current.getRoot().getOutputSymbols()))
                        .addChild(current.build());
            }

            current.setRoot(new TableCommitNode(node.getId(), current.getRoot(), node.getTarget(), node.getOutputSymbols()));

            return current;
        }

        @Override
        public SubPlanBuilder visitJoin(JoinNode node, Void context)
        {
            SubPlanBuilder left = node.getLeft().accept(this, context);
            SubPlanBuilder right = node.getRight().accept(this, context);

            if (left.isDistributed() || right.isDistributed()) {
                List<Symbol> leftSymbols = Lists.transform(node.getCriteria(), JoinNode.EquiJoinClause::getLeft);
                List<Symbol> rightSymbols = Lists.transform(node.getCriteria(), JoinNode.EquiJoinClause::getRight);

                switch (node.getType()) {
                    case INNER:
                    case LEFT:
                        right.setRoot(new SinkNode(idAllocator.getNextId(), right.getRoot(), right.getRoot().getOutputSymbols()));
                        if (distributedJoins) {
                            right.setHashOutputPartitioning(rightSymbols, node.getRightHashSymbol());
                            left = hashDistributeSubplan(left, leftSymbols, node.getLeftHashSymbol());
                        }
                        left.setRoot(new JoinNode(node.getId(),
                                node.getType(),
                                left.getRoot(),
                                new ExchangeNode(idAllocator.getNextId(), right.getId(), right.getRoot().getOutputSymbols()),
                                node.getCriteria(), node.getLeftHashSymbol(), node.getRightHashSymbol()));
                        left.addChild(right.build());

                        return left;
                    case RIGHT:
                        left.setRoot(new SinkNode(idAllocator.getNextId(), left.getRoot(), left.getRoot().getOutputSymbols()));
                        if (distributedJoins) {
                            left.setHashOutputPartitioning(leftSymbols, node.getLeftHashSymbol());
                            right = hashDistributeSubplan(right, rightSymbols,  node.getRightHashSymbol());
                        }
                        right.setRoot(new JoinNode(node.getId(),
                                node.getType(),
                                new ExchangeNode(idAllocator.getNextId(), left.getId(), left.getRoot().getOutputSymbols()),
                                right.getRoot(),
                                node.getCriteria(), node.getLeftHashSymbol(), node.getRightHashSymbol()));
                        right.addChild(left.build());

                        return right;
                    default:
                        throw new UnsupportedOperationException("Unsupported join type: " + node.getType());
                }
            }
            else {
                JoinNode join = new JoinNode(node.getId(), node.getType(), left.getRoot(), right.getRoot(), node.getCriteria(), node.getLeftHashSymbol(), node.getRightHashSymbol());
                return createSingleNodePlan(join)
                        .setChildren(Iterables.concat(left.getChildren(), right.getChildren()));
            }
        }

        public SubPlanBuilder hashDistributeSubplan(SubPlanBuilder subPlan, List<Symbol> symbols, Optional<Symbol> hashSymbol)
        {
            PlanNode sink = new SinkNode(idAllocator.getNextId(), subPlan.getRoot(), subPlan.getRoot().getOutputSymbols());
            subPlan.setRoot(sink).setHashOutputPartitioning(symbols, hashSymbol);

            PlanNode exchange = new ExchangeNode(idAllocator.getNextId(), subPlan.getId(), sink.getOutputSymbols());
            subPlan = createFixedDistributionPlan(exchange)
                    .addChild(subPlan.build());
            return subPlan;
        }

        @Override
        public SubPlanBuilder visitSemiJoin(SemiJoinNode node, Void context)
        {
            SubPlanBuilder source = node.getSource().accept(this, context);
            SubPlanBuilder filteringSource = node.getFilteringSource().accept(this, context);

            if (source.isDistributed() || filteringSource.isDistributed()) {
                filteringSource.setRoot(new SinkNode(idAllocator.getNextId(), filteringSource.getRoot(), filteringSource.getRoot().getOutputSymbols()));
                source.setRoot(new SemiJoinNode(node.getId(),
                        source.getRoot(),
                        new ExchangeNode(idAllocator.getNextId(), filteringSource.getId(), filteringSource.getRoot().getOutputSymbols()),
                        node.getSourceJoinSymbol(),
                        node.getFilteringSourceJoinSymbol(),
                        node.getSemiJoinOutput(),
                        node.getSourceHashSymbol(),
                        node.getFilteringSourceHashSymbol()));
                source.addChild(filteringSource.build());

                return source;
            }
            else {
                SemiJoinNode semiJoinNode = new SemiJoinNode(node.getId(),
                        source.getRoot(),
                        filteringSource.getRoot(),
                        node.getSourceJoinSymbol(),
                        node.getFilteringSourceJoinSymbol(),
                        node.getSemiJoinOutput(),
                        node.getSourceHashSymbol(),
                        node.getFilteringSourceHashSymbol());
                return createSingleNodePlan(semiJoinNode)
                        .setChildren(Iterables.concat(source.getChildren(), filteringSource.getChildren()));
            }
        }

        @Override
        public SubPlanBuilder visitIndexJoin(IndexJoinNode node, Void context)
        {
            SubPlanBuilder current = node.getProbeSource().accept(this, context);

            if (distributedIndexJoins && current.isDistributed()) {
                PlanNode sink = new SinkNode(idAllocator.getNextId(), current.getRoot(), current.getRoot().getOutputSymbols());

                current.setRoot(sink)
                        .setHashOutputPartitioning(Lists.transform(node.getCriteria(), IndexJoinNode.EquiJoinClause::getProbe), node.getProbeHashSymbol());

                PlanNode exchange = new ExchangeNode(idAllocator.getNextId(), current.getId(), sink.getOutputSymbols());
                IndexJoinNode indexJoinNode = new IndexJoinNode(node.getId(), node.getType(), exchange, node.getIndexSource(), node.getCriteria(), node.getProbeHashSymbol(), node.getIndexHashSymbol());
                return createFixedDistributionPlan(indexJoinNode).addChild(current.build());
            }
            else {
                current.setRoot(new IndexJoinNode(node.getId(), node.getType(), current.getRoot(), node.getIndexSource(), node.getCriteria(), node.getProbeHashSymbol(), node.getIndexHashSymbol()));
                return current;
            }
        }

        @Override
        public SubPlanBuilder visitUnion(UnionNode node, Void context)
        {
            if (createSingleNodePlan) {
                ImmutableList.Builder<PlanNode> sourceBuilder = ImmutableList.builder();
                for (PlanNode source : node.getSources()) {
                    sourceBuilder.add(source.accept(this, context).getRoot());
                }
                UnionNode unionNode = new UnionNode(node.getId(), sourceBuilder.build(), node.getSymbolMapping());
                return createSingleNodePlan(unionNode);
            }
            else {
                ImmutableList.Builder<SubPlan> sourceBuilder = ImmutableList.builder();
                ImmutableList.Builder<PlanFragmentId> fragmentIdBuilder = ImmutableList.builder();
                for (int i = 0; i < node.getSources().size(); i++) {
                    PlanNode subPlan = node.getSources().get(i);
                    SubPlanBuilder current = subPlan.accept(this, context);
                    current.setRoot(new SinkNode(idAllocator.getNextId(), current.getRoot(), node.sourceOutputLayout(i)));
                    fragmentIdBuilder.add(current.getId());
                    sourceBuilder.add(current.build());
                }
                ExchangeNode exchangeNode = new ExchangeNode(idAllocator.getNextId(), fragmentIdBuilder.build(), node.getOutputSymbols());
                return createSingleNodePlan(exchangeNode)
                        .setChildren(sourceBuilder.build());
            }
        }

        @Override
        protected SubPlanBuilder visitPlan(PlanNode node, Void context)
        {
            throw new UnsupportedOperationException("not yet implemented: " + node.getClass().getName());
        }

        public SubPlanBuilder createSingleNodePlan(PlanNode root)
        {
            return new SubPlanBuilder(new PlanFragmentId(nextSubPlanId()), allocator, PlanDistribution.NONE, root, null);
        }

        public SubPlanBuilder createFixedDistributionPlan(PlanNode root)
        {
            return new SubPlanBuilder(new PlanFragmentId(nextSubPlanId()), allocator, PlanDistribution.FIXED, root, null);
        }

        public SubPlanBuilder createSourceDistributionPlan(PlanNode root, PlanNodeId partitionedSourceId)
        {
            if (createSingleNodePlan) {
                // when creating a single node plan, we tell the planner that the table is not partitioned,
                // but we still need to set the source id for the execution engine
                return new SubPlanBuilder(new PlanFragmentId(nextSubPlanId()), allocator, PlanDistribution.NONE, root, partitionedSourceId);
            }
            else {
                return new SubPlanBuilder(new PlanFragmentId(nextSubPlanId()), allocator, PlanDistribution.SOURCE, root, partitionedSourceId);
            }
        }

        public SubPlanBuilder createCoordinatorOnlyPlan(PlanNode root)
        {
            return new SubPlanBuilder(new PlanFragmentId(nextSubPlanId()), allocator, PlanDistribution.COORDINATOR_ONLY, root, null);
        }

        private String nextSubPlanId()
        {
            return String.valueOf(nextFragmentId++);
        }
    }
}

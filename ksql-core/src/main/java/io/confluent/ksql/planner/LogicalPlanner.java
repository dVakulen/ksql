/**
 * Copyright 2017 Confluent Inc.
 **/
package io.confluent.ksql.planner;

import io.confluent.ksql.analyzer.AggregateAnalysis;
import io.confluent.ksql.analyzer.Analysis;
import io.confluent.ksql.metastore.KSQLSTDOUT;
import io.confluent.ksql.metastore.KSQLStream;
import io.confluent.ksql.metastore.KSQLTable;
import io.confluent.ksql.metastore.StructuredDataSource;
import io.confluent.ksql.parser.tree.Expression;
import io.confluent.ksql.planner.plan.AggregateNode;
import io.confluent.ksql.planner.plan.FilterNode;
import io.confluent.ksql.planner.plan.KSQLBareOutputNode;
import io.confluent.ksql.planner.plan.KSQLStructuredDataOutputNode;
import io.confluent.ksql.planner.plan.OutputNode;
import io.confluent.ksql.planner.plan.PlanNode;
import io.confluent.ksql.planner.plan.PlanNodeId;
import io.confluent.ksql.planner.plan.ProjectNode;
import io.confluent.ksql.planner.plan.SourceNode;
import io.confluent.ksql.planner.plan.StructuredDataSourceNode;
import io.confluent.ksql.util.ExpressionTypeManager;
import io.confluent.ksql.util.SchemaUtil;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;

import java.util.ArrayList;
import java.util.List;

public class LogicalPlanner {

  Analysis analysis;
  AggregateAnalysis aggregateAnalysis;

  public LogicalPlanner(Analysis analysis, AggregateAnalysis aggregateAnalysis) {
    this.analysis = analysis;
    this.aggregateAnalysis = aggregateAnalysis;
  }

  public PlanNode buildPlan() {
    PlanNode currentNode;
    if (analysis.getJoin() != null) {
      currentNode = analysis.getJoin();
    } else {
      SourceNode sourceNode = buildSourceNode();
      currentNode = sourceNode;
    }
    if (analysis.getWhereExpression() != null) {
      FilterNode filterNode = buildFilterNode(currentNode.getSchema(), currentNode);
      currentNode = filterNode;
    }
    if ((analysis.getGroupByExpressions() != null) && (!analysis.getGroupByExpressions()
        .isEmpty())) {
      AggregateNode aggregateNode = buildAggregateNode(currentNode.getSchema(), currentNode);
      currentNode = aggregateNode;
    } else {
      ProjectNode projectNode = buildProjectNode(currentNode.getSchema(), currentNode);
      currentNode = projectNode;
    }

    OutputNode outputNode = buildOutputNode(currentNode.getSchema(), currentNode);
    return outputNode;
  }

  private OutputNode buildOutputNode(final Schema inputSchema, final PlanNode sourcePlanNode) {
    StructuredDataSource intoDataSource = analysis.getInto();

    if (intoDataSource instanceof KSQLSTDOUT) {
      return new KSQLBareOutputNode(new PlanNodeId(KSQLSTDOUT.KSQL_STDOUT_NAME), sourcePlanNode,
                                      inputSchema);
    } else if (intoDataSource instanceof StructuredDataSource) {
      StructuredDataSource intoStructuredDataSource = (StructuredDataSource) intoDataSource;

      return new KSQLStructuredDataOutputNode(new PlanNodeId(intoDataSource.getName()),
                                             sourcePlanNode,
                                             inputSchema, intoStructuredDataSource.getKsqlTopic(),
                                             intoStructuredDataSource.getKsqlTopic()
                                                 .getTopicName(), analysis.getIntoProperties());

    }
    throw new RuntimeException("INTO clause is not supported in SELECT.");
  }

  private AggregateNode buildAggregateNode(final Schema inputSchema,
                                           final PlanNode sourcePlanNode) {

    SchemaBuilder aggregateSchema = SchemaBuilder.struct();
    ExpressionTypeManager expressionTypeManager = new ExpressionTypeManager(inputSchema);
    for (int i = 0; i < analysis.getSelectExpressions().size(); i++) {
      Expression expression = analysis.getSelectExpressions().get(i);
      String alias = analysis.getSelectExpressionAlias().get(i);

      Schema expressionType = expressionTypeManager.getExpressionType(expression);

      aggregateSchema = aggregateSchema.field(alias, expressionType);

    }

    return new AggregateNode(new PlanNodeId("Aggregate"), sourcePlanNode, aggregateSchema,
                             analysis.getSelectExpressions(), analysis.getGroupByExpressions(),
                             analysis.getWindowExpression(),
                             aggregateAnalysis.getAggregateFunctionArguments(),
                             aggregateAnalysis.getFunctionList(),
                             aggregateAnalysis.getRequiredColumnsList(),
                             aggregateAnalysis.getNonAggResultColumns(),
                             aggregateAnalysis.getFinalSelectExpressions(),
                             aggregateAnalysis.getHavingExpression());
  }

  private ProjectNode buildProjectNode(final Schema inputSchema, final PlanNode sourcePlanNode) {
    List<Field> projectionFields = new ArrayList<>();
    List<String> fieldNames = new ArrayList<>();

    SchemaBuilder projectionSchema = SchemaBuilder.struct();
    ExpressionTypeManager expressionTypeManager = new ExpressionTypeManager(inputSchema);
    for (int i = 0; i < analysis.getSelectExpressions().size(); i++) {
      Expression expression = analysis.getSelectExpressions().get(i);
      String alias = analysis.getSelectExpressionAlias().get(i);

      Schema expressionType = expressionTypeManager.getExpressionType(expression);

      projectionSchema = projectionSchema.field(alias, expressionType);

    }

    return new ProjectNode(new PlanNodeId("Project"), sourcePlanNode, projectionSchema,
                           analysis.getSelectExpressions());
  }

  private FilterNode buildFilterNode(final Schema inputSchema, final PlanNode sourcePlanNode) {

    Expression filterExpression = analysis.getWhereExpression();
    return new FilterNode(new PlanNodeId("Filter"), sourcePlanNode, filterExpression);
  }

  private SourceNode buildSourceNode() {

    StructuredDataSource fromDataSource = analysis.getFromDataSources().get(0).getLeft();
    String alias = analysis.getFromDataSources().get(0).getRight();
    Schema fromSchema = SchemaUtil.buildSchemaWithAlias(fromDataSource.getSchema(), alias);

    if (fromDataSource instanceof KSQLStream) {
      KSQLStream fromStream = (KSQLStream) fromDataSource;
      return new StructuredDataSourceNode(new PlanNodeId("KSQLTopic"), fromSchema,
                                          fromDataSource.getKeyField(),
                                          fromStream.getKsqlTopic().getTopicName(),
                                          alias, fromStream.getDataSourceType(),
                                          fromStream);
    } else if (fromDataSource instanceof KSQLTable) {
      KSQLTable fromTable = (KSQLTable) fromDataSource;
      return new StructuredDataSourceNode(new PlanNodeId("KSQLTopic"), fromSchema,
                                          fromDataSource.getKeyField(),
                                          fromTable.getKsqlTopic().getTopicName(),
                                          alias, fromTable.getDataSourceType(),
                                          fromTable);
    }

    throw new RuntimeException("Data source is not supported yet.");
  }

}
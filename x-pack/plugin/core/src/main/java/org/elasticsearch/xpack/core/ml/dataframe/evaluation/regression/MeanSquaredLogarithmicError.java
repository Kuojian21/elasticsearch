/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.core.ml.dataframe.evaluation.regression;

import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.script.Script;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.PipelineAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.NumericMetricsAggregation;
import org.elasticsearch.xpack.core.ml.dataframe.evaluation.EvaluationMetric;
import org.elasticsearch.xpack.core.ml.dataframe.evaluation.EvaluationMetricResult;
import org.elasticsearch.xpack.core.ml.dataframe.evaluation.EvaluationParameters;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import static org.elasticsearch.common.xcontent.ConstructingObjectParser.optionalConstructorArg;
import static org.elasticsearch.xpack.core.ml.dataframe.evaluation.MlEvaluationNamedXContentProvider.registeredMetricName;

/**
 * Calculates the mean squared error between two known numerical fields.
 *
 * equation: msle = 1/n * Σ(log(y + offset) - log(y´ + offset))^2
 * where offset is used to make sure the argument to log function is always positive
 */
public class MeanSquaredLogarithmicError implements EvaluationMetric {

    public static final ParseField NAME = new ParseField("mean_squared_logarithmic_error");

    public static final ParseField OFFSET = new ParseField("offset");
    private static final double DEFAULT_OFFSET = 1.0;

    private static final String PAINLESS_TEMPLATE =
        "def offset = {2};" +
        "def diff = Math.log(doc[''{0}''].value + offset) - Math.log(doc[''{1}''].value + offset);" +
        "return diff * diff;";
    private static final String AGG_NAME = "regression_" + NAME.getPreferredName();

    private static String buildScript(Object...args) {
        return new MessageFormat(PAINLESS_TEMPLATE, Locale.ROOT).format(args);
    }

    private static final ConstructingObjectParser<MeanSquaredLogarithmicError, Void> PARSER =
        new ConstructingObjectParser<>(NAME.getPreferredName(), true, args -> new MeanSquaredLogarithmicError((Double) args[0]));

    static {
        PARSER.declareDouble(optionalConstructorArg(), OFFSET);
    }

    public static MeanSquaredLogarithmicError fromXContent(XContentParser parser) {
        return PARSER.apply(parser, null);
    }

    private final double offset;
    private EvaluationMetricResult result;

    public MeanSquaredLogarithmicError(StreamInput in) throws IOException {
        this.offset = in.readDouble();
    }

    public MeanSquaredLogarithmicError(@Nullable Double offset) {
        this.offset = offset != null ? offset : DEFAULT_OFFSET;
    }

    @Override
    public String getName() {
        return NAME.getPreferredName();
    }

    @Override
    public Tuple<List<AggregationBuilder>, List<PipelineAggregationBuilder>> aggs(EvaluationParameters parameters,
                                                                                  String actualField,
                                                                                  String predictedField) {
        if (result != null) {
            return Tuple.tuple(Collections.emptyList(), Collections.emptyList());
        }
        return Tuple.tuple(
            Arrays.asList(AggregationBuilders.avg(AGG_NAME).script(new Script(buildScript(actualField, predictedField, offset)))),
            Collections.emptyList());
    }

    @Override
    public void process(Aggregations aggs) {
        NumericMetricsAggregation.SingleValue value = aggs.get(AGG_NAME);
        result = value == null ? new Result(0.0) : new Result(value.value());
    }

    @Override
    public Optional<EvaluationMetricResult> getResult() {
        return Optional.ofNullable(result);
    }

    @Override
    public String getWriteableName() {
        return registeredMetricName(Regression.NAME, NAME);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeDouble(offset);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(OFFSET.getPreferredName(), offset);
        builder.endObject();
        return builder;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MeanSquaredLogarithmicError that = (MeanSquaredLogarithmicError) o;
        return this.offset == that.offset;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(offset);
    }

    public static class Result implements EvaluationMetricResult {

        private static final String ERROR = "error";
        private final double error;

        public Result(double error) {
            this.error = error;
        }

        public Result(StreamInput in) throws IOException {
            this.error = in.readDouble();
        }

        @Override
        public String getWriteableName() {
            return registeredMetricName(Regression.NAME, NAME);
        }

        @Override
        public String getMetricName() {
            return NAME.getPreferredName();
        }

        public double getError() {
            return error;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeDouble(error);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field(ERROR, error);
            builder.endObject();
            return builder;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Result other = (Result)o;
            return error == other.error;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(error);
        }
    }
}

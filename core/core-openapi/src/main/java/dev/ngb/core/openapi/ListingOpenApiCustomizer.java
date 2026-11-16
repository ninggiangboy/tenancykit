package dev.ngb.core.openapi;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Op;
import dev.ngb.core.query.QueryParser;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.core.spring.query.ListingSchemas;
import dev.ngb.core.util.PageSize;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.DateTimeSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.UUIDSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.Comparator;
import java.util.List;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.web.method.HandlerMethod;

/**
 * Documents a {@link Listing} operation from the schema it names: one query parameter per filterable field and
 * operator, the sortable fields, and the paging parameters. The document therefore always describes what the endpoint
 * accepts. The {@link ListQuery} parameter itself is not part of the request and is hidden.
 */
public class ListingOpenApiCustomizer implements OperationCustomizer {

    private static final String CONTRACT_NOTE = """

            **Filtering, sorting and paging** follow the list-query contract. Conditions in `filter[field][op]` are \
            combined with AND. For OR, use `filter[or][i][field][op]`: predicates sharing the index `i` are AND-ed and \
            the branches are OR-ed (at most %d branches, %d predicates in all, %d values in an `in` list). \
            `neq` and `nin` do not match rows where the field is null. Send `limit` with either `offset` (at most %d) \
            or the `cursor` taken from a previous page, never both. The body is `{"data": [...], "page": null}`; \
            `page_info=true` fills `page` with `limit`, `offset`, `total` and `next_cursor`.""";

    static {
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(ListQuery.class);
    }

    @Override
    public Operation customize(Operation operation, HandlerMethod method) {
        Listing listing = method.getMethodAnnotation(Listing.class);
        if (listing == null) {
            return operation;
        }
        FilterSchema schema = ListingSchemas.of(listing.value());
        for (FieldSpec field : schema.fields()) {
            field.ops().stream().sorted(Comparator.comparing(Op::wire)).forEach(op -> operation.addParametersItem(filter(field, op)));
        }
        operation.addParametersItem(sort(schema));
        operation.addParametersItem(query("limit", "Page size, 1 to " + PageSize.MAX + ".", bounded(1, PageSize.MAX, QueryParser.DEFAULT_LIMIT)));
        operation.addParametersItem(query("offset", "Rows to skip, at most " + QueryParser.MAX_OFFSET + ". Not combinable with `cursor`.",
                bounded(0, QueryParser.MAX_OFFSET, 0)));
        operation.addParametersItem(query("cursor", "The `next_cursor` of the previous page. Valid only with the same filter and sort.", new StringSchema()));
        BooleanSchema pageInfo = new BooleanSchema();
        pageInfo.setDefault(false);
        operation.addParametersItem(query("page_info", "Fills `page` with the limit, offset, total and next cursor.", pageInfo));
        String note = CONTRACT_NOTE.formatted(QueryParser.MAX_OR_BRANCHES, QueryParser.MAX_PREDICATES, QueryParser.MAX_IN_VALUES, QueryParser.MAX_OFFSET);
        operation.setDescription(operation.getDescription() == null ? note.strip() : operation.getDescription() + "\n" + note);
        if (operation.getResponses() != null && !operation.getResponses().containsKey("400")) {
            operation.getResponses().addApiResponse("400", new ApiResponse().description(
                    "A filter, sort, cursor or paging parameter is unknown, not allowed for the field, or not valid for its type."));
        }
        return operation;
    }

    private static IntegerSchema bounded(int min, int max, int fallback) {
        IntegerSchema schema = new IntegerSchema();
        schema.setMinimum(java.math.BigDecimal.valueOf(min));
        schema.setMaximum(java.math.BigDecimal.valueOf(max));
        schema.setDefault(fallback);
        return schema;
    }

    private static Parameter filter(FieldSpec field, Op op) {
        String name = "filter[" + field.name() + "][" + op.wire() + "]";
        Schema<?> schema = op == Op.IS_NULL ? new BooleanSchema() : valueSchema(field);
        String description = switch (op) {
            case IN, NIN -> "Comma-separated list of up to " + QueryParser.MAX_IN_VALUES + " values; `" + op.wire() + "` of `" + field.name() + "`.";
            case IS_NULL -> "`true` for rows where `" + field.name() + "` is null, `false` for rows where it is set.";
            case STARTS_WITH -> "Case-insensitive prefix match on `" + field.name() + "`.";
            case CONTAINS -> "Case-insensitive substring match on `" + field.name() + "`.";
            default -> "`" + field.name() + "` " + op.wire() + " the value.";
        };
        if (op == Op.IN || op == Op.NIN) {
            schema = new StringSchema();
        }
        return query(name, description, schema);
    }

    private static Schema<?> valueSchema(FieldSpec field) {
        return switch (field.type()) {
            case STRING -> new StringSchema().maxLength(255);
            case UUID -> new UUIDSchema();
            case ENUM -> new StringSchema()._enum(field.values().stream().sorted().toList());
            case LONG -> new IntegerSchema().format("int64");
            case DECIMAL -> new NumberSchema();
            case BOOLEAN -> new BooleanSchema();
            case INSTANT -> new DateTimeSchema();
        };
    }

    private static Parameter sort(FilterSchema schema) {
        List<String> names = schema.fields().stream().filter(FieldSpec::sortable).map(FieldSpec::name).toList();
        String defaults = String.join(",", schema.defaultSort().stream().map(key -> key.wire()).toList());
        return query("sort", "Comma-separated sortable fields, each optionally prefixed with `-` for descending: "
                + String.join(", ", names) + ". At most " + QueryParser.MAX_SORT_KEYS + " fields; `" + schema.tieBreaker().name()
                + "` is appended to make the order total. Default: `" + defaults + "`.", new StringSchema());
    }

    private static Parameter query(String name, String description, Schema<?> schema) {
        return new Parameter().in("query").name(name).required(false).description(description).schema(schema);
    }
}

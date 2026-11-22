package dev.ngb.core.openapi;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Op;
import dev.ngb.core.query.QueryParser;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.core.spring.query.ListingSchemas;
import dev.ngb.core.util.PageSize;
import io.swagger.v3.oas.models.OpenAPI;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.web.method.HandlerMethod;

/**
 * Documents a {@link Listing} operation from the schema it names: one query parameter per filterable field and
 * operator, the sortable fields, and the paging parameters. The document therefore always describes what the endpoint
 * accepts. The {@link ListQuery} parameter itself is not part of the request and is hidden.
 *
 * <p>It also marks the paging facts the contract leaves absent: {@code page} is null unless the caller asked for it,
 * and inside it {@code offset} is null when a cursor positioned the page, {@code total} is null when the caller asked
 * only for the cursor, and {@code next_cursor} is null on the last page.
 */
public class ListingOpenApiCustomizer implements OperationCustomizer, OpenApiCustomizer {

    /** The paging-facts model every listing answers with, and the prefix of the wrappers that carry it. */
    private static final String PAGE_INFO = "PageInfo";

    private static final String PAGE_WRAPPER_PREFIX = "Page";

    /** The type a value that may be absent also carries, in OpenAPI 3.1. */
    private static final String NULL_TYPE = "null";

    private static final String CONTRACT_NOTE = """

            **Filtering, sorting and paging** follow the list-query contract. Conditions in `filter[field][op]` are \
            combined with AND. For OR, use `filter[or][i][field][op]`: predicates sharing the index `i` are AND-ed and \
            the branches are OR-ed (at most %d branches, %d predicates in all, %d values in an `in` list). \
            `neq` and `nin` do not match rows where the field is null. Send `limit` with either `offset` (at most %d) \
            or the `cursor` taken from a previous page, never both. The body is `{"data": [...], "page": null}`. \
            `page_info=full` fills `page` with `limit`, `offset`, `total` and `next_cursor`; `page_info=cursor` fills \
            it with `limit` and `next_cursor` alone, without counting the matching rows; omit it for the rows alone.""";

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
        StringSchema pageInfo = new StringSchema();
        pageInfo.setEnum(List.of("full", "cursor"));
        operation.addParametersItem(query("page_info", "`full` fills `page` with the limit, offset, total and next cursor; "
                + "`cursor` fills it with the limit and the next cursor alone, so a walk over a large listing is not counted; "
                + "omit it for the rows alone.",
                pageInfo));
        String note = CONTRACT_NOTE.formatted(QueryParser.MAX_OR_BRANCHES, QueryParser.MAX_PREDICATES, QueryParser.MAX_IN_VALUES, QueryParser.MAX_OFFSET);
        operation.setDescription(operation.getDescription() == null ? note.strip() : operation.getDescription() + "\n" + note);
        if (operation.getResponses() != null && !operation.getResponses().containsKey("400")) {
            operation.getResponses().addApiResponse("400", new ApiResponse().description(
                    "A filter, sort, cursor or paging parameter is unknown, not allowed for the field, or not valid for its type."));
        }
        return operation;
    }

    @Override
    public void customise(OpenAPI api) {
        if (api.getComponents() == null || api.getComponents().getSchemas() == null) {
            return;
        }
        boolean openApi30 = api.getOpenapi() != null && api.getOpenapi().startsWith("3.0");
        api.getComponents().getSchemas().forEach((name, schema) -> {
            if (PAGE_INFO.equals(name)) {
                absentable(schema, "offset", openApi30);
                absentable(schema, "total", openApi30);
                absentable(schema, "next_cursor", openApi30);
            } else if (name.startsWith(PAGE_WRAPPER_PREFIX)) {
                absentable(schema, "page", openApi30);
            }
        });
    }

    /**
     * Marks a property's value as one that may be absent, in the form the document's version allows: {@code null} among
     * the types in OpenAPI 3.1, and the {@code nullable} keyword in 3.0, where a reference is wrapped in {@code allOf} so
     * the two do not conflict.
     */
    private static void absentable(Schema<?> owner, String property, boolean openApi30) {
        if (owner.getProperties() == null || !(owner.getProperties().get(property) instanceof Schema<?> given)) {
            return;
        }
        if (given.get$ref() != null) {
            Schema<Object> reference = new Schema<>();
            reference.set$ref(given.get$ref());
            Schema<Object> wrapper = new Schema<>();
            wrapper.setDescription(given.getDescription());
            if (openApi30) {
                wrapper.setNullable(true);
                wrapper.setAllOf(List.of(reference));
            } else {
                wrapper.setOneOf(List.of(reference, nullType()));
            }
            owner.getProperties().put(property, wrapper);
            return;
        }
        if (openApi30) {
            given.setNullable(true);
            return;
        }
        Set<String> types = new LinkedHashSet<>();
        if (given.getTypes() != null) {
            types.addAll(given.getTypes());
        } else if (given.getType() != null) {
            types.add(given.getType());
        }
        if (!types.isEmpty()) {
            types.add(NULL_TYPE);
            given.setTypes(types);
        }
    }

    private static Schema<Object> nullType() {
        Schema<Object> nullType = new Schema<>();
        nullType.addType(NULL_TYPE);
        return nullType;
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

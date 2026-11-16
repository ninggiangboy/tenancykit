package dev.ngb.core.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Op;
import dev.ngb.core.spring.query.Listing;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

class ListingOpenApiCustomizerTest {

    /** What an app declares: a holder of the schema constant. */
    public static final class PaymentListing {
        public static final FilterSchema SCHEMA = FilterSchema.of("id", "-created_at",
                FieldSpec.of("id", FieldType.UUID).asSortable(),
                FieldSpec.enumOf("status", "OPEN", "PAID"),
                FieldSpec.of("amount", FieldType.LONG).asSortable(),
                FieldSpec.of("created_at", FieldType.INSTANT).asSortable(),
                FieldSpec.of("reference", FieldType.STRING).asNullable().with(Op.CONTAINS),
                FieldSpec.of("wallet.customer_id", FieldType.UUID));
    }

    @SuppressWarnings("unused")
    static class Controller {
        @Listing(PaymentListing.class)
        public void list(ListQuery query) {}

        public void plain() {}
    }

    private final ListingOpenApiCustomizer customizer = new ListingOpenApiCustomizer();

    private Operation customized(String method) throws Exception {
        Method m = Controller.class.getMethod(method, method.equals("list") ? new Class<?>[] {ListQuery.class} : new Class<?>[0]);
        Operation operation = new Operation().responses(new ApiResponses());
        return customizer.customize(operation, new HandlerMethod(new Controller(), m));
    }

    private static Parameter named(Operation operation, String name) {
        return operation.getParameters().stream().filter(p -> p.getName().equals(name)).findFirst().orElseThrow(() -> new AssertionError("no parameter " + name));
    }

    @Test
    void everyAllowedFieldAndOperatorBecomesAParameter() throws Exception {
        Operation operation = customized("list");
        List<String> names = operation.getParameters().stream().map(Parameter::getName).toList();
        assertThat(names).contains("filter[status][eq]", "filter[status][in]", "filter[amount][gte]", "filter[created_at][lt]",
                "filter[reference][contains]", "filter[reference][is_null]", "filter[wallet.customer_id][nin]",
                "sort", "limit", "offset", "cursor", "page_info");
        // What the schema does not allow is not advertised.
        assertThat(names).doesNotContain("filter[status][gt]", "filter[amount][contains]", "filter[id][is_null]", "filter[wallet.customer_id][gt]");
        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    void valuesAreTypedAndEnumsListTheirValues() throws Exception {
        Operation operation = customized("list");
        List<?> statuses = named(operation, "filter[status][eq]").getSchema().getEnum();
        assertThat(statuses.stream().map(Object::toString).toList()).containsExactly("OPEN", "PAID");
        assertThat(named(operation, "filter[amount][gte]").getSchema().getType()).isEqualTo("integer");
        assertThat(named(operation, "filter[created_at][lt]").getSchema().getFormat()).isEqualTo("date-time");
        assertThat(named(operation, "filter[wallet.customer_id][eq]").getSchema().getFormat()).isEqualTo("uuid");
        assertThat(named(operation, "filter[reference][is_null]").getSchema().getType()).isEqualTo("boolean");
        assertThat(named(operation, "filter[status][in]").getDescription()).contains("100");
    }

    @Test
    void sortAndPagingDocumentTheirLimits() throws Exception {
        Operation operation = customized("list");
        assertThat(named(operation, "sort").getDescription()).contains("amount, created_at, id").contains("Default: `-created_at,id`");
        assertThat(named(operation, "limit").getSchema().getMaximum()).isEqualByComparingTo("200");
        assertThat(named(operation, "offset").getSchema().getMaximum()).isEqualByComparingTo("10000");
        assertThat(named(operation, "page_info").getSchema().getDefault()).isEqualTo(false);
    }

    @Test
    void theOperationExplainsOrAndDocumentsTheBadRequest() throws Exception {
        Operation operation = customized("list");
        assertThat(operation.getDescription()).contains("filter[or][i][field][op]").contains("page_info=true");
        assertThat(operation.getResponses().get("400").getDescription()).contains("not allowed");
    }

    @Test
    void anOperationWithoutTheAnnotationIsLeftAlone() throws Exception {
        Operation operation = customized("plain");
        assertThat(operation.getParameters()).isNull();
        assertThat(operation.getResponses()).isEmpty();
    }

    @Test
    void theOutputIsDeterministic() throws Exception {
        assertThat(customized("list").getParameters().stream().map(Parameter::getName).toList())
                .isEqualTo(customized("list").getParameters().stream().map(Parameter::getName).toList());
    }
}

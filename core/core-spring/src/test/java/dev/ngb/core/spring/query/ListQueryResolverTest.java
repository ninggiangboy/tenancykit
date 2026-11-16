package dev.ngb.core.spring.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.query.ListQuery;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

class ListQueryResolverTest {

    /** What an app declares: a holder of the schema constant. */
    public static final class PaymentListing {
        public static final dev.ngb.core.query.FilterSchema SCHEMA = ListBindingTest.SCHEMA;
    }

    @SuppressWarnings("unused")
    static class Controller {
        @Listing(PaymentListing.class)
        void list(ListQuery query) {}

        @Listing(value = PaymentListing.class, passthrough = "metric")
        void withMetric(ListQuery query) {}

        void notAListing(ListQuery query) {}

        void other(String value) {}
    }

    private final ListQueryResolver resolver = new ListQueryResolver();

    private static MethodParameter parameter(String method, Class<?> type) throws NoSuchMethodException {
        Method m = Controller.class.getDeclaredMethod(method, type);
        return new MethodParameter(m, 0);
    }

    private Object resolve(String method, MockHttpServletRequest request) throws Exception {
        return resolver.resolveArgument(parameter(method, ListQuery.class), null, new ServletWebRequest(request), null);
    }

    @Test
    void itSupportsOnlyTheListQueryType() throws Exception {
        assertThat(resolver.supportsParameter(parameter("list", ListQuery.class))).isTrue();
        assertThat(resolver.supportsParameter(parameter("other", String.class))).isFalse();
    }

    @Test
    void itParsesTheQueryStringAgainstTheDeclaredSchema() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("filter[status][in]", "OPEN,PAID");
        request.setParameter("sort", "-amount");
        request.setParameter("limit", "10");
        request.setParameter("page_info", "true");
        ListQuery query = (ListQuery) resolve("list", request);
        assertThat(query.all()).hasSize(1);
        assertThat(query.limit()).isEqualTo(10);
        assertThat(query.pageInfo()).isTrue();
        assertThat(query.sort()).extracting(key -> key.wire()).containsExactly("-amount", "id");
    }

    @Test
    void aRefusedRequestSurfacesAsABadRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("filter[stripe_payment_intent_id][eq]", "pi_1");
        assertThatThrownBy(() -> resolve("list", request)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void anUndeclaredParameterIsRefusedUnlessPassedThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("metric", "api_calls");
        assertThatThrownBy(() -> resolve("list", request)).isInstanceOf(BadRequestException.class);
        assertThat(resolve("withMetric", request)).isInstanceOf(ListQuery.class);
    }

    @Test
    void aMethodWithoutTheAnnotationFailsFast() {
        assertThatThrownBy(() -> resolve("notAListing", new MockHttpServletRequest()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("@Listing");
    }

    @Test
    void aHolderWithoutTheConstantFailsWithItsName() {
        assertThatThrownBy(() -> ListingSchemas.of(String.class))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("java.lang.String");
    }
}

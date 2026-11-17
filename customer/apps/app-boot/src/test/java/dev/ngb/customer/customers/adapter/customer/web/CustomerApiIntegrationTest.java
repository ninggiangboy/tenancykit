package dev.ngb.customer.customers.adapter.customer.web;

import static dev.ngb.customer_jooq.Tables.WEBHOOK_DELIVERIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.core.messaging.outbox.OutboxRelay;
import dev.ngb.customer.webhook.application.endpoint.command.CreateEndpointUseCase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The customer endpoint: create, replace, patch, read and list, over the real filter chain, the real database and the
 * real outbox.
 *
 * <p>The two cases that decide the design are here: a repeated identical body must be a no-op that announces nothing,
 * and fifty callers creating the same reference at once must end with one row and one announcement.
 */
class CustomerApiIntegrationTest extends CustomerApiTestCase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    OutboxRelay relay;

    @Autowired
    CreateEndpointUseCase createEndpoint;

    @Test
    void putCreatesTheCustomerAndAnswersItsVersionAsTheETag() throws Exception {
        Session session = tenant();

        putCustomer(session, "cust-1", """
                {"displayName":"Ada Lovelace","phone":"+44 20 7946 0958","email":"Ada@Example.com"}
                """)
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.externalRef").value("cust-1"))
                .andExpect(jsonPath("$.displayName").value("Ada Lovelace"))
                .andExpect(jsonPath("$.phone").value("+44 20 7946 0958"))
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        getCustomer(session, "cust-1")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.displayName").value("Ada Lovelace"));

        assertThat(outboxRows(session, "customer.created")).isEqualTo(1);
    }

    @Test
    void aRetriedPutWithTheSameBodyChangesNothingAndAnnouncesTheCustomerOnce() throws Exception {
        Session session = tenant();
        String body = "{\"displayName\":\"Grace Hopper\",\"email\":\"grace@example.com\"}";

        putCustomer(session, "cust-repeat", body)
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""));
        putCustomer(session, "cust-repeat", body)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.displayName").value("Grace Hopper"));

        assertThat(outboxRows(session, "customer.created"))
                .as("an identical body is not a change and must not be announced")
                .isEqualTo(1);
        assertThat(customerRows(session)).isEqualTo(1);
    }

    @Test
    void aChangedPutReplacesTheAttributesAndBumpsTheVersion() throws Exception {
        Session session = tenant();

        putCustomer(session, "cust-change", "{\"displayName\":\"Ada Lovelace\"}")
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""));
        putCustomer(session, "cust-change", "{\"displayName\":\"Ada King\"}")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.displayName").value("Ada King"));
        getCustomer(session, "cust-change")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""));

        assertThat(outboxRows(session, "customer.created"))
                .as("only the creation is announced, never a replacement")
                .isEqualTo(1);
    }

    @Test
    void aPatchChangesOnlyThePropertiesItCarries() throws Exception {
        Session session = tenant();
        putCustomer(session, "cust-patch", """
                {"displayName":"Ada Lovelace","phone":"+44 20 7946 0958","email":"ada@example.com"}
                """)
                .andExpect(status().isCreated());

        patchCustomer(session, "cust-patch", "\"1\"", "{\"displayName\":\"Ada King\"}")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.displayName").value("Ada King"))
                .andExpect(jsonPath("$.phone").value("+44 20 7946 0958"))
                .andExpect(jsonPath("$.email").value("ada@example.com"));
    }

    @Test
    void aPatchWithoutIfMatchIsRefusedWith428() throws Exception {
        Session session = tenant();
        putCustomer(session, "cust-conditional", "{\"displayName\":\"Ada\"}").andExpect(status().isCreated());

        patchCustomer(session, "cust-conditional", null, "{\"displayName\":\"Ada King\"}")
                .andExpect(status().isPreconditionRequired());
    }

    @Test
    void aStaleIfMatchIsRefusedWith412() throws Exception {
        Session session = tenant();
        putCustomer(session, "cust-stale", "{\"displayName\":\"Ada\"}").andExpect(status().isCreated());
        patchCustomer(session, "cust-stale", "\"1\"", "{\"displayName\":\"Ada King\"}")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""));

        patchCustomer(session, "cust-stale", "\"1\"", "{\"displayName\":\"Someone Else\"}")
                .andExpect(status().isPreconditionFailed())
                .andExpect(header().string("ETag", "\"2\""));

        getCustomer(session, "cust-stale").andExpect(jsonPath("$.displayName").value("Ada King"));
    }

    @Test
    void aBodyNamingAnotherReferenceIsRefusedWith409() throws Exception {
        Session session = tenant();

        putCustomer(session, "cust-a", "{\"externalRef\":\"cust-b\",\"displayName\":\"Ada\"}")
                .andExpect(status().isConflict());
        getCustomer(session, "cust-a").andExpect(status().isNotFound());
        assertThat(customerRows(session)).isZero();
    }

    @Test
    void aCustomerThatIsNotValidIsRefusedWith422() throws Exception {
        Session session = tenant();

        putCustomer(session, "cust-blank", "{\"displayName\":\"   \"}")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        putCustomer(session, "cust-long-phone", "{\"phone\":\"" + "7".repeat(33) + "\"}")
                .andExpect(status().is(422));
        putCustomer(session, "cust-email", "{\"email\":\"not-an-address\"}")
                .andExpect(status().is(422));

        assertThat(customerRows(session)).isZero();
        assertThat(outboxRows(session, "customer.created")).isZero();
    }

    @Test
    void anUnknownCustomerIs404() throws Exception {
        Session session = tenant();

        getCustomer(session, "cust-nowhere").andExpect(status().isNotFound());
    }

    @Test
    void concurrentFirstTimePutsOfOneReferenceCreateOneRowAndAnnounceItOnce() throws Exception {
        Session session = tenant();
        String body = "{\"displayName\":\"Ada Lovelace\",\"email\":\"ada@example.com\"}";
        int callers = 50;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> answers = new ArrayList<>(callers);
            for (int i = 0; i < callers; i++) {
                Callable<Integer> caller = () -> {
                    start.await();
                    return putCustomer(session, "cust-race", body)
                            .andReturn()
                            .getResponse()
                            .getStatus();
                };
                answers.add(pool.submit(caller));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>(callers);
            for (Future<Integer> answer : answers) {
                statuses.add(answer.get(60, TimeUnit.SECONDS));
            }
            assertThat(statuses).allMatch(code -> code == 201 || code == 200);
            assertThat(statuses).filteredOn(code -> code == 201)
                    .as("exactly one caller created the customer")
                    .hasSize(1);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertThat(customerRows(session)).as("the unique reference admits one row").isEqualTo(1);
        assertThat(outboxRows(session, "customer.created"))
                .as("the customer is announced once, however many callers raced")
                .isEqualTo(1);
        getCustomer(session, "cust-race").andExpect(header().string("ETag", "\"1\""));
    }

    @Test
    void theCreatedEventCarriesExactlyTheDocumentedPayload() throws Exception {
        Session session = tenant();
        putCustomer(session, "cust-event", "{\"displayName\":\"Ada Lovelace\"}").andExpect(status().isCreated());

        JsonNode schema = JSON.readTree(getClass().getResourceAsStream("/events/customer.created.schema.json"));
        assertThat(schema).as("the build packages the committed event schema").isNotNull();
        Set<String> documented = names(schema.get("properties"));
        assertThat(documented).containsExactlyInAnyOrder("customerId", "externalRef", "createdAt");

        JsonNode payload = JSON.readTree(outboxPayload(session, "customer.created"));
        assertThat(names(payload)).as("the payload carries the documented fields and nothing else").isEqualTo(documented);
        assertThat(payload.get("externalRef").asString()).isEqualTo("cust-event");
        assertThat(Instant.parse(payload.get("createdAt").asString())).isNotNull();
        assertThat(payload.get("customerId").asString()).matches("[0-9a-f-]{36}");
    }

    @Test
    void aCreatedCustomerIsQueuedForEverySubscribedEndpoint() throws Exception {
        Session session = tenant();
        inTenant(session.tenant(), () -> createEndpoint.execute(new CreateEndpointUseCase.Command(
                "https://hooks.example.com/customer", null, List.of("customer.created"))));

        putCustomer(session, "cust-announced", "{\"displayName\":\"Ada Lovelace\"}").andExpect(status().isCreated());
        OutboxRelay.Result relayed = inTenant(session.tenant(), () -> relay.relayDue());
        assertThat(relayed.delivered()).isEqualTo(1);

        long queued = inTenant(session.tenant(), () -> unitOfWork.transactional(() -> (long) dsl.fetchCount(
                WEBHOOK_DELIVERIES, WEBHOOK_DELIVERIES.EVENT_TYPE.eq("customer.created"))));
        assertThat(queued).isEqualTo(1);
    }

    @Test
    void theEmptyBodyCreatesACustomerWithoutAttributes() throws Exception {
        Session session = tenant();

        putCustomer(session, "cust-anonymous", "{}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").doesNotExist())
                .andExpect(jsonPath("$.externalRef").value("cust-anonymous"));
    }

    @Test
    void theListPagesByCursorNewestFirst() throws Exception {
        Session session = tenant();
        putCustomer(session, "cust-page-1", "{\"displayName\":\"First\"}").andExpect(status().isCreated());
        putCustomer(session, "cust-page-2", "{\"displayName\":\"Second\"}").andExpect(status().isCreated());
        putCustomer(session, "cust-page-3", "{\"displayName\":\"Third\"}").andExpect(status().isCreated());

        String firstPage = listCustomers(session, "limit", "2", "page_info", "cursor")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].externalRef").value("cust-page-3"))
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String cursor = JSON.readTree(firstPage).get("page").get("next_cursor").asString();
        assertThat(cursor).as("the first page is not the last one").isNotBlank();

        listCustomers(session, "limit", "2", "page_info", "cursor", "cursor", cursor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].externalRef").value("cust-page-1"))
                .andExpect(jsonPath("$.page.next_cursor").doesNotExist());
    }

    private static Set<String> names(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }
}

package dev.ngb.workflow.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The API tests' common ground: the real application behind MockMvc, a Tenant with a Tenant System credential, and the
 * request builders the tests send.
 *
 * <p>Every test uses a fresh random tenant, so no test has to clean up after another and a row stored by one is
 * invisible to the next. A credential holds only the codes the test names, which is what makes a missing permission a
 * {@code 403} rather than an assumption.
 */
@AutoConfigureMockMvc
@Import(TokensConfig.class)
public abstract class WorkflowApiTest extends WorkflowIntegrationTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ReplicaFixtures replica;

    @Autowired
    protected DSLContext dsl;

    @Autowired
    protected UnitOfWork unitOfWork;

    /** A tenant, its hostname and a Tenant System token holding exactly these permission codes. */
    public record Session(TenantId tenant, String host, String token) {}

    /** A fresh tenant and a Tenant System credential that carries the codes the caller names. */
    protected Session tenant(String... permissions) {
        ReplicaFixtures.TenantSeed seed = replica.tenant();
        UUID system = replica.tenantSystem(seed.id().value(), "Tenant Workflow").value();
        String token = TestTokenFactory.tenantSystemToken(system, seed.id().value(), permissions);
        return new Session(seed.id(), seed.host(), token);
    }

    protected ResultActions getAs(Session session, String path, Object... variables) throws Exception {
        return send(get(path, variables), session, null, null);
    }

    protected ResultActions postAs(Session session, String path, String body, Object... variables) throws Exception {
        return send(post(path, variables), session, body, null);
    }

    protected ResultActions putAs(
            Session session, String path, String body, String ifMatch, Object... variables) throws Exception {
        return send(put(path, variables), session, body, ifMatch);
    }

    /** Sends a request to the tenant's host, presenting the session's token and, when present, its {@code If-Match}. */
    protected ResultActions send(
            MockHttpServletRequestBuilder request, Session session, String body, String ifMatch) throws Exception {
        request.header("Host", session.host()).header("Authorization", "Bearer " + session.token());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return mvc.perform(request);
    }

    /** Sends a request with an explicit token, for a case that asserts a refusal. */
    protected ResultActions send(
            MockHttpServletRequestBuilder request, Session session, String body, String token, String ifMatch)
            throws Exception {
        request.header("Host", session.host()).header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return mvc.perform(request);
    }

    protected int statusCode(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getStatus();
    }

    /** How many rows of one table the tenant owns, counted as the application role sees them, through its own scope. */
    protected long rowsOf(Session session, org.jooq.Table<?> table) {
        return inTenant(session.tenant(), () -> unitOfWork.transactional(() -> (long) dsl.fetchCount(table)));
    }
}

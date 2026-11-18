package dev.ngb.workflow.connections.adapter.credential.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_PLATFORM_CREDENTIAL;

import dev.ngb.workflow.connections.application.credential.query.CredentialReadRepository;
import dev.ngb.workflow.connections.application.credential.query.CredentialView;
import java.util.Optional;
import org.jooq.DSLContext;

/** The credential read side: client id and state only. */
public class JooqCredentialReadRepository implements CredentialReadRepository {

    private final DSLContext dsl;

    public JooqCredentialReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<CredentialView> find() {
        return dsl.select(
                        WORKFLOW_PLATFORM_CREDENTIAL.CLIENT_ID,
                        WORKFLOW_PLATFORM_CREDENTIAL.STATUS,
                        WORKFLOW_PLATFORM_CREDENTIAL.SECRET_KEY_ID,
                        WORKFLOW_PLATFORM_CREDENTIAL.UPDATED_AT)
                .from(WORKFLOW_PLATFORM_CREDENTIAL)
                .fetchOptional(row ->
                        new CredentialView(row.value1(), row.value2(), row.value3(), row.value4()));
    }
}

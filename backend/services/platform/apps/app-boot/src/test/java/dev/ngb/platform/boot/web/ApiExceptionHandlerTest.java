package dev.ngb.platform.boot.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.platform.access.application.permission.exception.UnknownPermissionException;
import dev.ngb.platform.tenancy.application.entitlement.exception.UnknownAppException;
import dev.ngb.platform.tenancy.application.tenant.exception.HostnameAlreadyMappedException;
import dev.ngb.platform.tenancy.application.tenant.exception.TenantCodeAlreadyUsedException;
import dev.ngb.platform.tenancy.application.tenant.exception.TenantNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void mapsTheAccessCatalogFailuresToTheirStatuses() {
        assertStatus(handler.notFound(new NotFoundException("no user 1")), 404);
        assertStatus(handler.unprocessable(new UnknownPermissionException("platform:entitlement:manage", "is not in the catalog")), 422);
    }

    @Test
    void mapsTheTenancyFailuresToTheirStatuses() {
        assertStatus(handler.tenantNotFound(new TenantNotFoundException("t1")), 404);
        assertStatus(handler.tenantConflict(new TenantCodeAlreadyUsedException("acme")), 409);
        assertStatus(handler.tenantConflict(new HostnameAlreadyMappedException("acme.example.com")), 409);
        assertStatus(handler.unprocessable(new UnknownAppException("billing")), 422);
    }

    private static void assertStatus(ProblemDetail problem, int status) {
        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getDetail()).isNotBlank();
    }
}

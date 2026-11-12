package dev.ngb.billing.session.adapter.portal.web;

import dev.ngb.core.security.CurrentActor;
import dev.ngb.billing.sharedkernel.security.BillingPermissions;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.audit.Audit;
import dev.ngb.billing.session.application.portal.command.ManageReturnUrlsUseCase;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The Tenant's allowlist of URLs a Billing Session may return the customer to. */
@Profile("api")
@ConditionalOnProperty(name = "billing.portal.enabled", havingValue = "true", matchIfMissing = true)
@RestController
@RequestMapping("/api/v1/portal/return-urls")
public class PortalSettingsController {

    public record ReplaceRequest(List<String> urls) {}

    private final ManageReturnUrlsUseCase manage;
    private final CurrentActor currentActor;

    public PortalSettingsController(ManageReturnUrlsUseCase manage, CurrentActor currentActor) {
        this.manage = manage;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresPermission(BillingPermissions.PORTAL_SETTINGS_MANAGE)
    public List<String> list() {
        return manage.list().stream().sorted().toList();
    }

    @PutMapping
    @RequiresPermission(BillingPermissions.PORTAL_SETTINGS_MANAGE)
    public List<String> replace(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId, @RequestBody ReplaceRequest request) {
        Set<String> urls = request.urls() == null ? Set.of() : Set.copyOf(request.urls());
        return manage.replace(urls, Audit.of(currentActor.get().auditName(), requestId)).stream().sorted().toList();
    }
}

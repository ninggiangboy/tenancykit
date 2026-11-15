package dev.ngb.notification.dispatch.adapter.notification.web;

import dev.ngb.core.security.RequiresPermission;
import dev.ngb.notification.dispatch.application.notification.command.ResolveTemplateUseCase;
import dev.ngb.notification.dispatch.application.notification.command.SaveTemplateOverrideUseCase;
import dev.ngb.notification.dispatch.application.notification.port.TemplateVariant;
import dev.ngb.notification.dispatch.domain.notification.NotificationType;
import dev.ngb.notification.shared.NotificationPermissions;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The suite-wide template overrides, behind the two template codes.
 *
 * <p>These four endpoints are the only ones reachable at platform scope: a template is the suite's content, one row
 * serves every tenant, and a tenant administrator must not be able to rewrite the wording every tenant receives.
 */
@RestController
@RequestMapping("/api/v1/templates")
class TemplateController {

    private final ResolveTemplateUseCase resolve;
    private final SaveTemplateOverrideUseCase save;

    TemplateController(ResolveTemplateUseCase resolve, SaveTemplateOverrideUseCase save) {
        this.resolve = resolve;
        this.save = save;
    }

    @GetMapping
    @RequiresPermission(NotificationPermissions.TEMPLATE_READ)
    List<TemplateSummary> list() {
        return Arrays.stream(NotificationType.values())
                .map(type -> new TemplateSummary(type.name(), type.templateKey()))
                .toList();
    }

    @GetMapping("/{key}")
    @RequiresPermission(NotificationPermissions.TEMPLATE_READ)
    ResolveTemplateUseCase.Resolved resolved(
            @PathVariable String key, @RequestParam(required = false) String locale) {
        return resolve.resolve(key, locale);
    }

    @PutMapping("/{key}/{locale}")
    @RequiresPermission(NotificationPermissions.TEMPLATE_MANAGE)
    ResponseEntity<Void> save(
            @PathVariable String key, @PathVariable String locale, @RequestBody TemplateRequest request) {
        save.save(key, locale, new TemplateVariant(
                request.subject(), request.html(), request.text(), request.title(), request.body(), 1));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{key}/{locale}")
    @RequiresPermission(NotificationPermissions.TEMPLATE_MANAGE)
    ResponseEntity<Void> delete(@PathVariable String key, @PathVariable String locale) {
        save.delete(key, locale);
        return ResponseEntity.noContent().build();
    }

    record TemplateSummary(String type, String key) {}

    record TemplateRequest(String subject, String html, String text, String title, String body) {}
}

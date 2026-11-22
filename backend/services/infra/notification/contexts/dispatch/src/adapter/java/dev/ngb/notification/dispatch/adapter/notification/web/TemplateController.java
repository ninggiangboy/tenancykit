package dev.ngb.notification.dispatch.adapter.notification.web;

import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.notification.dispatch.application.notification.command.ResolveTemplateUseCase;
import dev.ngb.notification.dispatch.application.notification.command.SaveTemplateOverrideUseCase;
import dev.ngb.notification.dispatch.application.notification.port.TemplateVariant;
import dev.ngb.notification.dispatch.domain.notification.NotificationType;
import dev.ngb.notification.shared.NotificationPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(
        name = "Notification templates",
        description =
                "The suite-wide template overrides that shape every notification's wording. Reachable at platform "
                + "scope only, because one row serves every tenant.")
@RestController
@RequestMapping("/api/v1/templates")
@Callers(Caller.PLATFORM_USER)
class TemplateController {

    private final ResolveTemplateUseCase resolve;
    private final SaveTemplateOverrideUseCase save;

    TemplateController(ResolveTemplateUseCase resolve, SaveTemplateOverrideUseCase save) {
        this.resolve = resolve;
        this.save = save;
    }

    @Operation(
            summary = "List the notification templates",
            description =
                    "Returns one entry per notification type with the template key that renders it. Reachable at platform scope only, because a template is suite content shared by every tenant.")
    @GetMapping
    @RequiresPermission(NotificationPermissions.TEMPLATE_READ)
    List<TemplateSummary> list() {
        return Arrays.stream(NotificationType.values())
                .map(type -> new TemplateSummary(type.name(), type.templateKey()))
                .toList();
    }

    @Operation(
            summary = "Get the resolved template for a key",
            description =
                    "Returns the variant the suite renders for template `key` in `locale`: each part the suite-wide override defines, and the packaged default for every part it leaves out. `overridden` says whether an override exists. A blank `locale` means the fallback locale. A key no notification type owns, or a locale the suite does not support, answers `404`.")
    @GetMapping("/{key}")
    @RequiresPermission(NotificationPermissions.TEMPLATE_READ)
    ResolveTemplateUseCase.Resolved resolved(
            @PathVariable String key, @RequestParam(required = false) String locale) {
        return resolve.resolve(key, locale);
    }

    @Operation(
            summary = "Save a template override",
            description =
                    "Creates or replaces the suite-wide override of template `key` for `locale` with the subject, HTML, text, in-app title and in-app body given; a part left out keeps the packaged default. The override applies to every tenant. A part the template policy refuses, or an unsupported locale, answers `400`, and a key no notification type owns answers `404`. Answers `204`.")
    @PutMapping("/{key}/{locale}")
    @RequiresPermission(NotificationPermissions.TEMPLATE_MANAGE)
    ResponseEntity<Void> save(
            @PathVariable String key, @PathVariable String locale, @RequestBody TemplateRequest request) {
        save.save(key, locale, new TemplateVariant(
                request.subject(), request.html(), request.text(), request.title(), request.body(), 1));
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Delete a template override",
            description =
                    "Removes the suite-wide override of template `key` for `locale`, so the packaged default is used again. Answers `204`. When there is no such override, or the key is unknown, it answers `404`; an unsupported locale answers `400`.")
    @DeleteMapping("/{key}/{locale}")
    @RequiresPermission(NotificationPermissions.TEMPLATE_MANAGE)
    ResponseEntity<Void> delete(@PathVariable String key, @PathVariable String locale) {
        save.delete(key, locale);
        return ResponseEntity.noContent().build();
    }

    record TemplateSummary(String type, String key) {}

    record TemplateRequest(String subject, String html, String text, String title, String body) {}
}

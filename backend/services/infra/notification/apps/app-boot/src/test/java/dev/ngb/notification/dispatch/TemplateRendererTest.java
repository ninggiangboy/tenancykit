package dev.ngb.notification.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.notification.dispatch.adapter.notification.render.ClasspathDefaultTemplates;
import dev.ngb.notification.dispatch.adapter.notification.render.JmustacheTemplateRenderer;
import dev.ngb.notification.dispatch.application.notification.exception.ForbiddenTemplateConstructException;
import dev.ngb.notification.dispatch.application.notification.exception.MissingTemplateVariableException;
import dev.ngb.notification.dispatch.application.notification.exception.TemplateTooLargeException;
import dev.ngb.notification.dispatch.application.notification.port.DefaultTemplates;
import dev.ngb.notification.dispatch.application.notification.port.TemplateRenderer;
import dev.ngb.notification.dispatch.application.notification.port.TemplateSource;
import dev.ngb.notification.dispatch.application.notification.port.TemplateVariant;
import dev.ngb.notification.dispatch.domain.template.RenderedMessage;
import dev.ngb.notification.shared.TemplateKey;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TemplateRendererTest {

    private static final int MAX_PART_BYTES = 65_536;

    private final DefaultTemplates defaults = new ClasspathDefaultTemplates(MAX_PART_BYTES);

    private TemplateRenderer renderer(TemplateSource overrides) {
        return new JmustacheTemplateRenderer(overrides, defaults, MAX_PART_BYTES);
    }

    @Test
    void variablesAreEscapedInHtmlAndUntouchedInText() {
        RenderedMessage message = renderer((key, locale) -> Optional.empty())
                .render(new TemplateKey("access-granted"), "en", Map.of(
                        "tenantName", "<script>alert(1)</script>",
                        "displayName", "A",
                        "link", "/notification/notifications"));

        assertThat(message.html()).contains("&lt;script&gt;");
        assertThat(message.text()).contains("<script>");
    }

    @Test
    void aMissingVariableIsAHardFailureRatherThanAnEmptyString() {
        assertThatThrownBy(() -> renderer((key, locale) -> Optional.empty())
                        .render(new TemplateKey("access-granted"), "en", Map.of()))
                .isInstanceOf(MissingTemplateVariableException.class);
    }

    @Test
    void aSectionInAnOverrideIsRefused() {
        TemplateSource overrides = (key, locale) -> Optional.of(new TemplateVariant(
                "Subject", "<p>{{#tenantName}}x{{/tenantName}}</p>", null, null, null, 2));
        assertThatThrownBy(() -> renderer(overrides)
                        .render(new TemplateKey("access-granted"), "en", Map.of(
                                "tenantName", "Acme", "displayName", "A", "link", "/x")))
                .isInstanceOf(ForbiddenTemplateConstructException.class);
    }

    @Test
    void aSubjectWithALineBreakIsRefused() {
        TemplateSource overrides = (key, locale) -> Optional.of(new TemplateVariant(
                "Header\nInjected: yes", null, null, null, null, 2));
        assertThatThrownBy(() -> renderer(overrides)
                        .render(new TemplateKey("access-granted"), "en", Map.of(
                                "tenantName", "Acme", "displayName", "A", "link", "/x")))
                .isInstanceOf(dev.ngb.core.error.BadRequestException.class);
    }

    @Test
    void anOversizePartIsRefusedRatherThanTruncated() {
        TemplateSource overrides = (key, locale) -> Optional.of(new TemplateVariant(
                "Subject", "<p>" + "x".repeat(70_000) + "</p>", null, null, null, 2));
        assertThatThrownBy(() -> renderer(overrides)
                        .render(new TemplateKey("access-granted"), "en", Map.of(
                                "tenantName", "Acme", "displayName", "A", "link", "/x")))
                .isInstanceOf(TemplateTooLargeException.class);
    }

    @Test
    void anOverrideReplacesOnlyThePartsItDefines() {
        TemplateSource overrides = (key, locale) -> Optional.of(new TemplateVariant(
                "Custom subject", null, null, null, null, 7));
        RenderedMessage message = renderer(overrides)
                .render(new TemplateKey("access-granted"), "en", Map.of(
                        "tenantName", "Acme", "displayName", "A", "link", "/x"));

        assertThat(message.subject()).isEqualTo("Custom subject");
        assertThat(message.html()).as("the untouched part still comes from the classpath").contains("Acme");
        assertThat(message.templateVersion()).isEqualTo(7);
    }

    @Test
    void vietnameseDefaultsRender() {
        RenderedMessage message = renderer((key, locale) -> Optional.empty())
                .render(new TemplateKey("access-granted"), "vi", Map.of(
                        "tenantName", "Acme", "displayName", "A", "link", "/x"));
        assertThat(message.subject()).contains("quyền truy cập");
        assertThat(message.locale()).isEqualTo("vi");
    }
}

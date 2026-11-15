package dev.ngb.email.content.adapter.render.mustache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.email.content.api.render.MissingTemplateVariableException;
import dev.ngb.email.content.api.render.TemplateLimitExceededException;
import dev.ngb.email.content.application.render.port.RenderedParts;
import dev.ngb.email.content.domain.template.RenderLimits;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

/**
 * The sandbox is proved adversarially: every hostile shape the plan names must fail with its own typed exception and
 * finish inside the wall-clock budget, and the ordinary Mustache features a Tenant needs must work.
 */
class TemplateRendererTest {

    private static final Duration BOUND = Duration.ofSeconds(3);

    private final JmustacheTemplateRenderer renderer = new JmustacheTemplateRenderer(RenderLimits.DEFAULT);

    @Test
    void sectionsLoopsAndNestedLookupsRender() {
        Map<String, Object> variables = Map.of(
                "name", "Acme",
                "items", List.of(Map.of("label", "One"), Map.of("label", "Two")),
                "receiver", Map.of("first_name", "An"));

        RenderedParts rendered = renderer.render(
                "Hi {{name}}",
                "<ul>{{#items}}<li>{{label}}</li>{{/items}}</ul><p>{{receiver.first_name}}</p>",
                null,
                variables);

        assertThat(rendered.subject()).isEqualTo("Hi Acme");
        assertThat(rendered.html())
                .isEqualTo("<ul><li>One</li><li>Two</li></ul><p>An</p>");
    }

    @Test
    void anInvertedSectionRendersOverAnEmptyList() {
        RenderedParts rendered = renderer.render("subject", "{{^items}}none{{/items}}", null, Map.of("items", List.of()));
        assertThat(rendered.html()).isEqualTo("none");
    }

    @Test
    void aTextOnlyBodyRendersWithoutAnHtmlPart() {
        // The command contract allows "html or text"; a text-only send must render, not fail on a null HTML part.
        RenderedParts rendered = renderer.render("Hi {{name}}", null, "Hello {{name}}", Map.of("name", "An"));

        assertThat(rendered.subject()).isEqualTo("Hi An");
        assertThat(rendered.html()).isNull();
        assertThat(rendered.text()).isEqualTo("Hello An");
    }

    @Test
    void anUnknownVariableFailsAndNamesIt() {
        assertThatThrownBy(() -> renderer.render("Hi {{missing}}", "<p>x</p>", null, Map.of()))
                .isInstanceOf(MissingTemplateVariableException.class)
                .satisfies(e -> assertThat(((MissingTemplateVariableException) e).variable()).isEqualTo("missing"));
    }

    @Test
    void anUnknownAttributeAtDepthFailsAndNamesIt() {
        assertThatThrownBy(() -> renderer.render(
                        "subject", "<p>{{receiver.first_name}}</p>", null, Map.of("receiver", Map.of("email", "a@b.c"))))
                .isInstanceOf(MissingTemplateVariableException.class)
                .satisfies(e -> assertThat(((MissingTemplateVariableException) e).variable()).isEqualTo("receiver.first_name"));
    }

    @Test
    void aMissingVariableInsideALoopFails() {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Map.of("label", "One"));
        items.add(Map.of("other", "Two"));

        assertThatThrownBy(() -> renderer.render(
                        "subject", "{{#items}}{{label}}{{/items}}", null, Map.of("items", items)))
                .isInstanceOf(MissingTemplateVariableException.class);
    }

    @Test
    void receiverDataIsEscapedInHtml() {
        RenderedParts rendered =
                renderer.render("subject", "<p>{{value}}</p>", null, Map.of("value", "<script>alert(1)</script>"));
        assertThat(rendered.html()).isEqualTo("<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>");
    }

    @Test
    void unescapedOutputIsRefusedInHtmlButAllowedInText() {
        assertThatThrownBy(() -> renderer.render("subject", "<p>{{{value}}}</p>", null, Map.of("value", "<b>")))
                .isInstanceOf(BadRequestException.class);

        RenderedParts rendered = renderer.render("subject", "<p>x</p>", "{{{value}}}", Map.of("value", "<b>"));
        assertThat(rendered.text()).isEqualTo("<b>");
    }

    @Test
    void aPartialIsRefused() {
        assertThatThrownBy(() -> renderer.render("subject", "{{> other}}", null, Map.of()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aDelimiterChangeIsRefused() {
        assertThatThrownBy(() -> renderer.render("subject", "{{=<% %>=}}", null, Map.of()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void tooMuchNestingIsRefusedAtLoad() {
        String html = "{{#a}}{{#b}}{{#c}}{{#d}}{{#e}}x{{/e}}{{/d}}{{/c}}{{/b}}{{/a}}";
        assertThatThrownBy(() -> renderer.render("subject", html, null, Map.of()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aLoopOverTheIterationBudgetFails() {
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> subs = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            subs.add(Map.of("label", "x"));
        }
        for (int i = 0; i < 120; i++) {
            items.add(Map.of("subs", subs));
        }
        Map<String, Object> variables = Map.of("items", items);

        assertTimeoutPreemptively(BOUND, () -> assertThatThrownBy(() -> renderer.render(
                        "subject", "{{#items}}{{#subs}}{{label}}{{/subs}}{{/items}}", null, variables))
                .isInstanceOf(TemplateLimitExceededException.class));
    }

    @Test
    void anOversizeOutputFails() {
        List<Map<String, Object>> items = new ArrayList<>();
        String label = "a".repeat(200);
        for (int i = 0; i < 2_000; i++) {
            items.add(Map.of("label", label));
        }
        Map<String, Object> variables = Map.of("items", items);

        assertTimeoutPreemptively(BOUND, () -> assertThatThrownBy(() -> renderer.render(
                        "subject", "{{#items}}{{label}}{{/items}}", null, variables))
                .isInstanceOf(TemplateLimitExceededException.class));
    }

    @Test
    void anOversizeTemplateIsRefused() {
        String html = "<p>" + "a".repeat(300 * 1024) + "</p>";
        assertThatThrownBy(() -> renderer.render("subject", html, null, Map.of()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aNewlineInTheSubjectIsRefused() {
        assertThatThrownBy(() -> renderer.render("Head\nInjected", "<p>x</p>", null, Map.of()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aRenderedNewlineInTheSubjectIsRefused() {
        assertThatThrownBy(() -> renderer.render("Hi {{name}}", "<p>x</p>", null, Map.of("name", "a\nb")))
                .isInstanceOf(TemplateLimitExceededException.class);
    }

    @Test
    void randomTemplatesNeverExceedTheOutputCapOrHang() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 40; attempt++) {
            int depth = random.nextInt(1, 5);
            String html = nested(depth);
            Map<String, Object> variables = Map.of("x", "a".repeat(random.nextInt(0, 400)));
            assertTimeoutPreemptively(BOUND, () -> {
                try {
                    RenderedParts rendered = renderer.render("subject", html, null, variables);
                    assertThat(rendered.html().length()).isLessThanOrEqualTo(RenderLimits.DEFAULT.maxOutputChars());
                } catch (RuntimeException expected) {
                    // A refused render is the other acceptable outcome; the point is that it terminates in bounds.
                }
            });
        }
    }

    private static String nested(int depth) {
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            html.append("{{#x}}");
        }
        html.append("{{x}}");
        for (int i = 0; i < depth; i++) {
            html.append("{{/x}}");
        }
        return html.toString();
    }
}

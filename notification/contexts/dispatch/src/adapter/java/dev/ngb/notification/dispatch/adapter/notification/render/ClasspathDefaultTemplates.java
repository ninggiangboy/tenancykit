package dev.ngb.notification.dispatch.adapter.notification.render;

import dev.ngb.notification.dispatch.application.notification.exception.TemplateTooLargeException;
import dev.ngb.notification.dispatch.application.notification.port.DefaultTemplates;
import dev.ngb.notification.dispatch.application.notification.port.TemplateVariant;
import dev.ngb.notification.dispatch.domain.notification.NotificationType;
import dev.ngb.notification.dispatch.domain.template.LocalePolicy;
import dev.ngb.notification.shared.TemplateKey;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The templates shipped in the jar, one directory per key and locale and one file per part.
 *
 * <p>It validates the whole tree in its constructor: every type × every supported locale × every part must exist, so
 * a packaging mistake fails the context at startup instead of one render at send time. Forbidden constructs and
 * oversize parts are refused here too, which is the load-time half of the logic-less guarantee.
 */
public class ClasspathDefaultTemplates implements DefaultTemplates {

    private static final List<String> PARTS = List.of("email.subject", "email.html", "email.text", "inapp.title", "inapp.body");

    private final Map<String, TemplateVariant> templates;
    private final int maxPartBytes;

    public ClasspathDefaultTemplates(int maxPartBytes) {
        this.maxPartBytes = maxPartBytes;
        this.templates = loadAll();
    }

    @Override
    public TemplateVariant find(TemplateKey key, String locale) {
        TemplateVariant variant = templates.get(id(key.value(), locale));
        if (variant == null) {
            throw new IllegalArgumentException(
                    "no default template for " + key.value() + " in " + locale);
        }
        return variant;
    }

    private Map<String, TemplateVariant> loadAll() {
        Map<String, TemplateVariant> loaded = new HashMap<>();
        for (NotificationType type : NotificationType.values()) {
            for (String locale : LocalePolicy.SUPPORTED) {
                Map<String, String> parts = new HashMap<>();
                for (String part : PARTS) {
                    String source = read(type.templateKey(), locale, part);
                    dev.ngb.notification.dispatch.application.notification.service.TemplatePolicy
                            .validatePart(part, source, maxPartBytes);
                    parts.put(part, source);
                }
                loaded.put(
                        id(type.templateKey(), locale),
                        TemplateVariant.of(
                                parts.get("email.subject"),
                                parts.get("email.html"),
                                parts.get("email.text"),
                                parts.get("inapp.title"),
                                parts.get("inapp.body")));
            }
        }
        return Map.copyOf(loaded);
    }

    private static String read(String key, String locale, String part) {
        String resource = "/templates/" + key + "/" + locale + "/" + part + ".mustache";
        try (InputStream stream = ClasspathDefaultTemplates.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("a default template is missing from the jar: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + resource, e);
        }
    }

    private static String id(String key, String locale) {
        return key + "/" + locale;
    }
}

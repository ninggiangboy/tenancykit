package dev.ngb.survey.authoring.application.flow.service;

import dev.ngb.survey.authoring.application.flow.query.PreviewView;
import dev.ngb.survey.authoring.domain.document.Json;
import dev.ngb.survey.authoring.domain.flow.PlaceholderText;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the preview of a draft document for one locale: every page in presentation order, every question with its
 * type and constraint flag, and every option label.
 *
 * <p>It is presentation, not evaluation. Nothing is skipped, nothing is hidden and no value is substituted: a builder
 * is looking at what it authored, and the placeholders say what piping will show once a respondent answers. Simulate
 * is the endpoint that runs the logic.
 */
public class PreviewService {

    public PreviewView preview(Map<String, Object> document, String locale) {
        Map<String, Object> entries = translations(document, locale);
        List<PreviewView.PageView> pages = new ArrayList<>();
        for (Object raw : array(document.get("pages"))) {
            Map<String, Object> page = Json.object(raw);
            if (page == null) {
                continue;
            }
            String pageId = Json.text(page.get("id"));
            List<PreviewView.QuestionView> questions = new ArrayList<>();
            for (Object rawQuestion : array(page.get("questions"))) {
                Map<String, Object> question = Json.object(rawQuestion);
                if (question == null) {
                    continue;
                }
                questions.add(question(question, entries));
            }
            pages.add(new PreviewView.PageView(
                    pageId, PlaceholderText.render(entries.get("page." + pageId + ".title")), List.copyOf(questions)));
        }
        return new PreviewView(localeOf(document, locale), List.copyOf(pages));
    }

    private PreviewView.QuestionView question(Map<String, Object> question, Map<String, Object> entries) {
        String id = Json.text(question.get("id"));
        List<PreviewView.OptionView> options = new ArrayList<>();
        for (Object rawOption : array(question.get("options"))) {
            Map<String, Object> option = Json.object(rawOption);
            if (option == null) {
                continue;
            }
            String optionId = Json.text(option.get("id"));
            options.add(new PreviewView.OptionView(
                    optionId,
                    Json.text(option.get("value")),
                    PlaceholderText.render(entries.get("option." + optionId + ".label"))));
        }
        return new PreviewView.QuestionView(
                id,
                Json.text(question.get("type")),
                Boolean.TRUE.equals(question.get("required")),
                PlaceholderText.render(entries.get("question." + id + ".label")),
                PlaceholderText.render(entries.get("question." + id + ".help")),
                List.copyOf(options));
    }

    private static Map<String, Object> translations(Map<String, Object> document, String locale) {
        Map<String, Object> all = Json.object(document.get("translations"));
        if (all == null) {
            return Map.of();
        }
        String chosen = localeOf(document, locale);
        Map<String, Object> entries = Json.object(all.get(chosen));
        return entries == null ? Map.of() : entries;
    }

    /** The requested locale when the version enables it, otherwise the version's default. */
    private static String localeOf(Map<String, Object> document, String requested) {
        Map<String, Object> settings = Json.object(document.get("settings"));
        String defaultLocale = settings == null ? null : Json.text(settings.get("default_locale"));
        if (requested == null || requested.isBlank()) {
            return defaultLocale;
        }
        List<Object> locales = settings == null ? null : Json.array(settings.get("locales"));
        if (locales != null && locales.contains(requested)) {
            return requested;
        }
        return defaultLocale;
    }

    private static List<Object> array(Object value) {
        List<Object> array = Json.array(value);
        return array == null ? List.of() : array;
    }
}

package dev.ngb.survey.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the version documents the authoring tests publish: one small valid survey, plus the mutators a test needs to
 * break one thing at a time. Every id is fixed, so a failure names the same pointer on every run.
 *
 * <p>Translations are filled in as nodes are added, because the semantic rules require every enabled locale to carry
 * every key; a test that wants a missing key removes one.
 */
public final class Documents {

    public static final String PAGE_ID = "11111111-1111-7111-8111-111111111111";
    public static final String TEXT_QUESTION_ID = "22222222-2222-7222-8222-222222222222";
    public static final String CHOICE_QUESTION_ID = "33333333-3333-7333-8333-333333333333";
    public static final String OPTION_A_ID = "44444444-4444-7444-8444-444444444444";
    public static final String OPTION_B_ID = "55555555-5555-7555-8555-555555555555";

    private Documents() {}

    /** A document that passes the schema and the semantic rules: one page, a text question and a choice. */
    public static Map<String, Object> valid() {
        Map<String, Object> document = new LinkedHashMap<>();
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("default_locale", "en");
        settings.put("locales", new ArrayList<>(List.of("en")));
        document.put("settings", settings);
        document.put("pages", new ArrayList<>());
        document.put("computed", new ArrayList<>());
        document.put("scoring", new LinkedHashMap<>());
        document.put("translations", new LinkedHashMap<>());

        addPage(document, PAGE_ID);
        addQuestion(document, 0, TEXT_QUESTION_ID, "short_text");
        question(document, 0, 0).put("identity", true);
        addQuestion(document, 0, CHOICE_QUESTION_ID, "single_choice");
        addOption(document, 0, 1, OPTION_A_ID);
        addOption(document, 0, 1, OPTION_B_ID);
        translations(document, "en").put("survey.title", "Test survey");
        return document;
    }

    /** Appends an empty page and its title. */
    public static Map<String, Object> addPage(Map<String, Object> document, String pageId) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("id", pageId);
        page.put("questions", new ArrayList<>());
        pages(document).add(page);
        translations(document, "en").put("page." + pageId + ".title", "Page");
        return page;
    }

    /** Appends a question of the given type and its label and help. */
    public static Map<String, Object> addQuestion(
            Map<String, Object> document, int pageIndex, String questionId, String type) {
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("id", questionId);
        question.put("type", type);
        questions(document, pageIndex).add(question);
        translations(document, "en").put("question." + questionId + ".label", "Question");
        translations(document, "en").put("question." + questionId + ".help", "Help");
        return question;
    }

    /** Appends an option and its label. */
    public static void addOption(
            Map<String, Object> document, int pageIndex, int questionIndex, String optionId) {
        Map<String, Object> question = question(document, pageIndex, questionIndex);
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("id", optionId);
        option.put("value", optionId.substring(0, 8));
        List<Object> options = options(question);
        options.add(option);
        question.put("options", options);
        translations(document, "en").put("option." + optionId + ".label", "Option");
    }

    /** Stops a question being a choice by dropping its options, so only the schema complains about it. */
    public static void removeOptions(Map<String, Object> question, Map<String, Object> document) {
        List<Object> options = options(question);
        for (Object option : options) {
            Map<String, Object> node = object(option);
            Object id = node.get("id");
            if (id != null) {
                translations(document, "en").remove("option." + id + ".label");
            }
        }
        question.remove("options");
    }

    @SuppressWarnings("unchecked")
    public static List<Object> pages(Map<String, Object> document) {
        return (List<Object>) document.get("pages");
    }

    public static Map<String, Object> page(Map<String, Object> document, int index) {
        return object(pages(document).get(index));
    }

    @SuppressWarnings("unchecked")
    public static List<Object> questions(Map<String, Object> document, int pageIndex) {
        return (List<Object>) page(document, pageIndex).get("questions");
    }

    public static Map<String, Object> question(Map<String, Object> document, int pageIndex, int questionIndex) {
        return object(questions(document, pageIndex).get(questionIndex));
    }

    @SuppressWarnings("unchecked")
    public static List<Object> options(Map<String, Object> question) {
        Object options = question.get("options");
        return options instanceof List<?> ? (List<Object>) options : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> translations(Map<String, Object> document, String locale) {
        Map<String, Object> translations = (Map<String, Object>) document.get("translations");
        return (Map<String, Object>) translations.computeIfAbsent(locale, key -> new LinkedHashMap<String, Object>());
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> settings(Map<String, Object> document) {
        return (Map<String, Object>) document.get("settings");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }
}

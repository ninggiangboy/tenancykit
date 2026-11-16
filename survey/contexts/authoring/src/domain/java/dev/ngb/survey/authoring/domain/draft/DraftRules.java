package dev.ngb.survey.authoring.domain.draft;

import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.survey.authoring.api.document.ValidationCode;
import dev.ngb.survey.authoring.api.document.ValidationError;
import dev.ngb.survey.authoring.domain.document.Json;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rules a draft document obeys beyond the shape {@code contracts/survey/version.schema.json} states: server-assigned
 * ids, unique ids, jumps that move forward, the translation key set and the single identity question.
 *
 * <p>Everything here is pure: a draft is data, and a rule that throws would turn a builder's mistake into a 500. A
 * malformed member is skipped by these checks and reported by the schema instead, so the two together describe the
 * whole contract without either one guessing at the other's job.
 */
public final class DraftRules {

    /**
     * The document a new survey starts from. Structurally it is not publishable yet — it has no page — which is what a
     * builder fills in; the server stores it so a draft always exists.
     */
    public static Map<String, Object> empty() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("default_locale", "en");
        settings.put("locales", List.of("en"));

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("settings", settings);
        document.put("pages", List.of());
        document.put("computed", List.of());
        document.put("scoring", Map.of());
        document.put("translations", Map.of("en", Map.of()));
        return document;
    }

    /**
     * The document with a server-assigned UUID wherever a structural node arrived without one. Ids the client already
     * holds are kept, which is what lets a builder save twice and see the same questions.
     */
    public static Map<String, Object> withIds(Map<String, Object> document, IdGenerator ids) {
        Map<String, Object> copy = Json.copyOf(document);
        for (Map<String, Object> page : objects(copy.get("pages"))) {
            assignId(page, ids);
            for (Map<String, Object> question : objects(page.get("questions"))) {
                assignId(question, ids);
                for (String member : List.of("options", "rows", "columns")) {
                    for (Map<String, Object> node : objects(question.get(member))) {
                        assignId(node, ids);
                    }
                }
            }
        }
        return copy;
    }

    /** Every reason the document cannot become a version, ordered by pointer so a report is stable. */
    public static List<ValidationError> problems(Map<String, Object> document) {
        List<ValidationError> errors = new ArrayList<>();
        Map<String, Object> settings = Json.object(document.get("settings"));
        List<String> locales = strings(settings == null ? null : settings.get("locales"));
        String defaultLocale = settings == null ? null : Json.text(settings.get("default_locale"));
        if (defaultLocale != null && !locales.contains(defaultLocale)) {
            errors.add(new ValidationError(
                    "/settings/default_locale",
                    ValidationCode.REQUIRED,
                    "the default locale must be one of the enabled locales"));
        }

        Map<String, String> firstPointerById = new LinkedHashMap<>();
        List<Map<String, Object>> pages = objects(document.get("pages"));
        Map<String, Integer> pageIndexById = new LinkedHashMap<>();
        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            Map<String, Object> page = pages.get(pageIndex);
            String pagePointer = "/pages/" + pageIndex;
            String pageId = recordId(page.get("id"), pagePointer + "/id", firstPointerById, errors);
            if (pageId != null) {
                pageIndexById.putIfAbsent(pageId, pageIndex);
            }
        }

        Set<String> translationKeys = new LinkedHashSet<>();
        translationKeys.add("survey.title");
        int identityQuestions = 0;
        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            Map<String, Object> page = pages.get(pageIndex);
            String pagePointer = "/pages/" + pageIndex;
            String pageId = Json.text(page.get("id"));
            if (pageId != null) {
                translationKeys.add("page." + pageId + ".title");
            }
            checkJumps(page, pagePointer, pageIndex, pageIndexById, errors);

            List<Map<String, Object>> questions = objects(page.get("questions"));
            for (int questionIndex = 0; questionIndex < questions.size(); questionIndex++) {
                Map<String, Object> question = questions.get(questionIndex);
                String questionPointer = pagePointer + "/questions/" + questionIndex;
                String questionId = recordId(
                        question.get("id"), questionPointer + "/id", firstPointerById, errors);
                if (questionId != null) {
                    translationKeys.add("question." + questionId + ".label");
                    translationKeys.add("question." + questionId + ".help");
                }
                if (Boolean.TRUE.equals(question.get("identity"))) {
                    identityQuestions++;
                    if (identityQuestions > 1) {
                        errors.add(new ValidationError(
                                questionPointer + "/identity",
                                ValidationCode.LIMIT_EXCEEDED,
                                "only one question may carry the respondent's identity"));
                    }
                }
                collectKeys(question, "options", "option", questionPointer, firstPointerById, errors, translationKeys);
                collectKeys(question, "rows", "row", questionPointer, firstPointerById, errors, translationKeys);
                collectKeys(question, "columns", "column", questionPointer, firstPointerById, errors, translationKeys);
            }
        }

        checkTranslations(document, locales, translationKeys, errors);

        errors.sort(Comparator.comparing(ValidationError::pointer)
                .thenComparing(error -> error.code().wireName())
                .thenComparing(ValidationError::message));
        return List.copyOf(errors);
    }

    /** The label keys of one question's options, rows or columns, plus their duplicate-id check. */
    private static void collectKeys(
            Map<String, Object> question,
            String member,
            String keyPrefix,
            String questionPointer,
            Map<String, String> firstPointerById,
            List<ValidationError> errors,
            Set<String> translationKeys) {
        List<Map<String, Object>> nodes = objects(question.get(member));
        for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
            Map<String, Object> node = nodes.get(nodeIndex);
            String pointer = questionPointer + "/" + member + "/" + nodeIndex;
            String id = recordId(node.get("id"), pointer + "/id", firstPointerById, errors);
            if (id != null) {
                translationKeys.add(keyPrefix + "." + id + ".label");
            }
        }
    }

    /** A jump may only move forward: a backward jump is a loop the path resolver would never leave. */
    private static void checkJumps(
            Map<String, Object> page,
            String pagePointer,
            int pageIndex,
            Map<String, Integer> pageIndexById,
            List<ValidationError> errors) {
        List<Map<String, Object>> jumps = objects(page.get("jumps"));
        for (int jumpIndex = 0; jumpIndex < jumps.size(); jumpIndex++) {
            String toPage = Json.text(jumps.get(jumpIndex).get("to_page"));
            if (toPage == null || toPage.equals("end")) {
                continue;
            }
            String pointer = pagePointer + "/jumps/" + jumpIndex + "/to_page";
            Integer target = pageIndexById.get(toPage);
            if (target == null) {
                errors.add(new ValidationError(
                        pointer, ValidationCode.DANGLING_REFERENCE, "the jump names no page of this version"));
            } else if (target <= pageIndex) {
                errors.add(new ValidationError(
                        pointer, ValidationCode.CYCLE, "a jump must lead to a page after this one"));
            }
        }
    }

    /**
     * Every enabled locale carries every key the version needs, and no key the version does not define. A missing key
     * is reported per pointer rather than as one failure, so a builder sees the whole list at once.
     */
    private static void checkTranslations(
            Map<String, Object> document, List<String> locales, Set<String> required, List<ValidationError> errors) {
        Map<String, Object> translations = Json.object(document.get("translations"));
        for (String locale : locales) {
            Map<String, Object> entries = translations == null ? null : Json.object(translations.get(locale));
            for (String key : required) {
                if (entries == null || !entries.containsKey(key)) {
                    errors.add(new ValidationError(
                            "/translations/" + escape(locale) + "/" + escape(key),
                            ValidationCode.TRANSLATION_MISSING,
                            "the locale " + locale + " has no text for this key"));
                }
            }
            if (entries == null) {
                continue;
            }
            for (String key : entries.keySet()) {
                if (!required.contains(key)) {
                    errors.add(new ValidationError(
                            "/translations/" + escape(locale) + "/" + escape(key),
                            ValidationCode.DANGLING_REFERENCE,
                            "no page, question, option, row or column of this version carries this key"));
                }
            }
        }
    }

    private static String recordId(
            Object rawId, String pointer, Map<String, String> firstPointerById, List<ValidationError> errors) {
        String id = Json.text(rawId);
        if (id == null) {
            // The schema reports the missing member and a value of the wrong kind; there is nothing to compare here.
            return null;
        }
        String first = firstPointerById.putIfAbsent(id, pointer);
        if (first != null) {
            errors.add(new ValidationError(
                    pointer, ValidationCode.DUPLICATE_ID, "another node already holds this id: " + first));
        }
        return id;
    }

    private static void assignId(Map<String, Object> node, IdGenerator ids) {
        if (Json.text(node.get("id")) == null) {
            node.put("id", ids.newId().toString());
        }
    }

    private static List<Map<String, Object>> objects(Object value) {
        List<Object> array = Json.array(value);
        if (array == null) {
            return List.of();
        }
        List<Map<String, Object>> objects = new ArrayList<>(array.size());
        for (Object member : array) {
            Map<String, Object> object = Json.object(member);
            if (object != null) {
                objects.add(object);
            }
        }
        return objects;
    }

    private static List<String> strings(Object value) {
        List<Object> array = Json.array(value);
        if (array == null) {
            return List.of();
        }
        List<String> strings = new ArrayList<>(array.size());
        for (Object member : array) {
            String text = Json.text(member);
            if (text != null) {
                strings.add(text);
            }
        }
        return strings;
    }

    /** RFC 6901: a pointer segment escapes {@code ~} and {@code /}. */
    private static String escape(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }
}

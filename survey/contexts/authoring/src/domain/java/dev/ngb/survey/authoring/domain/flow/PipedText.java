package dev.ngb.survey.authoring.domain.flow;

import dev.ngb.survey.authoring.domain.document.Json;
import dev.ngb.survey.authoring.domain.expression.Evaluator;
import dev.ngb.survey.authoring.domain.expression.Expr;
import dev.ngb.survey.authoring.domain.expression.ExprParser;
import dev.ngb.survey.authoring.domain.expression.Limits;
import dev.ngb.survey.authoring.domain.expression.Value;
import java.util.List;
import java.util.Map;

/**
 * Renders a translated sentence that pipes an answer into it.
 *
 * <p>A translated string is either plain text or an ordered list of literal strings and {@code {"ref": ...}} tokens.
 * Each substituted value is HTML-escaped, because the rendered sentence is shown to a respondent and the answer is
 * whatever they typed; the whole result is capped at {@link Limits#PIPED_CHARACTERS}. A substituted value is never
 * itself interpreted as a token, so an answer that contains braces stays literal text.
 */
public final class PipedText {

    private PipedText() {}

    /**
     * @param translation the stored entries of one locale
     * @param key the translation key to render, for example {@code question.<id>.label}
     * @param environment the answers and variables the tokens read
     * @return the rendered sentence, or null when the locale carries no such key
     */
    public static String render(Map<String, Object> translation, String key, Evaluator.Environment environment) {
        return render(translation.get(key), environment);
    }

    /** The rendered sentence of one stored translation value. */
    public static String render(Object stored, Evaluator.Environment environment) {
        if (stored == null) {
            return null;
        }
        String text = Json.text(stored);
        if (text != null) {
            return clip(text);
        }
        List<Object> tokens = Json.array(stored);
        if (tokens == null) {
            return null;
        }
        StringBuilder rendered = new StringBuilder();
        for (Object token : tokens) {
            String literal = Json.text(token);
            if (literal != null) {
                rendered.append(literal);
            } else {
                rendered.append(substitution(Json.object(token), environment));
            }
            if (rendered.length() >= Limits.PIPED_CHARACTERS) {
                break;
            }
        }
        return clip(rendered.toString());
    }

    private static String substitution(Map<String, Object> token, Evaluator.Environment environment) {
        if (token == null || !token.containsKey("ref")) {
            return "";
        }
        // The token is parsed the same way every other expression is, so a future client cannot disagree.
        Expr expression = ExprParser.parse(token.get("ref"), "/ref").expression();
        return expression == null ? "" : escape(Value.text(Evaluator.evaluate(expression, environment)));
    }

    private static String clip(String text) {
        return text.length() <= Limits.PIPED_CHARACTERS ? text : text.substring(0, Limits.PIPED_CHARACTERS);
    }

    /** The five characters a browser would read as markup become their entities; everything else is left alone. */
    private static String escape(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            switch (text.charAt(index)) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(text.charAt(index));
            }
        }
        return escaped.toString();
    }
}

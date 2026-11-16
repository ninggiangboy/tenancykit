package dev.ngb.survey.authoring.application.flow.query;

import java.util.List;
import java.util.Map;

/**
 * The pages of a draft as one locale renders them, in presentation order, with piping left as its stored tokens so a
 * builder shows its own placeholders rather than a value that no respondent has given yet.
 */
public record PreviewView(String locale, List<PageView> pages) {

    /**
     * One page.
     *
     * @param id the page id
     * @param title the rendered title, or null when the locale carries none
     * @param questions the questions in presentation order
     */
    public record PageView(String id, String title, List<QuestionView> questions) {}

    /**
     * One question.
     *
     * @param id the stable question id
     * @param type the question type
     * @param required whether an answer is required at submit
     * @param label the rendered label, or null when the locale carries none
     * @param help the rendered help text, or null when the locale carries none
     * @param options the option labels, in order
     */
    public record QuestionView(
            String id, String type, boolean required, String label, String help, List<OptionView> options) {}

    /** One option label. */
    public record OptionView(String id, String value, String label) {}
}

package dev.ngb.survey.insight.domain.export;

import java.util.List;

/**
 * Writes one CSV row, escaping the two things a spreadsheet reads as code.
 *
 * <p>A cell that begins with {@code =}, {@code +}, {@code -} or {@code @} is a formula to Excel and Sheets, so an answer
 * a respondent typed could run when a Tenant opens the file. Every such cell is prefixed with an apostrophe, which the
 * suite's own export reader strips. A cell containing a comma, a quote or a newline is quoted with its quotes doubled,
 * which is the RFC 4180 form. Multi-choice values are joined by a semicolon so one answer stays one cell.
 */
public final class CsvWriter {

    private CsvWriter() {}

    /** The separator a multi-choice answer's values are joined by. */
    public static final String MULTI_CHOICE_SEPARATOR = ";";

    /** One row, terminated by a newline. */
    public static String row(List<String> cells) {
        StringBuilder line = new StringBuilder();
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) {
                line.append(',');
            }
            line.append(cell(cells.get(index)));
        }
        return line.append('\n').toString();
    }

    /** One cell, escaped against formula injection and against the CSV separator. */
    public static String cell(String value) {
        String safe = value == null ? "" : value;
        if (startsWithFormula(safe)) {
            safe = "'" + safe;
        }
        if (needsQuoting(safe)) {
            return '"' + safe.replace("\"", "\"\"") + '"';
        }
        return safe;
    }

    private static boolean startsWithFormula(String value) {
        if (value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@';
    }

    private static boolean needsQuoting(String value) {
        return value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
    }
}

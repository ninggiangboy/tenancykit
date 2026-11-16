package dev.ngb.crm.customers.application.customer.query;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.util.List;

/**
 * The CSV an export is written as.
 *
 * <p>One row is written and flushed at a time, so the memory an export holds does not grow with the number of
 * customers. A cell that starts with {@code =}, {@code +}, {@code -} or {@code @} is prefixed with a quote, so a
 * spreadsheet that opens the file treats it as text instead of running it as a formula; a cell that carries a comma, a
 * quote or a newline is quoted the way RFC 4180 says.
 */
public final class CsvText {

    private final Writer out;

    public CsvText(Writer out) {
        this.out = out;
    }

    /** Writes one row, terminated by CRLF. */
    public void row(List<String> cells) throws IOException {
        StringBuilder line = new StringBuilder(64);
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(cell(cells.get(i)));
        }
        out.write(line.toString());
        out.write("\r\n");
    }

    public void flush() throws IOException {
        out.flush();
    }

    /** One cell: prefixed against a formula, then quoted only when it has to be. */
    static String cell(String raw) {
        String value = raw == null ? "" : raw;
        char first = value.isEmpty() ? 0 : value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@') {
            value = "'" + value;
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /** A field value as a cell: a decimal in plain notation, everything else as it prints. */
    static String value(Object raw) {
        if (raw == null) {
            return "";
        }
        if (raw instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (raw instanceof java.util.List<?> list) {
            StringBuilder joined = new StringBuilder();
            for (Object entry : list) {
                if (joined.length() > 0) {
                    joined.append('|');
                }
                joined.append(value(entry));
            }
            return joined.toString();
        }
        if (raw instanceof java.util.Map<?, ?> map) {
            return raw.toString();
        }
        return raw.toString();
    }
}

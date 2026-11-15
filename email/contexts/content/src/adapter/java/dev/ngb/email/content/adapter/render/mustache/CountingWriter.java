package dev.ngb.email.content.adapter.render.mustache;

import dev.ngb.email.content.api.render.TemplateLimitExceededException;
import java.io.Writer;

/**
 * A writer that refuses an oversize or overlong render as it writes, instead of discovering it after the whole body
 * has been built in memory. The count is characters, which is the unit the contract states.
 */
final class CountingWriter extends Writer {

    private final StringBuilder out = new StringBuilder();
    private final RenderBudget budget;
    private final int maxChars;

    CountingWriter(RenderBudget budget) {
        this.budget = budget;
        this.maxChars = budget.maxOutputChars();
    }

    @Override
    public void write(char[] buffer, int offset, int length) {
        budget.checkClock();
        if ((long) out.length() + length > maxChars) {
            throw new TemplateLimitExceededException("output size");
        }
        out.append(buffer, offset, length);
    }

    @Override
    public void write(String text, int offset, int length) {
        budget.checkClock();
        if ((long) out.length() + length > maxChars) {
            throw new TemplateLimitExceededException("output size");
        }
        out.append(text, offset, offset + length);
    }

    @Override
    public void write(int character) {
        budget.checkClock();
        if (out.length() + 1 > maxChars) {
            throw new TemplateLimitExceededException("output size");
        }
        out.append((char) character);
    }

    @Override
    public void flush() {
        // nothing buffered beyond the builder
    }

    @Override
    public void close() {
        // the caller reads value() after execute returns
    }

    String value() {
        return out.toString();
    }
}

package dev.ngb.loyalty.program.adapter.program.web;

import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.loyalty.program.application.program.exception.ProgramRefusal;

/** The one precondition rule the program's writes share: an update names the version it read. */
public final class Preconditions {

    private Preconditions() {}

    /**
     * @return the version an {@code If-Match} header accepts
     * @throws ProgramRefusal {@code IF_MATCH_REQUIRED} when the header is absent, which the API answers with 428
     */
    public static ExpectedVersion required(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw ProgramRefusal.ifMatchRequired();
        }
        return ExpectedVersion.fromIfMatch(ifMatch);
    }
}

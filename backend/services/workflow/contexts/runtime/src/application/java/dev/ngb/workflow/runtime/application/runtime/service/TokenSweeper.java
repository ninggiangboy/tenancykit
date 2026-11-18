package dev.ngb.workflow.runtime.application.runtime.service;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.workflow.runtime.application.runtime.port.TokenRepository;
import java.time.Instant;

/** Takes back the tokens whose worker died: the lease expired, so the token becomes READY again. */
public class TokenSweeper {

    private final UnitOfWork unitOfWork;
    private final TokenRepository tokens;
    private final Clock clock;

    public TokenSweeper(UnitOfWork unitOfWork, TokenRepository tokens, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tokens = tokens;
        this.clock = clock;
    }

    /** @return how many tokens were taken back */
    public int requeueExpired() {
        Instant now = clock.now();
        return unitOfWork.transactional(() -> tokens.requeueExpired(now));
    }
}

package dev.ngb.workflow.runtime.application.runtime.service;

import dev.ngb.core.runtime.Clock;
import dev.ngb.workflow.runtime.api.task.TaskResumer;
import dev.ngb.workflow.runtime.application.runtime.port.TokenRepository;
import java.util.UUID;

/** Resumes a task's parked token inside the caller's transaction, so a decision and its resume commit together. */
public class TaskResumerService implements TaskResumer {

    private final TokenRepository tokens;
    private final Clock clock;

    public TaskResumerService(TokenRepository tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    @Override
    public boolean resume(UUID tokenId, String branch, Object payload) {
        return tokens.resume(tokenId, branch, payload, clock.now());
    }
}

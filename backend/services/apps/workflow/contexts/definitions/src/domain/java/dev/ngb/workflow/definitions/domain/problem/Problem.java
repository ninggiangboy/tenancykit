package dev.ngb.workflow.definitions.domain.problem;

/**
 * One reason a definition or an expression is refused.
 *
 * @param pointer the RFC 6901 pointer of the offending member
 * @param code a stable code a client may branch on, one of {@link ProblemCodes}
 * @param message the sentence a person reads
 */
public record Problem(String pointer, String code, String message) {}

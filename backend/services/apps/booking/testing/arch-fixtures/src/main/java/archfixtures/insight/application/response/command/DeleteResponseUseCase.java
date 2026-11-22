package archfixtures.insight.application.response.command;

/**
 * Violates insight-writes-only-exports: a delete is a write, and only {@code collection} owns a response row, so
 * insight must call collection's use case instead of holding one of its own.
 */
public class DeleteResponseUseCase {}

package dev.ngb.mailroom.sending.application.email.service;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.mailroom.sending.application.email.command.SendEmailUseCase;
import dev.ngb.mailroom.sending.domain.email.Email;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sends a batch item by item. Each item runs in its own transaction through {@link SendEmailUseCase}, so a bad address
 * or a missing variable is an error for that item and never rolls back the items around it.
 *
 * <p>An item that could not even be translated into a command — an unknown {@code kind}, a missing required field — is
 * carried as {@link Item#refused} and reported at its own index. That keeps the promise the contract makes: one
 * malformed item is an error for that item, not a 500 for the whole call.
 */
public class BatchSender {

    public static final int MAX_ITEMS = 100;

    /** @param error set only when the item failed before an email row existed */
    public record Result(int index, UUID emailId, String status, String reason, String error) {}

    /** One item, either ready to send or already refused while being translated. */
    public record Item(SendEmailUseCase.Command command, RuntimeException refusal) {

        public static Item sent(SendEmailUseCase.Command command) {
            return new Item(command, null);
        }

        public static Item refused(RuntimeException refusal) {
            return new Item(null, refusal);
        }
    }

    private final SendEmailUseCase send;

    public BatchSender(SendEmailUseCase send) {
        this.send = send;
    }

    public List<Result> send(List<Item> items) {
        if (items == null || items.size() > MAX_ITEMS) {
            throw new BadRequestException("a batch carries at most " + MAX_ITEMS + " items");
        }
        List<Result> results = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            Item item = items.get(index);
            if (item.refusal() != null) {
                results.add(new Result(index, null, null, null, message(item.refusal())));
                continue;
            }
            try {
                Email email = send.execute(item.command());
                results.add(new Result(index, email.id(), email.status().name(), email.statusReason(), null));
            } catch (RuntimeException failure) {
                results.add(new Result(index, null, null, null, message(failure)));
            }
        }
        return results;
    }

    private static String message(RuntimeException failure) {
        if (failure instanceof BadRequestException || failure instanceof IllegalArgumentException) {
            return failure.getMessage();
        }
        return "the item could not be sent";
    }
}

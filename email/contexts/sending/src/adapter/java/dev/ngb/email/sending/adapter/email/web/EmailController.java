package dev.ngb.email.sending.adapter.email.web;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.email.sending.application.email.command.SendEmailUseCase;
import dev.ngb.email.sending.application.email.query.EmailQueries;
import dev.ngb.email.sending.application.email.query.EmailView;
import dev.ngb.email.sending.application.email.service.BatchSender;
import dev.ngb.email.sending.domain.email.Email;
import dev.ngb.email.shared.EmailKind;
import dev.ngb.email.shared.EmailPermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Sending one email or a batch, and reading the ledger. */
@RestController
@RequestMapping("/api/v1/emails")
class EmailController {

    private final SendEmailUseCase send;
    private final BatchSender batch;
    private final EmailQueries queries;

    EmailController(SendEmailUseCase send, BatchSender batch, EmailQueries queries) {
        this.send = send;
        this.batch = batch;
        this.queries = queries;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(EmailPermissions.EMAIL_SEND)
    EmailResponse send(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody SendEmailRequest request) {
        Email email = send.execute(command(request, idempotencyKey));
        return EmailResponse.of(email);
    }

    @PostMapping("/batch")
    @RequiresPermission(EmailPermissions.EMAIL_SEND)
    List<BatchResultResponse> batch(@RequestBody BatchRequest request) {
        List<SendEmailRequest> items = request.items();
        if (items == null || items.size() > BatchSender.MAX_ITEMS) {
            throw new BadRequestException("a batch carries at most " + BatchSender.MAX_ITEMS + " items");
        }
        // Each item is translated on its own: a missing or unknown kind is that item's failure, not a 500 for the
        // whole call, which is the batch contract's promise that one bad item never spoils the others.
        List<BatchSender.Item> commands = new ArrayList<>(items.size());
        for (SendEmailRequest item : items) {
            try {
                commands.add(BatchSender.Item.sent(command(item, item.idempotencyKey())));
            } catch (RuntimeException refusal) {
                commands.add(BatchSender.Item.refused(refusal));
            }
        }
        return batch.send(commands).stream()
                .map(result -> new BatchResultResponse(
                        result.index(), result.emailId(), result.status(), result.reason(), result.error()))
                .toList();
    }

    @GetMapping
    @RequiresPermission(EmailPermissions.EMAIL_READ)
    List<EmailResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) UUID campaign,
            @RequestParam(required = false) String recipient,
            @RequestParam(required = false) UUID after,
            @RequestParam(required = false) Integer limit) {
        return queries.list(status, kind, campaign, recipient, after, limit).stream()
                .map(EmailResponse::of)
                .toList();
    }

    @GetMapping("/{id}")
    @RequiresPermission(EmailPermissions.EMAIL_READ)
    EmailResponse get(@PathVariable UUID id) {
        return EmailResponse.of(queries.get(id));
    }

    private static SendEmailUseCase.Command command(SendEmailRequest request, String idempotencyKey) {
        return SendEmailUseCase.Command.of(
                request.to(),
                EmailKind.valueOf(request.kind()),
                request.fromKey(),
                request.templateKey(),
                request.variables(),
                request.subject(),
                request.html(),
                request.text(),
                idempotencyKey);
    }

    record SendEmailRequest(
            String to,
            String kind,
            String fromKey,
            String templateKey,
            Map<String, Object> variables,
            String subject,
            String html,
            String text,
            String idempotencyKey) {}

    record BatchRequest(List<SendEmailRequest> items) {}

    record BatchResultResponse(int index, UUID emailId, String status, String reason, String error) {}

    record EmailResponse(
            UUID id,
            String kind,
            String status,
            String statusReason,
            String to,
            String subject,
            UUID campaignId,
            UUID receiverId,
            UUID templateId,
            Integer templateRevision,
            String html,
            String text,
            boolean bodyPurged,
            Instant deliveryUpdatedAt,
            Instant createdAt,
            Instant updatedAt,
            List<?> events) {

        static EmailResponse of(Email email) {
            return new EmailResponse(
                    email.id(), email.kind().name(), email.status().name(), email.statusReason(),
                    email.toAddress(), email.subject(), email.campaignId(), email.receiverId(),
                    email.templateId(), email.templateRevision(), email.bodyHtml(), email.bodyText(),
                    email.bodyPurged(), email.deliveryUpdatedAt(), email.createdAt(), email.updatedAt(),
                    List.of());
        }

        static EmailResponse of(EmailView view) {
            return new EmailResponse(
                    view.id(), view.kind(), view.status(), view.statusReason(), view.toAddress(),
                    view.subject(), view.campaignId(), view.receiverId(), view.templateId(),
                    view.templateRevision(), view.bodyHtml(), view.bodyText(), view.bodyPurged(),
                    view.deliveryUpdatedAt(), view.createdAt(), view.updatedAt(), view.events());
        }
    }
}

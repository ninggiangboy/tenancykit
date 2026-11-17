package dev.ngb.customer.customers.adapter.consent.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.customer.customers.application.consent.command.CreateConsentTopicUseCase;
import dev.ngb.customer.customers.application.consent.command.PatchConsentTopicUseCase;
import dev.ngb.customer.customers.application.consent.command.RecordConsentUseCase;
import dev.ngb.customer.customers.application.consent.query.ConsentQueries;
import dev.ngb.customer.customers.application.consent.query.ConsentRecordView;
import dev.ngb.customer.customers.application.consent.query.ConsentTopicListing;
import dev.ngb.customer.customers.application.consent.query.ConsentTopicQueries;
import dev.ngb.customer.customers.application.consent.query.ConsentTopicView;
import dev.ngb.customer.customers.domain.consent.ConsentEvent;
import dev.ngb.customer.customers.domain.consent.ConsentTopic;
import dev.ngb.customer.customers.domain.consent.ConsentStatus;
import dev.ngb.customer.shared.CustomerPermissions;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Tenant's consent topics and the records its customers' agreements are made of.
 *
 * <p>A record is appended and never rewritten, so a revocation is its own row and the grant it revokes stays in the
 * history. The current state of a topic is therefore a read of the latest record, not a column somebody keeps in step.
 */
@Profile("api")
@RestController
@RequestMapping("/api/v1")
public class ConsentController {

    /** A topic the Tenant defines: the key is chosen once. */
    public record ConsentTopicRequest(String key, String label) {}

    /** The properties to change: a null one is left as it is. */
    public record ConsentTopicPatch(String label, Boolean archived) {}

    /** One consent record to append. The optional {@code consentRef} makes the call replayable. */
    public record ConsentRequest(
            String topicKey,
            String status,
            Instant occurredAt,
            String source,
            String evidenceRef,
            String consentRef) {}

    /** One consent record as the API returns it. */
    public record ConsentRecordResponse(
            String topicKey,
            String status,
            Instant occurredAt,
            Instant recordedAt,
            String source,
            String evidenceRef,
            String consentRef) {

        static ConsentRecordResponse of(ConsentEvent event) {
            return new ConsentRecordResponse(
                    event.topicKey(),
                    event.status().name(),
                    event.occurredAt(),
                    event.recordedAt(),
                    event.source(),
                    event.evidenceRef(),
                    event.consentRef());
        }

        static ConsentRecordResponse ofView(ConsentRecordView view) {
            return new ConsentRecordResponse(
                    view.topicKey(),
                    view.status().name(),
                    view.occurredAt(),
                    view.recordedAt(),
                    view.source(),
                    view.evidenceRef(),
                    view.consentRef());
        }
    }

    public record ConsentTopicResponse(String key, String label, boolean archived) {

        static ConsentTopicResponse of(ConsentTopic topic) {
            return new ConsentTopicResponse(topic.key(), topic.label(), topic.archived());
        }

        static ConsentTopicResponse ofView(ConsentTopicView view) {
            return new ConsentTopicResponse(view.key(), view.label(), view.archived());
        }
    }

    /** The current state per topic and the history behind it, newest first. */
    public record ConsentsResponse(List<ConsentRecordResponse> current, List<ConsentRecordResponse> history) {}

    private final CreateConsentTopicUseCase createTopic;
    private final PatchConsentTopicUseCase patchTopic;
    private final RecordConsentUseCase record;
    private final ConsentTopicQueries topics;
    private final ConsentQueries consents;

    public ConsentController(
            CreateConsentTopicUseCase createTopic,
            PatchConsentTopicUseCase patchTopic,
            RecordConsentUseCase record,
            ConsentTopicQueries topics,
            ConsentQueries consents) {
        this.createTopic = createTopic;
        this.patchTopic = patchTopic;
        this.record = record;
        this.topics = topics;
        this.consents = consents;
    }

    @GetMapping("/consent-topics")
    @Listing(ConsentTopicListing.class)
    @RequiresPermission(CustomerPermissions.FIELD_MANAGE)
    public Page<ConsentTopicResponse> listConsentTopics(ListQuery query) {
        return topics.list(query).map(ConsentTopicResponse::ofView);
    }

    @PostMapping("/consent-topics")
    @RequiresPermission(CustomerPermissions.FIELD_MANAGE)
    public ResponseEntity<ConsentTopicResponse> createConsentTopic(
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody ConsentTopicRequest request) {
        ConsentTopic topic =
                createTopic.execute(new CreateConsentTopicUseCase.Command(request.key(), request.label()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ConsentTopicResponse.of(topic));
    }

    @PatchMapping("/consent-topics/{key}")
    @RequiresPermission(CustomerPermissions.FIELD_MANAGE)
    public ConsentTopicResponse patchConsentTopic(
            @PathVariable String key,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody ConsentTopicPatch request) {
        ConsentTopic topic =
                patchTopic.execute(key, new PatchConsentTopicUseCase.Change(request.label(), request.archived()));
        return ConsentTopicResponse.of(topic);
    }

    /** Appends one record; a repeated {@code consentRef} with the same body returns the record it appended. */
    @PostMapping("/customers/{externalRef}/consents")
    @RequiresPermission(CustomerPermissions.CUSTOMER_MANAGE)
    public ResponseEntity<ConsentRecordResponse> recordConsent(
            @PathVariable String externalRef,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody ConsentRequest request) {
        RecordConsentUseCase.Result result = record.execute(
                externalRef,
                new RecordConsentUseCase.Command(
                        request.topicKey(),
                        request.status(),
                        request.occurredAt(),
                        request.source(),
                        request.evidenceRef(),
                        request.consentRef()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ConsentRecordResponse.of(result.event()));
    }

    @GetMapping("/customers/{externalRef}/consents")
    @RequiresPermission(CustomerPermissions.CUSTOMER_READ)
    public ConsentsResponse listConsents(@PathVariable String externalRef) {
        var view = consents.read(externalRef);
        return new ConsentsResponse(
                view.current().stream().map(ConsentRecordResponse::ofView).toList(),
                view.history().stream().map(ConsentRecordResponse::ofView).toList());
    }
}

package dev.ngb.mailer.email.adapter.delivery.ses;

import dev.ngb.mailer.email.application.email.port.EmailSender;
import dev.ngb.mailer.email.application.email.port.OutgoingEmail;
import dev.ngb.mailer.email.application.email.port.SendOutcome;
import dev.ngb.mailer.shared.Recipient;
import java.util.List;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.MessageHeader;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

/**
 * The production adapter. A 200 from SES means <em>accepted</em>, not delivered, which is exactly what
 * {@code SUCCEEDED} records.
 *
 * <p>Every decision the ledger needs is made by {@link SesErrorTranslator}; this class only builds the request. The
 * charset is stated explicitly on the subject and both bodies so a non-ASCII character survives the round trip.
 */
public class SesV2EmailSender implements EmailSender {

    private final SesClientFactory clients;

    public SesV2EmailSender(SesClientFactory clients) {
        this.clients = clients;
    }

    @Override
    public SendOutcome send(OutgoingEmail email) {
        SesV2Client client = clients.client(email.sender());
        try {
            SendEmailResponse response = client.sendEmail(request(email));
            return new SendOutcome.Accepted(response.messageId());
        } catch (SesV2Exception failure) {
            return SesErrorTranslator.translate(failure);
        } catch (RuntimeException failure) {
            return SesErrorTranslator.translateUnexpected(failure);
        }
    }

    private static SendEmailRequest request(OutgoingEmail email) {
        List<String> cc = email.cc().stream().map(Recipient::address).toList();
        SendEmailRequest.Builder builder = SendEmailRequest.builder()
                .fromEmailAddress(from(email))
                .destination(destination -> {
                    destination.toAddresses(email.recipient().address());
                    if (!cc.isEmpty()) {
                        destination.ccAddresses(cc);
                    }
                })
                .content(content -> content.simple(simple -> simple
                        .subject(subject -> subject.data(email.subject()).charset("UTF-8"))
                        .body(body -> {
                            if (email.html() != null) {
                                body.html(part -> part.data(email.html()).charset("UTF-8"));
                            }
                            if (email.text() != null) {
                                body.text(part -> part.data(email.text()).charset("UTF-8"));
                            }
                        })
                        // The allowlist is the command contract's; SES v2 carries them as message headers beside the
                        // body, not as a destination or a policy.
                        .headers(messageHeaders(email))));
        if (email.sender().replyTo() != null) {
            builder.replyToAddresses(email.sender().replyTo());
        }
        return builder.build();
    }

    private static List<MessageHeader> messageHeaders(OutgoingEmail email) {
        return email.headers().entrySet().stream()
                .map(header -> MessageHeader.builder()
                        .name(header.getKey())
                        .value(header.getValue())
                        .build())
                .toList();
    }

    /**
     * SESv2 has no separate display-name field on a send, so the name rides in the address the way a mail client writes
     * it. {@code fromName} is validated at the contract boundary to contain no quote, angle bracket, comma or line
     * break, which is what makes this concatenation safe.
     */
    private static String from(OutgoingEmail email) {
        String address = email.sender().fromAddress();
        String name = email.sender().fromName();
        return name == null || name.isBlank() ? address : name + " <" + address + ">";
    }
}

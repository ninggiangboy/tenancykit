package dev.ngb.mailer.email.adapter.delivery.smtp;

import dev.ngb.mailer.email.application.email.port.EmailSender;
import dev.ngb.mailer.email.application.email.port.OutgoingEmail;
import dev.ngb.mailer.email.application.email.port.SendOutcome;
import dev.ngb.mailer.shared.Recipient;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * The local adapter: plain SMTP, which is what Mailpit speaks. It is a separate adapter from SES rather than a
 * configuration of it because assumed-role credentials cannot authenticate over SMTP at all, so one adapter could never
 * serve both.
 *
 * <p>The error mapping is the only transport-specific logic here: a message that cannot be built or an address the
 * server refuses is the request's fault and is terminal, while a server that is down or slow is left to the ledger's
 * backoff.
 */
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mail;

    public SmtpEmailSender(JavaMailSender mail) {
        this.mail = mail;
    }

    @Override
    public SendOutcome send(OutgoingEmail email) {
        MimeMessage message;
        try {
            message = build(email);
        } catch (MessagingException | UnsupportedEncodingException invalid) {
            return new SendOutcome.Rejected("could not build the message: " + invalid.getMessage());
        }
        try {
            mail.send(message);
            // SMTP has no provider id; the local message id is evidence, not a receipt, and reading it may fail on a
            // message that was never saved.
            String messageId;
            try {
                messageId = message.getMessageID();
            } catch (MessagingException unreadable) {
                messageId = null;
            }
            return new SendOutcome.Accepted(messageId == null ? "smtp" : messageId);
        } catch (MailException failure) {
            return translate(failure);
        }
    }

    private static SendOutcome translate(MailException failure) {
        if (failure instanceof MailParseException) {
            return new SendOutcome.Rejected("the message is malformed: " + failure.getMessage());
        }
        if (failure instanceof MailAuthenticationException) {
            return new SendOutcome.Rejected("the SMTP server refused the credentials");
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SendFailedException refused) {
                // A refused recipient or sender is the request's fault; retrying the same address cannot succeed.
                return new SendOutcome.Rejected("the SMTP server refused the addresses: " + refused.getMessage());
            }
        }
        return new SendOutcome.Transient("the SMTP server did not accept the message: " + failure.getMessage());
    }

    private MimeMessage build(OutgoingEmail email) throws MessagingException, UnsupportedEncodingException {
        MimeMessage message = mail.createMimeMessage();
        // The helper is created with a charset, so a non-ASCII subject or body is encoded rather than mangled.
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        if (email.sender().fromName() == null) {
            helper.setFrom(email.sender().fromAddress());
        } else {
            helper.setFrom(email.sender().fromAddress(), email.sender().fromName());
        }
        helper.setTo(email.recipient().address());
        if (!email.cc().isEmpty()) {
            helper.setCc(email.cc().stream().map(Recipient::address).toArray(String[]::new));
        }
        if (email.sender().replyTo() != null) {
            helper.setReplyTo(email.sender().replyTo());
        }
        helper.setSubject(email.subject());
        if (email.html() != null && email.text() != null) {
            // multipart/alternative: the client picks the part it can render.
            helper.setText(email.text(), email.html());
        } else if (email.html() != null) {
            helper.setText(email.html(), true);
        } else {
            helper.setText(email.text(), false);
        }
        return message;
    }
}

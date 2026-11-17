---
phase: 11
title: "Mailer: outbound attachments"
status: pending
priority: P1
effort: "M"
dependencies: [1]
---

# Phase 11: Mailer, Outbound Attachments

## Goal

`email.send` can carry attachments, so an agent's reply reaches an email or form customer with the files in the mail itself. This
is the only phase that edits another app, and it is backward compatible: a command without the new field behaves exactly as
before.

## Context

A user decision of 2026-11-17 (the alternative of signed links was offered and declined). The command schema
(`contracts/commands/email.send.schema.json`) has `additionalProperties: false`, so the field must be added to the contract first.
The transports already build the message: `SesV2EmailSender` uses the SES v2 simple content (which supports attachments) and
`SmtpEmailSender` builds a `MimeMessage` through `MimeMessageHelper`, which supports attachments. The open design question is how
the bytes reach mailer, and it depends on how mailer persists a command for retry, which this phase reads first.

## Design gate (first task, before any edit)

Read the delivery ledger and the command handler (`mailer/contexts/email/src/**`, its migrations, `docs/mailer.md`) and record in
`plans/reports/mailer-attachments-design.md`:

1. Where is the rendered body kept between acceptance and send, and for how long? Is it in a database column, and does the
   ledger purge it?
2. Option A, **inline by value**: each attachment is `{filename, contentType, contentBase64}` in the command; mailer stores the
   bytes with the delivery row until it is terminal and then purges them. Simple, no new dependency, broker message grows.
3. Option B, **by reference**: `{filename, contentType, sizeBytes, sha256, url}` where mailer fetches a presigned URL when it sends.
   Keeps the broker light but needs an outbound fetch with a destination guard, and the URL must outlive mailer's retry window;
   inside the compose network the object store sits on a private address that `DestinationPolicy` refuses.
4. Default: Option A with a hard cap of 10 MB of raw attachment bytes per command and at most 10 attachments, and mailer purging
   bytes on terminal status. Choose B only if A cannot be held in mailer's persistence reasonably, and say why. **If neither is
   acceptable, stop and ask.**

## Files to create / modify

- Modify: `contracts/commands/email.send.schema.json` (optional `attachments`, limits, filename pattern), `contracts/commands/README.md`
- Modify: `mailer/contexts/email/**` (command validation, ledger storage and purge, both senders, an attachment value object with
  a size and type check), its migration (a new `V*__` for attachment storage or a column), `docs/mailer.md` (the "is not" table
  keeps "a mailbox", gains a sentence on attachments)
- Create: tests `AttachmentValidationTest`, `SesWireContractTest` additions (the SES request carries the attachment),
  `SmtpDeliveryIntegrationTest` additions (Mailpit shows the attachment), `AttachmentPurgeTest`

## Tasks

1. Write the design report and get the contract right first; a contract test proves the schema accepts a command with and without
   the field and rejects a name with a path separator, CR/LF, a type that is not `type/subtype`, or a total over the cap.
2. Implement the chosen option in command validation, storage and both senders; the filename is sanitised and never used as a path.
3. Purge stored bytes when the delivery reaches `SUCCEEDED` or `DEAD`, in the same transaction as the status change.
4. Keep `email-delivery.changed` unchanged.
5. Run mailer's entire existing test suite unchanged; add the new tests only.

## Verification

- `cd mailer && ./gradlew check` is green with **no existing test modified**.
- A command with two attachments is delivered to Mailpit with both parts and the right names and types; the SES request built in
  `SesWireContractTest` carries them.
- A command without `attachments` produces byte-identical output to before (golden comparison of the built message).
- After the delivery succeeds or dies, no attachment bytes remain in mailer's storage.
- `git diff --stat` touches only `mailer/`, `contracts/commands/**`, `docs/mailer.md` and the report.

## Risks

- Mailer is a stable service every other app depends on: the field is optional, the cap is hard, and the existing tests are the
  proof of no regression.
- A larger broker message and a larger ledger row for mail with attachments: the cap bounds both, and purge on terminal status
  bounds storage over time.

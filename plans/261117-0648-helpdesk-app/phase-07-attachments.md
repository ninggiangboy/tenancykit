---
phase: 7
title: "Attachments"
status: pending
priority: P1
effort: "L"
dependencies: [5]
---

# Phase 7: Attachments

## Goal

An agent, the public form, the tracking link and (later) inbound mail can attach files to a message; every file is checked,
stored under a key that never uses its name, and downloadable only through a short-lived presigned URL.

## Context

Copy the pattern of `survey/contexts/collection/.../upload/**` (`S3UploadStore`, `PresignUploadUseCase`, `UploadVerifier`,
`PurgePendingUploadsUseCase`) in a separate build; never import Survey. Inbound mail attachments arrive as bytes inside the
MIME, so the store also needs a server-side put path that runs the same checks (used in phase 15).

## Files to create

- Migration `V4__attachments.sql`: `attachments` (tenant, ticket, message nullable until attached, `object_key`, `declared_type`,
  `detected_type`, `size_bytes`, `sha256`, `status` PENDING|ATTACHED|REJECTED, `created_at`), RLS, jOOQ regenerated
- `helpdesk/contexts/ticketing/.../attachment/**`: `AttachmentStore` port, `S3AttachmentStore`, `PresignAttachmentUseCase`,
  `CompleteAttachmentUseCase`, `AttachmentVerifier`, `PurgePendingAttachmentsUseCase`, controller
- Tests: `AttachmentVerifierTest` (type spoofing, size, executable types, double extension), `AttachmentLifecycleIntegrationTest`
  against MinIO, `AttachmentRowLevelSecurityTest`, `PurgePendingAttachmentsTest`

## Tasks

1. **Limits.** 10 MB per file, 25 MB per message, 10 files per message; the allowlist covers documents, images, text, archives
   and common office types; executables, scripts and HTML files are refused. The limits are settings, the allowlist is code.
2. **Presign flow.** `POST /attachments` returns an upload id and a presigned PUT whose length and type are fixed into the
   signature; the client PUTs, then `POST /attachments/{id}/complete`; completing reads the object head, verifies size, sniffs
   the content type (magic bytes) and matches it to the declared type and extension; a mismatch rejects and deletes the object.
3. **Attach.** A reply, note or public message names upload ids that belong to this ticket and this Tenant and are `PENDING`
   or completed; attaching flips them to `ATTACHED`; an id of another ticket or Tenant is refused.
4. **Download.** Only `GET /attachments/{id}/download`, answering a presigned GET with `Content-Disposition: attachment`, and
   only for a caller with `helpdesk:ticket:read`; a public caller gets downloads only for public messages through the tracking
   token (phase 13).
5. **Key.** `tenant/{tenantId}/helpdesk/{ticketId}/{attachmentId}`; the file name is stored as metadata and sanitised on output.
6. **Purge.** Pending uploads older than 24 hours are removed with their objects; a closed ticket's retention follows the
   Tenant's setting (default keep; deletion for one person is phase 18's erasure).
7. **Server-side put.** `AttachmentStore.putVerified(...)` for bytes helpdesk already holds, running the same verifier.

## Verification

- A `.exe` renamed `.png`, a file whose bytes are HTML declared as `image/png`, a file over the limit and a double extension
  are each rejected, and no object remains.
- Download of tenant A's attachment with tenant B's token is `404`; the presigned GET carries `Content-Disposition: attachment`.
- `git grep -n "originalFilename" -- helpdesk` shows no use in an object key.
- The purge job removes a 25-hour-old pending upload and its object and nothing else.

## Risks

- No malware scanning in v1 (a non-goal): executable refusal, content sniffing and attachment-only downloads are the controls,
  and the residual risk is written in `docs/helpdesk.md`.

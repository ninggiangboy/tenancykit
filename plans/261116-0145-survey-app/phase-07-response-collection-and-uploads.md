---
phase: 7
title: "Response collection and uploads"
status: pending
priority: P1
effort: "L"
dependencies: [5, 6]
---

# Phase 7: Response Collection and Uploads

## Goal

A respondent saves a draft, resumes it, uploads files and submits; the server validates the path actually taken,
computes variables and the score, and enforces the window, the cap, single use and idempotency under concurrency.

## Files to create / modify

- Create: `survey/contexts/collection/...` (`response`, `answer`, `upload` packages)
- Create: migration `V4__responses.sql`: `responses` (id, tenant_id, survey_id, version_id, link_id, visit_id,
  status `IN_PROGRESS|COMPLETED`, locale, answers jsonb, vars jsonb, score, respondent_identity, external_ref, source,
  idempotency_key, personal_link_id, started_at, submitted_at; unique `(tenant_id, idempotency_key)`;
  `personal_link_id` is set only for personal links, and a partial unique index on `(personal_link_id) where status =
  'COMPLETED'` allows one completion per personal link), `answers` (response_id, tenant_id,
  survey_id, version_id, question_id, option_id, row_id, num_value, text_value, date_value, source, submitted_at;
  indexes `(survey_id, question_id, option_id)`, `(survey_id, submitted_at)`), `survey_counters` (survey_id, completed,
  cap), `uploads` (id, tenant_id, visit_id, question_id, object_key, declared_size, declared_mime, status
  `PENDING|ATTACHED`, created_at)
- Modify: `survey/apps/app-boot` config for `SURVEY_S3_ENDPOINT` (server side), `SURVEY_S3_PUBLIC_ENDPOINT` (the host a
  respondent's browser reaches, used only to sign URLs), `SURVEY_S3_BUCKET`, credentials
- Modify: `contracts/openapi/survey.yaml`

## API

| Method and path | Notes |
|---|---|
| `PUT /p/v/{visit_token}/draft` | Saves partial answers; first save moves the visit to `STARTED` |
| `GET /p/v/{visit_token}/draft` | Resume |
| `POST /p/v/{visit_token}/uploads` | `{question_id, filename, size, mime}` → `{upload_id, url, headers}` presigned PUT, 15 minutes |
| `POST /p/v/{visit_token}/submit` | Requires `Idempotency-Key`; returns `{response_id, score?}` |

## Tasks

1. **Draft.** Stored on the `responses` row in `IN_PROGRESS` with the version the visit opened; resuming always uses
   that version, even if a newer one is published, so a respondent never sees questions change mid-way.
2. **Submit validation.** Run `SurveyFlow` (phase 5) over the answers: answers to questions off the path are rejected
   with `answer_not_on_path`; required questions on the path must be answered; type constraints apply (email format,
   number range, choice ids belong to the question, matrix cells complete, date range); client-sent `vars` or `score`
   are ignored.
3. **Concurrency.** In one transaction: check window and status; `update survey_counters set completed = completed + 1
   where survey_id = ? and (cap is null or completed < cap)`; zero rows → `409 cap_reached`; update the response to
   `COMPLETED` (the partial unique index rejects a second completion on a personal link → `409 already_completed`);
   insert typed `answers` rows; mark the visit `COMPLETED`; append the outbox row (phase 9 adds the publisher). A
   repeated `Idempotency-Key` returns the first result.
4. **Identity.** `respondent_identity` is the personal link's `external_ref`, else the value of the question marked
   `identity: true` (lower-cased, trimmed), else null.
5. **Uploads.** Presign with the AWS SDK v2 S3 presigner (the SDK mailer already uses for SES), fixing
   `Content-Length` and `Content-Type`. At submit, `HEAD` each referenced object (size must equal the declared size and
   the question's limit) and read its first 512 bytes to match magic bytes against the question's allowed types; then
   set `ATTACHED`. Object keys are `tenant/{tenant_id}/survey/{survey_id}/{upload_id}`; filenames are never used as keys.
6. **Housekeeping (worker).** Delete `PENDING` uploads older than 24 hours and their objects.
7. **Response deletion** lives here because `collection` owns the rows: a `DeleteResponseUseCase` hard-deletes the
   response, its `answers` and its upload objects, nulls `visits.response_id` and keeps the visit's source and device
   facts; the counter is not decremented (the cap counts submissions). Phase 8 exposes it at `DELETE /responses/{id}`.

## Verification

- `cd survey && ./gradlew test --tests '*response*' --tests '*upload*'` green (Testcontainers PostgreSQL and MinIO).
- Tests: 50 threads submitting against a cap of 10 → exactly 10 `COMPLETED`; the same personal link submitted twice
  concurrently → one success, one `409`; a hidden question's answer → `answer_not_on_path`; a required question
  behind a `skip_if` is not required; a client-sent score is ignored; a renamed `.exe` declared as `image/png` fails the
  magic-byte check; an orphaned upload is gone after housekeeping; a draft resumed after v2 is published still uses v1.

## Risks

- The partial unique index needs the link kind on the response row; denormalise `personal_link_id` rather than joining
  in the constraint.

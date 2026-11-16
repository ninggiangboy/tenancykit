---
phase: 4
title: "Authoring: drafts, versions, translations"
status: done
priority: P1
effort: "M"
dependencies: [3]
---

# Phase 4: Authoring — Drafts, Versions, Translations

## Goal

A Tenant creates a survey, edits its draft as one document with optimistic concurrency, gets structural errors by path,
and publishes immutable versions with stable question and option ids and complete translations.

## Files to create / modify

- Create: `survey/contexts/authoring/src/{domain,application,adapter,api}/java/dev/ngb/survey/authoring/...`
- Create: migration `V2__authoring.sql`: `surveys` (id, tenant_id, name, status `DRAFT|OPEN|CLOSED`, current_version_id,
  row version), `survey_drafts` (survey_id, document jsonb, etag, updated_at, updated_by), `survey_versions`
  (id, survey_id, version_no, document jsonb, published_at, published_by; unique `(survey_id, version_no)`),
  `question_lineage` (question_id, survey_id, replaces, first_version_no)
- Modify: `contracts/openapi/survey.yaml`

## API

| Method and path | Permission | Notes |
|---|---|---|
| `POST /surveys` | `survey:survey:manage` | Creates the survey and an empty draft |
| `GET /surveys`, `GET /surveys/{id}` | `survey:survey:read` | |
| `GET /surveys/{id}/draft` | `survey:survey:read` | Returns `ETag` |
| `PUT /surveys/{id}/draft` | `survey:survey:manage` | Requires `If-Match`; `412` on mismatch, `428` without it |
| `POST /surveys/{id}/draft/validate` | `survey:survey:read` | Errors by JSON pointer, without saving |
| `POST /surveys/{id}/publish` | `survey:survey:manage` | Requires `If-Match` of the draft |
| `GET /surveys/{id}/versions`, `GET /surveys/{id}/versions/{no}` | `survey:survey:read` | |
| `POST /surveys/{id}/close`, `POST /surveys/{id}/reopen` | `survey:survey:manage` | |

## Tasks

1. **Draft document.** Stored as submitted after structural validation against `contracts/survey/version.schema.json`.
   The server assigns UUIDs to pages, questions, options, matrix rows and columns that arrive without one and returns
   the stored document, so a builder keeps ids across saves.
2. **Validation by path.** Errors are `{pointer, code, message}`; codes are a closed list (`required`, `unknown_type`,
   `duplicate_id`, `dangling_reference`, `translation_missing`, `limit_exceeded`, `cycle`, `expression_invalid`). The
   expression checks are added in phase 5; this phase leaves a hook.
3. **Publish.** In one transaction: validate; compare with the latest version; a question whose `type` changed gets a
   new id and a `question_lineage` row with `replaces`; insert the version with `version_no = max + 1`; set
   `current_version_id`; status becomes `OPEN` on first publish. The draft remains editable afterwards.
4. **Translations.** Every enabled locale must have every key (`survey.title`, `page.<id>.title`,
   `question.<id>.label`, `question.<id>.help`, `option.<id>.label`, `row.<id>.label`, `column.<id>.label`); missing keys
   fail publish with `translation_missing` per pointer. A translation fix is a new version.
5. **Immutability.** No update or delete statement targets `survey_versions`; the `survey_app` role has only
   `select, insert` on it (enforced in the migration and asserted in a test).

## Verification

- `cd survey && ./gradlew test --tests '*authoring*'` green.
- Tests: concurrent `PUT` with the same `ETag` yields one `200` and one `412`; publish v2 after retyping a question gives
  the new question a new id with `replaces`; publish fails with every missing translation listed; `survey_app` cannot
  update a version row; tenant B gets `404` for tenant A's survey.

## Risks

- A client that drops ids on save makes every publish create new questions and breaks cross-version aggregation.
  Document it in the OpenAPI description and return the stored document from `PUT`.

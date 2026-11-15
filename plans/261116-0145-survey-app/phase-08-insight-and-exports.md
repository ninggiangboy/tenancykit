---
phase: 8
title: "Insight: analytics, funnel, respondents, exports"
status: pending
priority: P1
effort: "L"
dependencies: [7]
---

# Phase 8: Insight — Analytics, Funnel, Respondents, Exports

## Goal

A Tenant reads and deletes responses, sees per-question aggregates, filters by answers, cross-tabs, trends and the
funnel by source merged across versions, lists respondents by status, and exports CSV.

## Files to create / modify

- Create: `survey/contexts/insight/...` (`query`, `funnel`, `respondent`, `export` packages)
- Create: migration `V5__insight.sql`: `exports` (id, tenant_id, survey_id, status `QUEUED|RUNNING|DONE|FAILED`,
  filter jsonb, object_key, rows, requested_by, created_at, finished_at)
- Modify: `contracts/openapi/survey.yaml`

## API

| Method and path | Permission |
|---|---|
| `GET /surveys/{id}/responses?version&from&to&source&status&external_ref` | `survey:response:read` |
| `GET /responses/{id}`, `GET /responses/{id}/files/{upload_id}` (presigned GET, attachment) | `survey:response:read` |
| `DELETE /responses/{id}` | `survey:response:delete` |
| `POST /surveys/{id}/analytics/aggregate` | `survey:analytics:read` |
| `POST /surveys/{id}/analytics/crosstab` | `survey:analytics:read` |
| `POST /surveys/{id}/analytics/trend` | `survey:analytics:read` |
| `GET /surveys/{id}/analytics/funnel?group_by=source` | `survey:analytics:read` |
| `GET /surveys/{id}/respondents?state=COMPLETED|STARTED|ABANDONED&source` | `survey:response:read` |
| `POST /surveys/{id}/exports`, `GET /exports/{id}` (presigned GET when done) | `survey:export:manage` |

## Tasks

1. **Filter.** Every analytics body takes an optional `where` written in the expression language, restricted to
   `answer` references and comparison and boolean operators; it compiles to SQL over `answers` with bound parameters
   (no string building), plus `version`, `from`, `to` and `source`.
2. **Aggregate.** Per question type: counts per option (choice, multi), mean, median and distribution (number, rating),
   NPS (promoters 9-10, detractors 0-6), counts per cell (matrix), response count (text, file). Grouped by the stable
   question id across versions; a retyped question appears as its own question with a `replaces` link.
3. **Cross-tab.** Two choice-like questions (choice, rating, nps bucket, source, country); counts and row percentages;
   at most 50 × 50 cells.
4. **Trend.** Count, mean or NPS by `day|week` over `submitted_at` in the survey's time zone setting (default UTC).
5. **Funnel.** From `visits`: opened, started, completed, abandoned, and the last page viewed for abandoned visits,
   grouped by `source`, `country` or `device_class`; bot visits excluded.
6. **Abandonment job (worker).** A visit `STARTED` with `last_activity_at` older than the survey's `abandon_after`, or
   any non-completed visit when the survey closes, becomes `ABANDONED`.
7. **Respondents.** Rows of `{respondent_identity, external_ref, source, state, last_activity_at, response_id}`; this
   is the list a Tenant imports into a mailroom group for a resend.
8. **Delete.** The endpoint calls `collection`'s `DeleteResponseUseCase` through its `api` package (phase 7, task 7);
   `insight` itself writes only its `exports` table.
9. **Export (worker).** Streams responses to CSV in pages of 1,000 (one column per question id across versions, header
   from the latest version's default-locale label, multi-choice joined by `;`), cells starting with `= + - @` prefixed
   with `'` against formula injection, written to S3; the API returns a presigned GET valid 15 minutes.

## Verification

- `cd survey && ./gradlew test --tests '*insight*'` green.
- Tests: aggregates over v1 and v2 match a hand-computed fixture including a removed, an added and a retyped question;
  a `where` with an injection-shaped value returns the right rows; cross-tab and trend match fixtures; funnel by source
  matches visits created through two sources; an abandoned visit appears after the job; a deleted response disappears
  from every aggregate and its objects are gone; an exported CSV opens with the expected header and escaped cells.
- An `explain` check in a test shows the aggregate query uses the `answers` index.

## Risks

- Large surveys make cross-tab and trend slow; the cell cap and the indexes are the v1 bound, documented in
  `docs/survey.md`.

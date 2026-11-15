---
phase: 1
title: "Scope, docs and contracts"
status: done
priority: P1
effort: "M"
dependencies: []
---

# Phase 1: Scope, Docs and Contracts

## Goal

Record the new app in the docs and write every contract the later phases implement, before any code exists.

## Files to create / modify

- Create: `docs/survey.md` (what it is and is not, contexts, data model outline, public surface, the manual resend
  recipe; finished in phase 10)
- Modify: `docs/overview-and-scope.md` (six deployables; survey's row; the "first business app beyond Billing" non-goal
  bullets now point to survey; survey sends nothing), `docs/README.md`, `docs/architecture.md`, `README.md`
- Create: `contracts/permissions/survey.yaml`
- Create: `contracts/survey/version.schema.json`, `contracts/survey/expression.schema.json`,
  `contracts/survey/expression-vectors/{logic,arithmetic,text,collections,piping,scoring,limits}.json`,
  `contracts/survey/README.md`
- Create: `contracts/events/survey/{survey-response.submitted,survey-version.published,survey.closed}.schema.json`
- Modify: `contracts/events/README.md`, `contracts/messaging/README.md` (exchange `survey.events`, queue
  `survey.platform-events`), `contracts/README.md`
- Create: `contracts/openapi/survey.yaml` (skeleton with tags and security schemes; operations added per phase)

## Tasks

1. **Docs.** Write the scope in `docs/survey.md` and update `docs/overview-and-scope.md`. Keep the statement that the
   survey app never sends a message; Tenant data is stored on the Tenant's behalf and deleted on request.
2. **Permission catalog** (`survey:<resource>:<action>`, all tenant scope):
   `survey:survey:read`, `survey:survey:manage` (drafts, publish, close), `survey:link:manage`,
   `survey:response:read` (responses, respondents, file downloads), `survey:response:delete`,
   `survey:analytics:read`, `survey:export:manage`, `survey:webhook:manage`, and `survey:settings:manage`
   (not grantable to a Tenant System). Follow the header comment style of `contracts/permissions/mailroom.yaml`.
3. **Version document schema.** Survey settings (default locale, enabled locales, window, cap, quiz on/off and whether
   the score is returned, abandonment threshold), pages (id, title, `skip_if`, `jumps[]` of `{when, to_page}`),
   questions (id, type, required, `visible_if`, constraints per type, `identity: true` allowed on `email`/`text`),
   options (id, value, score), matrix rows and columns, computed variables (`name`, `expr`, declared order), scoring
   rules, and `translations[locale][key]`. Question types: `short_text`, `long_text`, `number`, `email`,
   `single_choice`, `multi_choice`, `rating`, `nps`, `date`, `matrix`, `file`.
4. **Expression schema.** Literals (string, number, boolean, null, array ≤ 100), `{"answer": "<question id>"}`,
   `{"answer": "<question id>", "row": "<row id>"}`, `{"var": "<computed name>"}`; operators `eq neq gt gte lt lte in
   contains and or not exists empty count sum add sub mul div min max if len lower`. Limits: depth ≤ 16, nodes ≤ 500
   per expression, ≤ 2,000 per version. Piping is a text value written as an array of strings and `{"ref": ...}`
   tokens. Null and type-mismatch rules are written down here (comparisons with `null` are `false`, `div` by zero is
   `null`, arithmetic on `null` is `null`). Numbers are decimals: every arithmetic result is rounded to 6 fraction
   digits with `HALF_EVEN`, and comparisons are exact on rounded values; numbers travel as JSON numbers with at most
   6 fraction digits. <!-- Updated: Validation Session 1 - decimal number semantics -->
5. **Vectors.** Each vector file is a list of `{name, version_fragment, answers, expected: {visible, path, vars,
   score, errors}}`. At least 120 vectors covering every operator, every null rule, every limit and every question type
   in `visible_if`.
6. **Events.** The suite envelope of `docs/suite-messaging.md` (`id`, `type`, `tenant_id`, `occurred_at`,
   `schema_version`, `data`). `survey-response.submitted.data`: `survey_id`, `version_id`, `response_id`, `link_id`,
   `source`, `external_ref`, `respondent_identity`, `score`, `submitted_at`; no answers in the event.
7. **Webhook headers.** Document `Survey-Event-Id`, `Survey-Event-Type`, `Survey-Timestamp`, `Survey-Key-Id`,
   `Survey-Signature`, `Survey-Attempt`, with the same `v1=` HMAC rule as Billing (`docs/webhooks-and-events.md`).

## Verification

- Every JSON file under `contracts/survey/` and `contracts/events/survey/` validates as JSON Schema draft 2020-12, and
  every vector's `version_fragment` validates against `version.schema.json` (a small script or a test in phase 5; here
  a `python3 -m json.tool` pass on every file).
- `platform` builds with the new catalog: `cd platform && ./gradlew test --tests '*PermissionCatalog*'`.
- `git grep -n -i "five deployables" docs/` returns nothing (today it matches `docs/overview-and-scope.md:13`).

## Risks

- The vectors are the contract with a UI that does not exist yet; under-specifying null handling now becomes a client
  bug later. Every rule in task 4 needs at least one vector.

---
title: "Survey authoring: drafts, versions and the ETag contract"
date: 2026-11-16
summary: "Phase 4 of the survey app: the authoring context stores one editable draft, publishes immutable versions and gives a retyped question a new id with lineage"
---

# Survey authoring: drafts, versions and the ETag contract

## What happened

Implemented phase 4 of `plans/261116-0145-survey-app`: the `authoring` context now owns
`surveys`, `survey_drafts`, `survey_versions` and `question_lineage` (migration `V2__authoring.sql`,
jOOQ schema regenerated and committed). A Tenant creates a survey, reads and writes its draft under
an `If-Match` tag and publishes the next immutable version.

- `DraftRules` assigns a UUID to every page, question, option, matrix row and column that arrives
  without one, and reports duplicate ids, dangling/backward jumps, the full missing-translation list
  and a second `identity` question, by JSON pointer with the contract's closed codes.
- The structural gate is the packaged `contracts/survey/version.schema.json` validated at runtime
  with networknt 3.0.7. The version schema's relative `$ref` to `expression.schema.json` is resolved
  by registering both files under their `$id`, so an expression outside the closed operator set is
  rejected even before phase 5's parser exists.
- Only the shape is enforced on `PUT /draft`; the semantic rules run on `POST /draft/validate` and at
  publish, so an incomplete draft is savable but never publishable.
- `PublishSurveyUseCase` compares question types with the latest version, gives a retyped question a
  new id, records `question_lineage(replaces, first_version_no)`, rewrites `answer`/`question`
  references and the `question.<id>.label|help` translation keys, and writes the retyped document
  back to the draft so the next publish does not retype it again.
- Missing `If-Match` is `428` (`ConditionalRequests.requireIfMatch` in `shared-core`), stale is `412`,
  a shape failure is `422` with every pointer.

## Decision

- The draft carries its own `etag`; a publish consumes it (again `412` on reuse), because a publish
  may rewrite the draft's ids.
- `survey_versions` is immutable in the database: the migration revokes `update, delete` from
  `survey_app`, and `VersionImmutabilityTest` proves the privilege error.
- A publish keeps a closed survey closed; only the first publish moves `DRAFT` to `OPEN`.
- Format assertions are on for the schema, so a non-UUID id fails the save instead of reaching storage.

## Verification

`cd survey && ./gradlew build` green: 140 tests, 0 failures (ArchUnit, Modulith, RLS on all four new
tables, jOOQ drift, both runtimes' startup, 33 authoring tests). Commits: `b5052a92`, `9ab40bb2`,
`c7da3542`. Attribution app work in the same worktree was left untouched.

## Next steps

Phase 5: the expression engine (`Expr`, `ExprParser`, `ExprChecker`, `Evaluator`), preview/simulate,
and plug `ExprChecker` into phase 4's validation hook so expression references, node budget and cycles
join the same pointer report.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.

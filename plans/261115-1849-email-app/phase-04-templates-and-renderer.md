---
phase: 4
title: "Templates and the sandboxed renderer"
status: done
priority: P1
effort: "L"
dependencies: [3]
---

# Phase 4: Templates and the Sandboxed Renderer

## Goal

A Tenant manages templates through the API, previews them, and a renderer produces a subject, HTML and text from a
template plus variables — with sections and loops, and with hard limits that make a hostile template harmless.

## Context

- `notification/contexts/dispatch/src/adapter/.../render/JmustacheTemplateRenderer.java` and `StrictTemplateModel.java`
  — the library, the strict model and the part-size validation to learn from; `docs/notification.md` "Templates, parts and locale".
- `contracts/commands/email.send.schema.json` — subject ≤ 998 without line breaks; html and text ≤ 262144 characters.

## Data model (context `content`)

- `email_templates(id, tenant_id, key, name, current_revision, version, created_at, updated_at, unique (tenant_id, key))`
- `email_template_revisions(id, tenant_id, template_id, revision, subject, html, text, created_at)` — immutable.
  A campaign pins a revision, and every email stores its `template_revision`, so a later edit never changes history.
- RLS on both tables; `version` is the optimistic lock used with `ETag`/`If-Match`, as in the rest of the suite.

## Renderer rules (the sandbox)

| Rule | Enforced |
|---|---|
| Partials (`{{> x}}`), lambdas, set-delimiters and comments-with-logic | refused at write and again at load |
| `{{{x}}}` and `{{& x}}` | refused in `subject` and `html`; allowed in `text` only (text is not markup) |
| Section nesting depth | ≤ 4 |
| Template size | each part ≤ 256 KiB; rendered output ≤ 262144 characters |
| Total loop iterations per render | ≤ 10,000, counted by a wrapper around every `Iterable` the model hands to JMustache |
| Wall-clock per render | ≤ 500 ms, checked by a counting `Writer` and the iteration counter |
| Variables payload | ≤ 64 KiB JSON, ≤ 1,000 items per array, nesting ≤ 3 |
| Unknown variable at any depth, including `receiver.*` | render fails with the variable name (the model refuses every missing lookup, as `StrictTemplateModel` does in notification); a section or inverted section over a missing name also fails, so a default is supplied by the caller, not the template |
| Rendered subject | no CR/LF, ≤ 998 |

A render that breaks a limit throws a typed exception; the caller records the email as `FAILED` with a reason and never
sends a partial body. Link rewriting for tracking (phase 8) runs **after** rendering, on the final HTML.

## Files to create

- `mailroom/contexts/content/src/domain/.../template/{Template,TemplateRevision,TemplateKey}.java`
- `mailroom/contexts/content/src/application/.../template/{command,query,port,service}/*` (create, update, delete, get, list, preview)
- `mailroom/contexts/content/src/application/.../render/{TemplateRenderer,RenderLimits,TemplatePolicy}.java` (ports and policy)
- `mailroom/contexts/content/src/adapter/.../render/{JmustacheTemplateRenderer,LimitedModel,CountingWriter}.java`
- `mailroom/contexts/content/src/adapter/.../persistence/Jooq*Repository.java`; web controllers and `api` port `RenderTemplate`
- `mailroom/apps/app-boot/src/main/resources/db/migration/V2__templates.sql`; `contracts/openapi/mailroom.yaml` (templates)
- Tests under `mailroom/apps/app-boot/src/test/java/dev/ngb/mailroom/content/`

## Endpoints (prefix `/mailroom/api/v1`)

`POST /templates`, `GET /templates`, `GET /templates/{id}`, `PUT /templates/{id}` (new revision, `If-Match`),
`DELETE /templates/{id}`, `GET /templates/{id}/revisions`, `POST /templates/{id}/preview` (variables in, rendered parts
out, same limits as sending). Permissions `mailroom:template:read|manage`.

## Verification

- Adversarial `TemplateRendererTest`: nested loops that would exceed 10,000 iterations, a 300 KiB template, a
  `{{> partial}}`, a lambda-shaped section, `{{{ x }}}` in HTML, a receiver attribute containing `<script>` (must be escaped),
  a template that emits only whitespace in loops, a subject containing a newline — each fails with the expected exception
  and finishes within the wall-clock bound.
- Property test: random depth/size inputs never exceed the output cap and never hang.
- Behavioural tests: sections and `{{#items}}` loops over supplied data, inverted sections over supplied empty values, a
  misspelled variable fails, a missing `receiver.*` attribute fails, and the failure names the variable.
- RLS test for both tables; contract test that every endpoint exists in `contracts/openapi/mailroom.yaml` and every permission
  in `contracts/permissions/mailroom.yaml`.

## Risks

- JMustache has no built-in resource limits; the guard must wrap the model and the writer, not trust the library. If a
  hostile case slips through, run the render in a bounded executor as a second line.
- Notification's renderer is deliberately **not** reused: it is coupled to its ports and its policy forbids sections.
  The two stay separate; do not move either into `core/` without a second reason.

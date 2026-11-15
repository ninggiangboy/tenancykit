---
title: "Phase 4: Templates, Rendering and Locale"
status: todo
---

# Phase 4: Templates, Rendering and Locale

## Context Links

- `plans/261112-0726-enterprise-suite-foundation/phase-10-email-and-templates.md` — the cancelled template design this
  phase narrows to what is needed
- `contracts/commands/email.send.schema.json` — `subject` max 998, `html`/`text` max 262144, no newlines in `subject`
- `notification/libs/shared-core` — `TemplateKey`
- `mailer/contexts/email/src/adapter/java/dev/ngb/mailer/email/adapter/delivery/persistence` — the jOOQ adapter shape

## Overview

Priority P1, size M. Adds the content layer the notification pipeline will call: a default template per type and
locale in the classpath, a tenant override row that wins over it, a renderer that fails rather than sending a
half-filled message, and a locale policy that is a pure function of two inputs.

This phase deliberately has no consumer in the pipeline. That keeps its tests about rendering only — escaping, missing
variables, forbidden constructs, fallback — instead of mixing them with event handling. Phase 5 is the first caller.

## Key Insights

- JMustache renders a missing variable as an empty string, which is exactly the half-filled email that must never be
  sent. The fix is not a flag: JMustache resolves values through the model's `Map`, so a `Map` decorator whose `get`
  throws for an unknown key turns a missing variable into a hard failure. That is the strictness mechanism, and it is
  one small class with a test.
- JMustache also supports sections and partials, which are logic. A tenant override containing `{{#`, `{{^` or `{{>`
  is refused at write time **and** at load time, so a row inserted by hand cannot smuggle logic in.
- Escaping differs per part: HTML parts escape, text parts must not (`&amp;` in a plain-text body is a bug). One
  renderer with an `EscapeMode` per part, not two renderers.
- Locale resolution is `user → tenant → en`, which needs no service call and no table: it is a three-line function of
  two nullable tags. Making it a port now would be an abstraction with no second implementation.
- A template row is one row per `(tenant, key, locale)` holding all five parts, because the API updates a whole variant
  at once and a per-part table would make "which parts does this override define" a query problem.
- The 998-character subject limit and the newline ban come from the email contract; validating them here means the
  publisher in Phase 6 never has to reject its own payload.

## Requirements

Functional

- Five notification types × two locales (`en`, `vi`) × five parts (`email.subject`, `email.html`, `email.text`,
  `inapp.title`, `inapp.body`) ship as classpath defaults.
- A tenant override row for `(key, locale)` replaces the corresponding parts and leaves the rest default.
- Rendering fails with a named exception when a variable is missing, a construct is forbidden, or a size limit is
  exceeded.
- Locale resolution: the user's preference if present, else the tenant default, else `en`.

Non-functional

- Rendering is pure: same template version plus same variables gives the same bytes.
- No template is read from the database for the classpath default path; a missing override is not an error.
- The renderer is in `application`/`domain` terms (no Spring, no HTTP types) and reachable from a unit test without a
  container.

## Architecture

```
application/notification/port/TemplateSource      ← tenant override lookup (adapter: jOOQ)
application/notification/port/DefaultTemplates    ← classpath lookup (adapter: resources)
application/notification/port/RenderedMessage     ← record(title, body, subject, html, text, templateVersion, locale)
application/notification/service/TemplateRenderer ← part-by-part JMustache + StrictModel + EscapeMode
application/notification/service/LocalePolicy     ← choose(userLocale, tenantLocale) → "en"|"vi"
```

Classpath layout, one directory per key, one per locale, one file per part:

```
templates/<templateKey>/<locale>/email.subject.mustache
templates/<templateKey>/<locale>/email.html.mustache
templates/<templateKey>/<locale>/email.text.mustache
templates/<templateKey>/<locale>/inapp.title.mustache
templates/<templateKey>/<locale>/inapp.body.mustache
```

Storage, one row per override variant. **Revised:** the table is suite-wide, not per tenant — no `tenant_id`, no RLS,
and a key of `(template_key, locale)`. A template is the suite's content; one row serves every tenant, and only a
Platform user at platform scope may write it.

```sql
create table notification_templates (
    template_key text    not null,
    locale       text    not null check (locale in ('en', 'vi')),
    subject      text,
    html         text,
    text         text,
    title        text,
    body         text,
    version      integer not null default 1,
    updated_at   timestamptz not null default now(),
    primary key (template_key, locale)
);
```

The tenant-scoped version this phase originally specified was dropped before any database ran it, so it was edited in
place rather than migrated away.

## Related Code Files

Create

- `notification/contexts/dispatch/src/domain/java/dev/ngb/notification/dispatch/domain/template/{RenderedMessage,LocalePolicy}.java`
- `notification/contexts/dispatch/src/application/java/dev/ngb/notification/dispatch/application/notification/port/{TemplateSource,DefaultTemplates,TemplateVariant}.java`
- `notification/contexts/dispatch/src/application/java/dev/ngb/notification/dispatch/application/notification/service/{TemplateRenderer,StrictTemplateModel,EscapeMode}.java`
- `notification/contexts/dispatch/src/application/java/dev/ngb/notification/dispatch/application/notification/exception/{MissingTemplateVariableException,ForbiddenTemplateConstructException,TemplateTooLargeException}.java`
- `notification/contexts/dispatch/src/adapter/java/dev/ngb/notification/dispatch/adapter/notification/persistence/{JooqTemplateSource,JooqTemplateOverrides}.java`
- `notification/contexts/dispatch/src/adapter/java/dev/ngb/notification/dispatch/adapter/notification/render/ClasspathDefaultTemplates.java`
- `notification/contexts/dispatch/src/adapter/resources/templates/<templateKey>/{en,vi}/*.mustache` — 50 files
- `notification/apps/app-boot/src/main/resources/db/migration/V2__templates.sql`
- `notification/apps/app-boot/src/test/java/dev/ngb/notification/dispatch/TemplateRendererTest.java`,
  `LocalePolicyTest.java`, `TemplateOverrideTest.java`

Modify

- `notification/contexts/dispatch/build.gradle.kts` — `adapterImplementation(libs.jmustache)`
- `notification/libs/jooq-schema` — regenerated for `notification_templates`

## Implementation Steps

1. Add the `jmustache` dependency to the catalog and to the dispatch module's `adapterImplementation` configuration only.
2. Write `LocalePolicy.choose(String userLocale, String tenantLocale)`: return the first non-blank supported tag, else
   `en`. Reject an unsupported tag by falling through, never by throwing — a bad preference must not block a
   notification.
3. Write the 50 default templates. Keep them short and factual: what happened, the tenant name, the affected object,
   and one link. No marketing copy, no images, no inline CSS beyond a single style block.
4. Write `StrictTemplateModel` (a `Map` decorator over the variables that throws `MissingTemplateVariableException` on
   `get`/`containsKey` for an unknown key) and `EscapeMode` (`HTML` for the html parts, `NONE` for text parts).
5. Write `TemplateRenderer.render(templateKey, locale, variables)`:
   - resolve the variant from `TemplateSource` (tenant override) or `DefaultTemplates` (classpath);
   - refuse any part containing `{{#`, `{{^` or `{{>` with `ForbiddenTemplateConstructException`;
   - refuse a part larger than 64 KiB or a subject larger than 998 characters or containing a line break;
   - render each part with the strict model and its escape mode;
   - return a `RenderedMessage` carrying `templateVersion` (the override's version, or 0 for the classpath default) and
     the resolved locale.
6. Write `V2__templates.sql` with the table above, RLS, and `grant`s already covered by default privileges; regenerate
   jOOQ with `-Djooq.update=true`.
7. Write `JooqTemplateSource` (read one variant, return empty when absent) and `ClasspathDefaultTemplates` (reads
   `templates/<key>/<locale>/<part>.mustache`, throws a startup error if a key or locale is missing for any of the five
   types).
8. Tests:
   - `TemplateRendererTest`: a variable containing `<script>alert(1)</script>` is escaped in `html` and untouched in
     `text`; a missing variable throws; a template with a section throws; a subject with `\n` throws; a 70 KiB part
     throws.
   - `LocalePolicyTest`: `("vi","en") → vi`, `(null,"vi") → vi`, `(null,null) → en`, `("fr","vi") → vi`.
   - `TemplateOverrideTest`: **revised** as a suite-wide test. Two different tenants render the same override byte for
     byte; a missing override is empty rather than an error; deleting it removes it for every tenant; a second save bumps
     the revision the ledger records; and the table has no `tenant_id` column and no row-level security. The old
     "tenant A's override does not leak into tenant B" case no longer has a subject.

## Todo

- [x] `LocalePolicy` with the three-step fallback
- [x] 50 default templates (5 types × 2 locales × 5 parts)
- [x] `StrictTemplateModel` and `EscapeMode`
- [x] `TemplateRenderer` with the three refusals
- [x] `V2__templates.sql` + regenerated jOOQ
- [x] Classpath and jOOQ template sources
- [x] Renderer, locale and override tests

## Success Criteria

- `find notification -path '*adapter/resources/templates/*' -name '*.mustache' | wc -l` is 50.
- `cd notification && ./gradlew :apps:app-boot:test --tests '*Template*' --tests '*LocalePolicy*'` passes.
- The escaping test asserts `&lt;script&gt;` in the HTML part and the literal `<script>` in the text part.
- A tenant override row with a `{{#` construct is refused by the renderer even when written directly with SQL.

## Risk Assessment

| Risk | Mitigation |
|---|---|
| A missing default template is discovered at send time | `ClasspathDefaultTemplates` validates all five types × two locales at startup and fails fast |
| Tenant overrides accumulate unmerged copies and never receive later default fixes | `version` records the override's own revision; the resolved message stores both the key and the version, so the dispatch ledger shows which content produced the email |
| HTML escaping strips intended formatting | Only variable substitution is escaped; the surrounding markup is authored by the template owner |
| Unicode or RTL content breaks the text part | Templates are UTF-8; the renderer never truncates, only refuses oversize parts |

## Security Considerations

- Logic-less by enforcement, not by convention: sections, inverted sections and partials are refused at write and at
  load, so a tenant cannot execute anything through a template.
- Variable values are escaped in HTML, which closes the injection path from a tenant name or permission code into the
  message body.
- Template size limits bound memory per render and keep a payload inside the email contract's maxima.
- Templates are tenant-scoped by RLS; a tenant can neither read nor edit another tenant's override, and the classpath
  defaults are not writable through the API.

## Next Steps

Phase 5 is the first caller: it resolves the audience, calls the renderer, and stores the in-app notification.

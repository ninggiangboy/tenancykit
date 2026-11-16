# Survey

The suite's app for letting a **Tenant ask its own customers something**. A Tenant builds a survey, publishes it as
an immutable version, hands out links, and reads who answered, where people stopped and what the answers add up to.
It is a separate deployable (`survey/`, root package `dev.ngb.survey`) with three runtimes: `migrate`, `api` and
`worker`.

Survey never sends a message and knows no channel. It issues links; the Tenant puts a link wherever it likes — a
mailroom campaign, a QR code on a receipt, a page on its own site. Delivery and unsubscribes stay with
[mailroom](mailroom.md) and [mailer](mailer.md).

## What it is, and what it is not

| It is | It is not |
|---|---|
| The owner of a Tenant's surveys, their immutable versions, links, visits, page events, responses, answers, uploads and the analysis of all of it | A sender: it has no template, no recipient list and no channel, and publishes no `email.send` |
| The single owner of the expression language that decides branching, computed variables, quiz scoring and piping | A form builder UI or a fill UI: both are a later plan; this app is API only |
| A producer of `survey.events` and signed `Survey-*` webhooks to a Tenant System | A consumer of another app's business data: it keeps the Platform replica and reads nothing from Billing or mailroom |
| Idempotent and reconciliation-free: a respondent is a link, a visit and a response, never an account | A CRM: it does not reconcile a respondent with a mailroom receiver, and does not deduplicate a person across links |

The suite stores a Tenant's customer data on the Tenant's behalf. Survey keeps the least it can: no raw IP, no user
agent string, no referrer path, and an explicit delete that removes answers and uploaded objects.

## Runtimes

| Profile | Runs | Migrates |
|---|---|---|
| `api` | HTTP: surveys, drafts, versions, links, the public fill surface, responses, analytics, exports, webhook endpoints | no |
| `worker` | The `platform.events` listener, the outbox relay, webhook delivery, the replica rebuild, the abandonment job and upload housekeeping | no |
| `migrate` | Flyway as the table owner, then exits | yes |
| default (no profile) | `api` and `worker` together, for dev and tests | yes |

Both long-running runtimes include `replicaState` in the readiness group. The worker is the only runtime that rebuilds
the replica, so on a fresh database `api` stays `DOWN` until the worker has finished one full rebuild.

## Contexts

Four business contexts plus a replica:

| Context | Owns |
|---|---|
| `platform` | A replica of the Platform data it enforces: `tenants`, `tenant_domains`, `tenant_systems`, `user_grants`, `app_entitlements`, plus the read ports `TenantDirectory` and `UserDirectory`. Nothing else reaches these tables |
| `authoring` | Surveys, drafts with optimistic concurrency, immutable versions with stable question and option ids, translations, the expression engine, the flow evaluator, preview and simulate |
| `collection` | Links and their tokens, visits and page events, drafts and submissions, the typed `answers` rows, uploads, the response cap, single use, idempotency and deletion |
| `insight` | Aggregates, cross-tabs, trends, the funnel, respondents and CSV exports. It writes only its own `exports` table |
| `webhook` | Webhook endpoints, deliveries, the event-type catalog, and the claim and deliver use cases |

`collection` and `insight` reach `authoring` only through its `api` package, `insight` reaches `collection` only
through its `api` package, and ArchUnit fails the build when a context names another's tables. Deletion lives in
`collection`, because it owns the rows; `insight` calls it rather than writing a response itself.

## Broker items

| Item | Value |
|---|---|
| Consumes | `platform.events` on `survey.platform-events` (the five state-transfer keys) |
| Dead-letters | to `suite.dlx`, drained by `survey.platform-events.dlq` |
| Publishes | `survey-version.published`, `survey.closed` and `survey-response.submitted` to `survey.events` through the transactional outbox |
| Declares | `survey.events`; it has no consumer today, and exists so a future app can react to a response without Survey changing |

Every message uses envelope `schemaVersion` 2. The payload schemas are in
[contracts/events/survey](../contracts/events/survey/).

## The version document

A published version is one JSONB document plus a `version_no`:
[contracts/survey/version.schema.json](../contracts/survey/version.schema.json). It holds settings (default and enabled
locales, window, response cap, quiz on/off and whether the score is returned, abandonment threshold, time zone),
pages with `skip_if` and ordered `jumps`, questions with `visible_if` and type-specific constraints, options with a
value and a score, matrix rows and columns, computed variables in declared order, scoring rules, and the translations.

- **Ids are stable.** Question, option, matrix row and column ids are server-assigned UUIDs that persist across
  versions, so an aggregate can group the same question over v1 and v2. A question whose `type` changes is given a
  new id at publish and records `replaces` in `question_lineage`; the analytics show it as its own question with a
  link to its predecessor. A client must keep the ids it is given: dropping them makes every publish create new
  questions.
- **Translations belong to the version.** Every enabled locale must carry every key — survey title, page title,
  question label and help, option, row and column labels — and a missing key fails the publish with
  `translation_missing` at that pointer. Fixing a translation is a new version.
- **Versions are immutable.** No update or delete statement targets `survey_versions`, and the application role holds
  only `select, insert` on it.
- **The draft stays editable after a publish**, with optimistic concurrency: `PUT /draft` requires `If-Match` and
  answers `412` on a stale tag, `428` without one.

## The expression language

One JSON AST, one closed operator set, one set of test vectors:
[contracts/survey/](../contracts/survey/README.md). `visible_if`, `skip_if`, a jump's `when`, a computed variable, a
scoring rule and an analytics `where` are all the same document, so a future fill UI evaluating logic in the browser
cannot disagree with the server about a branch or a score — both must pass `contracts/survey/expression-vectors/*.json`.

The properties that matter in review:

- **Numbers are decimals.** Every arithmetic result is rounded to 6 fraction digits with `HALF_EVEN`, and a comparison
  is exact on the rounded values. Java uses `BigDecimal` and compares with `compareTo`, so `0.1 + 0.2` is `0.3`.
- **Evaluation never throws on data.** `div` by zero is `null`, arithmetic with a `null` operand is `null`, a type
  mismatch is `null`, and any comparison involving `null` is `false`. A survey is data, not a program that can fail.
- **The parser is the gate.** Unknown operators, wrong arity, depth over 16, more than 500 nodes in one expression, an
  array literal over 100 items, more than 2,000 nodes in a version, a reference to an unknown question, a variable
  referenced before it is declared and a cycle are all rejected at draft save and again at publish, each with a
  pointer and a code.

## The flow

Given a version and a set of answers the server computes a `FlowResult`:

| Member | Meaning |
|---|---|
| `path` | The page ids visited, in order. A page is skipped when `skip_if` holds; after a page is visited its first matching jump redirects, and `to_page: "end"` stops the survey |
| `visible` | Per question: on the path and its `visible_if` holds. An absent `visible_if` is true |
| `vars` | Each computed variable, evaluated in declared order after all answers |
| `score` | The sum of option, matrix column, rating and NPS scores plus every scoring rule whose condition holds, over questions on the path — or `null` when the version disables quiz scoring |
| `errors` | The structural errors a draft or version fails at |

The same evaluator answers `POST /surveys/{id}/draft/simulate` and `.../versions/{no}/simulate`, and a submit runs it
over the submitted answers. Simulate on a half-built draft still answers: a document that does not compile reports its
`errors` by pointer instead of failing the request, so a builder sees the same pointers a publish would refuse.

`POST /surveys/{id}/draft/preview?locale=` is presentation, not evaluation: it renders every page in order with its
translated labels and leaves piping as a placeholder naming what the token will show (`{{answer:<id>}}`,
`{{var:<name>}}`), because no respondent has answered yet. It never skips or hides a page — that is what simulate is
for.

## Answers, and why they are stored twice

A completed response keeps the submitted JSONB verbatim, so a display or an export shows exactly what the respondent
sent, including a choice whose option was later relabelled. Beside it, a typed `answers` table holds one row per
question, option or matrix cell (`num_value`, `text_value`, `date_value`), which is what every aggregate, filter,
cross-tab, trend and funnel reads. The JSONB is the record; the typed rows are the index.

Answers to a question that is not on the path are rejected at submit with `answer_not_on_path`. A required question
on the path must be answered; one behind a false `visible_if` or on a skipped page is not required. A client-sent
`vars` or `score` is ignored: the server recomputes both and trusts neither.

## Limits under concurrency

Three facts are enforced by the database rather than by a check-then-write:

- **The response cap** is a conditional update, `update survey_counters set completed = completed + 1 where survey_id
  = ? and (cap is null or completed < cap)`. Zero rows updated means `409 cap_reached`, so fifty concurrent submits
  against a cap of ten complete exactly ten.
- **Single use** is a partial unique index, `unique (personal_link_id) where status = 'COMPLETED'`, so a personal
  link cannot complete twice even if two submits race.
- **Idempotency** is `unique (tenant_id, idempotency_key)`: a repeated `Idempotency-Key` returns the first result
  rather than creating a second response.

## Links, visits and the funnel

A *public* link is one per Tenant-named source; a *personal* link is issued in bulk for one `external_ref` and may
carry an expiry and single use. A link token is HMAC-signed (`link_id ‖ key_id ‖ HMAC-SHA256`), so a link's URL can
be read back at any time and revoked by status. Every invalid, revoked, expired or foreign-tenant token answers the
same `404` body.

Opening a link creates a *visit* and returns an opaque visit token (stored only as its SHA-256). The visit records
the link's source, bounded UTM parameters, the referrer's registrable domain only, a device class and browser family,
and the GeoIP country. The client IP is used in memory to compute the country and never stored or logged.

The funnel is built from visits:

```
opened ──first draft save──▶ started ──submit──▶ completed
   └────no activity for abandon_after────▶ abandoned
```

`opened`, `started` and `completed` come from server-side facts. **Only the abandoned visit's last page view depends
on the client** sending `POST /p/v/{token}/pages/{pageId}/view`, so abandonment is approximated by the last page a
browser reported; a visit with no page events is abandoned *on its first page*. Bot visits are marked `bot` and
excluded from every funnel count.

## Analytics

Every analytics body takes an optional `where` in the same expression language, restricted to answer references and
comparison and boolean operators; it compiles to parameterised SQL over `answers` rather than to string-built SQL.
On top of that filter:

| Endpoint | Answers |
|---|---|
| `aggregate` | Per question type: counts per option, mean, median and distribution, NPS (promoters 9-10, detractors 0-6), per-cell matrix counts, response count for text and file. Grouped by the stable question id across versions |
| `crosstab` | Two choice-like questions — choice, rating, NPS bucket, source, country — with counts and row percentages, at most 50 × 50 cells |
| `trend` | Count, mean or NPS by day or week over `submitted_at`, in the survey's time zone |
| `funnel` | Opened, started, completed and abandoned, plus the last page for abandoned visits, grouped by `source`, `country` or `device_class` |

The cell cap and the indexes on `answers` are the v1 bound on cost; a survey large enough to make a cross-tab slow is
the first thing to measure and the first thing to narrow.

## Files

A file question presigns a PUT to MinIO/S3 with the AWS SDK v2, fixing `Content-Length` and `Content-Type`; the URL
names the host a respondent's browser reaches (`SURVEY_S3_PUBLIC_ENDPOINT`), which is not necessarily the host the
server uses. At submit the server `HEAD`s each referenced object (the size must equal the declared size and fit the
question's limit) and reads its first 512 bytes to match magic bytes against the question's allowed types, then marks
the upload `ATTACHED`. A renamed `.exe` declared as `image/png` fails that check. Object keys are
`tenant/{tenant_id}/survey/{survey_id}/{upload_id}`; a filename is never used as a key.

Downloads go only through a presigned GET with `Content-Disposition: attachment`. Pending uploads older than 24 hours
are deleted, together with their objects, by the worker's housekeeping job. There is no virus scan: that is a
non-goal, and the magic-byte check is the bound the suite accepted.

## GeoIP

The country comes from the DB-IP Lite country database (`.mmdb`, CC BY 4.0 — **attribution is required**), read from
`SURVEY_GEOIP_PATH` with `com.maxmind.db:maxmind-db`. The file is downloaded by `ops/geoip/fetch.sh`, is git-ignored
and is never committed. A missing file is not an error: `country` stays `null`, and the health detail says the file
is absent. The health detail also carries the database's build date, so a stale file is visible rather than silent.

## Rate limits

A public endpoint is guarded by two token buckets: one keyed by an HMAC of the client IP with a daily-rotated key,
and one per survey. Both live in memory, in a size-bounded map. `429` carries `Retry-After`. With N instances the
effective limit is N times the setting — the limit is published as a per-instance figure rather than a cluster one,
because a shared counter would need a store Survey deliberately does not have. Every public endpoint also refuses a
body over 256 KiB, and a visit accepts at most 200 page events.

## Isolation

Every table is tenant scoped and protected by row-level security; the tenant always comes from the verified host,
never from client input. A platform-scope caller is refused on every survey endpoint. The public `/p/**` surface
carries no suite token at all: it resolves its tenant from the host alone, and answers the same `404` for every
invalid token so it teaches nothing about which identifiers exist.

## Deletion

`DELETE /responses/{id}` is a hard delete: the response, its `answers` rows and its upload objects are removed, the
visit is kept with its source and device facts and its `response_id` nulled, and the cap is **not** decremented,
because the cap counts submissions. There is no retention schedule: Survey deletes when asked, and a Tenant that must
retain nothing deletes the response. A survey closed and emptied leaves its visits, which carry no personal data.

## API surface

Every tenant endpoint is under `/survey/api/v1` on the client's side (the proxy strips `/survey`, and the controller
serves `/api/v1`). JSON field names are camelCase; database columns are snake_case and never appear on the wire. The
committed contract is [contracts/openapi/survey.yaml](../contracts/openapi/survey.yaml).

| Resource | Endpoints |
|---|---|
| Surveys | `GET/POST /surveys`, `GET /surveys/{id}`, `POST /surveys/{id}/close`, `POST /surveys/{id}/reopen` |
| Draft | `GET/PUT /surveys/{id}/draft`, `POST /surveys/{id}/draft/validate`, `POST /surveys/{id}/draft/preview`, `POST /surveys/{id}/draft/simulate` |
| Versions | `POST /surveys/{id}/publish`, `GET /surveys/{id}/versions`, `GET /surveys/{id}/versions/{no}`, `POST /surveys/{id}/versions/{no}/simulate` |
| Links | `POST /surveys/{id}/links`, `POST /surveys/{id}/links/personal`, `GET /surveys/{id}/links`, `GET /links/{id}`, `POST /links/{id}/revoke` |
| Responses | `GET /surveys/{id}/responses`, `GET /responses/{id}`, `DELETE /responses/{id}`, `GET /responses/{id}/files/{uploadId}` |
| Analytics | `POST /surveys/{id}/analytics/aggregate`, `POST /surveys/{id}/analytics/crosstab`, `POST /surveys/{id}/analytics/trend`, `GET /surveys/{id}/analytics/funnel` |
| Respondents | `GET /surveys/{id}/respondents` |
| Exports | `POST /surveys/{id}/exports`, `GET /exports/{id}` |
| Webhooks | `GET/POST /webhook-endpoints`, `GET/PUT/DELETE /webhook-endpoints/{id}`, `POST /webhook-endpoints/{id}/rotate-secret`, `GET /webhook-deliveries`, `POST /webhook-deliveries/{id}/retry` |
| Public | `POST /p/l/{token}/open`, `GET /p/v/{visitToken}/schema`, `PUT /p/v/{visitToken}/draft`, `GET /p/v/{visitToken}/draft`, `POST /p/v/{visitToken}/pages/{pageId}/view`, `POST /p/v/{visitToken}/uploads`, `POST /p/v/{visitToken}/submit` |

A missing resource in the caller's tenant is `404`, a stale `If-Match` is `412`, a duplicate public source is `409`,
and a submit past the cap or on a completed personal link is `409`.

## Webhooks

A Tenant System registers an endpoint and receives signed deliveries of the three events, built on the shared
`core-webhook` mechanics Billing also uses. Tables and delivery state stay in Survey; only signing, the destination
policy, the guarded DNS resolver, the HTTP sender and the retry schedule are shared.

| Header | Meaning |
|---|---|
| `Survey-Event-Id` | The event id (same as `id`). **The deduplication key.** |
| `Survey-Event-Type` | The event type. |
| `Survey-Timestamp` | Unix time in seconds when this attempt was signed. |
| `Survey-Key-Id` | Identifies which signing secret signed this delivery. |
| `Survey-Signature` | `v1=` followed by the lowercase hex HMAC-SHA256 of `timestamp + "." + rawBody`. |
| `Survey-Attempt` | 1 for the first attempt, then 2, 3, and so on. |

The signing secret is shown once, on create and on `rotate-secret`. Success is any 2xx; failures retry with
exponential backoff and jitter, and a delivery that exhausts its budget becomes `DEAD`, stays visible and can be
retried. A Tenant-chosen URL is untrusted: non-public destinations are refused, and the check happens inside the
connection's own DNS resolution. The verification recipe is in
[webhooks-and-events.md](webhooks-and-events.md).

## Configuration

| Variable | Meaning |
|---|---|
| `SURVEY_DB_URL`, `SURVEY_DB_USER`, `SURVEY_DB_PASSWORD` | The application connection; `survey_app` owns nothing and row-level security applies to it |
| `SURVEY_MIGRATION_DB_USER`, `SURVEY_MIGRATION_DB_PASSWORD` | Flyway, as the table owner |
| `SURVEY_RABBITMQ_HOST`, `SURVEY_RABBITMQ_USER`, `SURVEY_RABBITMQ_PASSWORD` | The broker user: write `survey.events`, read its own queues, nothing else |
| `SURVEY_OIDC_ISSUER_URI`, `SURVEY_OIDC_JWK_SET_URI` | The user-token issuer; the suite token is verified against Platform's JWKS |
| `SURVEY_PLATFORM_BASE_URL`, `SURVEY_PLATFORM_ISSUER`, `SURVEY_PLATFORM_JWK_SET_URI` | The replica's snapshot source and token verifier |
| `PLATFORM_SURVEY_SERVICE_CLIENT_ID`, `PLATFORM_SURVEY_SERVICE_CLIENT_SECRET` | The replica's own client; only its SHA-256 is configured in Platform |
| `SURVEY_LINK_KEY_ID`, `SURVEY_LINK_SECRET`, `SURVEY_PUBLIC_BASE_URL` | The HMAC key that signs link tokens and the public prefix a respondent reaches. There is no default secret: an empty one fails startup |
| `SURVEY_GEOIP_PATH` | The DB-IP Lite `.mmdb` file; absent is allowed and leaves `country` null |
| `SURVEY_S3_ENDPOINT`, `SURVEY_S3_PUBLIC_ENDPOINT`, `SURVEY_S3_BUCKET` and credentials | The server's S3 endpoint, the host a browser reaches for presigned URLs, the bucket and the credentials |
| `SURVEY_JOBS_ENABLED`, `SURVEY_HOUSEKEEPING_ENABLED` | Switches; neither drops queued work |

A link key rotates without breaking links already handed out: add a second entry under `survey.link.keys`, make the
new key id `active-key-id`, and keep the old one until the links carrying it are no longer opened.

## Runbook

| Symptom | Check |
|---|---|
| A link answers `404` and should not | The survey is `OPEN`, inside its window, and the token verifies with a configured key; a revoked or expired link answers the same `404` by design |
| A submit answers `409 cap_reached` | `survey_counters.completed` has reached the version's `response_cap`; raise the cap by publishing a new version |
| A submit answers `409 already_completed` | The personal link already has a completed response; the partial unique index is the enforcement |
| Aggregates look empty for a new version | Every aggregate groups by the stable question id; a question whose type changed has a new id and its own series, linked by `replaces` |
| A webhook is not delivered | The endpoint is enabled and subscribed to the event type; a `DEAD` delivery is visible in `GET /webhook-deliveries` and retryable |
| A webhook is refused with a blocked-destination error | The endpoint URL resolves to a non-public address; the guarded resolver is working as intended |
| `country` is always null | `SURVEY_GEOIP_PATH` is unset or the file is missing; the health detail says which |
| Uploads never reach `ATTACHED` | The size or the magic bytes did not match the question's constraints; a mismatched upload stays `PENDING` and is deleted after 24 hours |
| The service refuses to start with a blank link key | `SURVEY_LINK_SECRET` is unset; there is deliberately no default |

Nothing in the log contains an answer, an address, a token or an IP.

## Resend recipe: invite the respondents who have not answered yet

Survey does not send, by design. A Tenant that invited people through mailroom and wants to remind the ones who have
not answered does it with mailroom's groups and segments, with no change in either app:

1. Read the list of people who already answered:
   `GET /survey/api/v1/surveys/{id}/respondents?state=COMPLETED`. Each row carries `respondentIdentity` and
   `externalRef`, which are the Tenant's own customer keys.
2. Create a mailroom group for this survey, for example `responded-<survey-id>`, and add those keys as members with
   `POST /mailroom/api/v1/groups/{groupId}/members`. A personal link's `externalRef` is the value to use.
3. Send the reminder campaign to a segment that excludes them:
   `in_group(invited) and not in_group(responded-<survey-id>)`, with the campaign template carrying the
   `SURVEY_PUBLIC_BASE_URL` link or a per-recipient personal link.
4. Re-run steps 1 and 2 before each reminder. The respondents list is the live answer set, so a reminder only ever
   reaches someone who has not completed the survey.

The funnel gives the same picture at a glance: `opened`, `started` and `completed` per source.

## Related

The version and expression contracts: [contracts/survey/README.md](../contracts/survey/README.md). The permission
catalog: [contracts/permissions/survey.yaml](../contracts/permissions/survey.yaml). Events:
[contracts/events/survey/](../contracts/events/survey/). Webhook mechanics: [webhooks-and-events.md](webhooks-and-events.md).
The broker: [suite-messaging.md](suite-messaging.md). The app that can send a Tenant's mail:
[mailroom.md](mailroom.md).

# Attribution contracts

Two document sets, shared by the attribution server and anything that reports to it or reads it: the attribution
vectors the credit engine must reproduce, and the ingest schemas of the payloads that travel over the broker.

| File | Owns |
|---|---|
| `attribution-vectors/vector.schema.json` | The shape of one vector case: a model, a window, a currency, a conversion, a touch chain and the expected credits |
| `attribution-vectors/*.json` | The cases themselves, split by subject. The server's engine test is generated from these files; adding a model behaviour means adding a case, never a test in the code |
| `ingest/click.schema.json` | One click produced by the redirect, as it travels to the ingest queue |

`contracts/README.md` lists this folder with the other contracts, and `contracts/permissions/attribution.yaml` is the
app's permission catalog.

## The vectors

Every file in `attribution-vectors/` is a JSON array of cases. Each case states everything the credit engine needs and
the exact credits a reviewer would defend, so the engine is written against the file rather than the file being
generated from the engine.

| Member | Meaning |
|---|---|
| `name` | What the case proves, phrased as an assertion |
| `model` | `FIRST`, `LAST` or `LINEAR`, chosen per campaign |
| `window_days` | The owning campaign's window, 1 to 90 days. A click is inside it when it is not after the conversion and no more than this many 24-hour periods before `conversion.at` |
| `currency` | The ISO 4217 code that decides the fraction digits: 2 for `USD`, 0 for `VND` and `JPY` |
| `conversion` | `at` in UTC and `value` as a decimal string, never a binary float |
| `chain` | The touchpoints, oldest first. A chain may span campaigns and may contain `BOT` or `SUSPECT` clicks |
| `expect` | The credits, one per credited click, in chain order, as decimal strings at the currency's fraction digits. An empty list means the conversion is `UNATTRIBUTED` |
| `reason` | Optional, and only where an empty `expect` needs an explanation: `no_chain`, `all_clicks_outside_window` or `bot_only` |

Two rules make a case decidable:

- **Only human clicks count.** A `BOT` click (a crawler or a link-preview fetcher) and a `SUSPECT` click (a visitor over
  the per-link rate) are filtered out before the model runs, so a chain of bots alone is unattributed.
- **The credits sum exactly to the value.** Linear credit uses largest-remainder rounding at the currency's fraction
  digits and puts the residual on the last touch, so `100.00` over three touches is `33.33, 33.33, 33.34` and `100` in
  a zero-digit currency is `33, 33, 34`.

The seven files are split by subject: `first-click`, `last-click`, `linear-even`, `linear-rounding`,
`window-excludes-old-click`, `single-click-chain` and `unattributed`. A chain of one is what a conversion looks like
without a visitor id, which is why the three models agree there.

## The ingest schemas

The redirect does not write to a database. It resolves the link, answers `302`, and offers a payload to a bounded
buffer; a publisher sends batches to `attribution.ingest`, and the worker consumes them. `ingest/click.schema.json`
describes the click payload. The page-view, visitor-bound and goal payloads are added by the phases that produce
them.

Every payload carries no raw IP and no user agent string: the redirect has already classified the request into a
device class, a browser family, a country, a traffic class and a daily visitor hash, and those are all the stored row
can say about the person. `event_id` makes the store idempotent, and `tenant_id` lets the worker group a batch by
tenant so each group is inserted in its own row-level-security scope.

## Adding a case

1. Append it to the file whose subject it belongs to: the model, the window, or the rounding.
2. State the credits a reviewer would defend, not the number the implementation happens to produce.
3. Keep the credits summing to the value at the currency's fraction digits, and keep them in chain order.
4. Run the server's vector test; it fails when a case is not satisfied and names it.

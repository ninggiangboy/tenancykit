# List query contract

Every list endpoint of the suite filters, sorts and pages the same way, so a Tenant System learns one syntax. The
parsing, validation and cursor live in `core/core-query`; the jOOQ compiler, the Spring argument resolver and the index
check live in `core/core-spring`; the OpenAPI generation lives in `core/core-openapi`. Each resource declares only
*what* may be filtered and sorted, in a schema.

The OpenAPI document of every migrated endpoint is generated from that schema, so it is the complete reference for the
fields and operators of one resource. Each service commits its generated document and compares it with the code in a
contract test; regenerate it with `-Dopenapi.update=true`. A service mounted behind a path prefix (mailroom, survey,
attribution) carries that prefix in the document's server URL rather than in every path.
`contracts/openapi/billing.yaml` is the Billing one.

## Request

| Part | Syntax |
|---|---|
| Filter | `filter[<field>][<op>]=<value>`; all filters are combined with AND |
| OR | `filter[or][<i>][<field>][<op>]=<value>`; predicates sharing `i` are AND-ed, the branches are OR-ed, and the whole group is AND-ed with the plain filters |
| Sort | `sort=-created_at,total`: sortable fields, `-` for descending, at most 4 |
| Page size | `limit`, default 50, at most 200 (a larger value is lowered to 200) |
| Position | `offset` (at most 10 000) **or** `cursor`; sending both is a 400 |
| Paging facts | `page_info=true` for every fact, `page_info=cursor` for the cursor alone |

Example, the open invoices of one customer that are either large or recent, largest first:

```
GET /api/v1/invoices
  ?filter[customer_id][eq]=0193...
  &filter[status][eq]=OPEN
  &filter[or][0][total][gte]=100000
  &filter[or][1][created_at][gte]=2026-11-01T00:00:00Z
  &sort=-total&limit=50&page_info=true
```

Clients send the brackets as they are; the API profile of each service sets
`server.tomcat.relaxed-query-chars: "[,]"` so Tomcat accepts them. A client may also percent-encode them.

### Operators

| Operator | Meaning | Applies to |
|---|---|---|
| `eq`, `neq` | equal, not equal | every type |
| `in`, `nin` | in, not in a comma-separated list of up to 100 values | string, uuid, enum, long, decimal, instant |
| `gt`, `gte`, `lt`, `lte` | ordering | long, decimal, instant |
| `is_null` | `true` or `false` | only a field declared nullable |
| `starts_with`, `contains` | case-insensitive text match; `%`, `_` and `\` are escaped | string, and only where the field declares it |

`neq` and `nin` follow SQL: they do not match rows where the field is null. A field offers the operators its schema
lists, not every operator its type could take; the OpenAPI parameters list exactly those.

### Types

| Type | Value |
|---|---|
| string | text of at most 255 characters |
| uuid | a UUID |
| enum | one of the declared values, exactly as written |
| long, decimal | a whole or decimal number |
| boolean | `true` or `false` |
| instant | ISO-8601 with an offset, such as `2026-11-16T10:15:30Z` |

### Limits

20 predicates in all, 100 values in one `in`/`nin`, 10 OR branches, 4 sort fields, offset 10 000, limit 200. A request
beyond a limit is a 400.

## Response

The body is always one shape, so a client declares one type per resource:

```json
{ "data": [ ... ], "page": null }
```

`page` is `null` unless the caller asks for paging facts, and then it is

```json
{ "limit": 50, "offset": null, "total": 1342, "next_cursor": "eyJ..." }
```

with `page_info=true`. `page_info=cursor` answers the same shape without counting the matching rows, so `total` is
`null` and no `count` query runs:

```json
{ "limit": 50, "offset": null, "total": null, "next_cursor": "eyJ..." }
```

- `total` counts every row matching the filter, wherever the page starts. It is `null` in `cursor` mode.
- `next_cursor` is `null` on the last page. Pass it as `cursor`, with the same filter and sort, to read the next page.
  A cursor is opaque. It is refused with a different filter or sort, and when it is malformed; it is not signed, because
  a forged one only moves where a read starts inside rows the caller may already see.
- `offset` is the number of rows skipped, or `null` when a cursor positioned the page. A client that walks a whole
  listing should use the cursor and `page_info=cursor`: an offset page can skip or repeat rows when data changes
  between calls and gets slower as it deepens, and every page of a walk costs a count when `total` is asked for too.

## Errors

A request the contract does not allow is a 400 with a `detail` that names the field, the operator or the limit:

| Request | Answer |
|---|---|
| a field that is not whitelisted (an internal column, a Stripe identifier) | `the field void_reason cannot be filtered; filterable fields: ...` |
| an operator the field does not offer | `the operator contains does not apply to field total; allowed: ...` |
| a value of the wrong type | `the field total takes a whole number, not 'lots'` |
| a sort on a field that is not sortable | `the field settlement cannot be sorted; sortable fields: ...` |
| a parameter the listing does not know, such as an old `before` | `the parameter before is not supported on this listing` |

An unknown parameter is refused rather than ignored, because a silently dropped filter would return more rows than the
caller asked for.

## Declaring a listing

A resource states its contract once and the rest follows:

1. **Schema** (application layer, plain Java): a class with `public static final FilterSchema SCHEMA`. Each field
   names its API name, type, operators, whether it is sortable and whether it is nullable. A field of a related table
   is written `relation.column` and can be filtered but never sorted. The schema names a tie-breaker (the unique
   field appended to every sort, normally `id`) and a default sort.
2. **Binding** (persistence adapter): `ListBinding.of(SCHEMA).column(name, JOOQ_COLUMN)...build()`. A related field
   also gives the function that wraps its predicate into `exists (select 1 from related where link and ...)`, so a
   relation never multiplies rows. `build()` fails when a schema field has no binding, a binding names no schema
   field, or a column's nullability differs from the field's.
3. **Read** (persistence adapter): `JooqLists.fetch(dsl, TABLE, scope, binding, query, mapper)`. `scope` is the
   condition the caller always gets, such as the customers a Tenant System may see; tenant isolation still comes from
   row-level security. The page is fetched with one extra row to learn whether another exists.
4. **Controller**: annotate the method with `@Listing(Schema.class)` and take a `ListQuery` parameter; return
   `Page<Response>` built with `Page.map`.
5. **Web wiring** (once per service): add `ListQueryResolver` as an argument resolver and `ListingOpenApiCustomizer`
   as an `OperationCustomizer`.
6. **Indexes**: a sortable field leads a full btree index of its table, and a `contains` field has a full GIN
   `gin_trgm_ops` index. A test calls `ListingIndexCoverage.missing(dsl, binding)` against the migrated database and
   fails when a field is not covered.

A sortable field must be `NOT NULL`, because a cursor cannot position itself on a null; the schema refuses a nullable
sortable field. Keyset paging is written out as `k1 > v1 or (k1 = v1 and (k2 > v2 ...))` with the direction of each key,
so mixed ascending and descending keys work.

## Where it applies

| Service | Listings on the contract | Still on their own parameters |
|---|---|---|
| Billing | customers, wallets, ledger, charges, reservations, payments, refunds, invoices, subscriptions, usage events, coupons, promotion codes, redemptions, webhook endpoints and deliveries, Stripe events, incidents, pricing plans, and every portal list | usage aggregates (a report, not a row list); the internal replica snapshot |
| Mailroom | templates, template revisions, campaigns, emails, receivers, group members, groups, segments, suppressions | public tracking and the single-object reads |
| Survey | surveys, versions, links, responses, respondents, exports, webhook endpoints and deliveries | analytics summaries and the public visit |
| Attribution | links, campaigns, goals, conversions | ingest (collect and redirect), settings |
| Notification | notifications (the caller's inbox), email dispatches | the template list, which is derived from the notification-type enum |
| Platform | tenants, users, roles, tenant systems, credentials | `/me`, JWKS and the internal replica snapshot |

A service's table grows as each phase of `plans/261116-1657-list-query-contract` lands; when a service migrates, this
table is updated and its old parameters are removed with no alias.

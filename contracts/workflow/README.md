# Workflow contracts

The documents that fix what a workflow definition and a workflow expression mean, shared by the workflow app and any
tool that reads or writes them.

| File | Owns |
|---|---|
| `graph.schema.json` | One workflow graph: its nodes, their types and settings, and its edges |
| `expression.schema.json` | The expression language: literals, references and the closed operator set |
| `expression-vectors/*.json` | The behaviours the evaluator must reproduce; the app's tests are generated from these files |
| `graph-vectors/*.json` | Graphs a checker must accept, and graphs it must reject with a stable pointer and code |

`contracts/README.md` lists this folder with the other contracts.

## The suite rule applied to workflow

Workflow is not an app the suite reasons about internally; it is the Tenant side of the suite rule. A Tenant
describes a flow as a workflow, and the engine acts as that Tenant's own system: it calls apps over their public API
with a Tenant System credential, receives their signed webhooks as triggers, and binds to no app's exchange. The rule
is written once in [contracts/messaging/README.md](../messaging/README.md#the-suite-rule) and linked from the other
documents instead of repeated.

## Number semantics

Copied from Survey, which owns the same semantics: every number is a decimal, never a binary float. Arithmetic rounds
its result to 6 fraction digits with `HALF_EVEN`; a comparison is exact on the rounded values; a number travels as a
JSON number with at most 6 fraction digits. Java uses `BigDecimal` and never `equals` — two equal values with
different scales are equal, so comparison is `compareTo`.

`div` by zero is `null`, and so is any arithmetic with a `null` operand. Type mismatches follow the same rule: an
operator given a value of the wrong type produces `null` rather than throwing, so evaluation never fails on data. A
comparison with `null` is `false`, on both sides.

## The expression language

One JSON AST, and a closed operator set: comparison, `in`, `contains`, `and`, `or`, `not`, `exists`, `empty`, `count`,
`sum`, `add`, `sub`, `mul`, `div`, `min`, `max`, `if`, `len`, `lower`, `concat` and `coalesce`. There is no function
call, no regular expression, and no access to the host, the clock or the network: time comes from nodes, never from an
expression.

A reference names where a value comes from: `{"input": "<path>"}` reads the trigger or manual payload,
`{"step": "<nodeId>", "path": "<path>"}` reads a finished step's output, `{"var": "<name>"}` reads a workflow variable
set by a `set` on a node, and inside a for-each `{"item": "<path>"}` and `{"index": true}` read the current item and
its index. A path is a closed dotted subset (`a.b.0.c`) with no wildcards and no filters.

Limits, enforced when a definition is saved and published: depth ≤ 16, ≤ 500 nodes per expression, array literal
≤ 100 items, and ≤ 2,000 expression nodes in one version. Depth and node counting follow the definitions in
[contracts/survey/README.md](../survey/README.md#the-expression-language).

## Vector format

Every file in `expression-vectors/` is a JSON array of cases. Each case has a `name` phrased as an assertion, an
`environment` holding the `input`, `steps`, `vars`, `item` and `index` the expression sees, the `expr` under test, and
`expected` with either a `value` or `errors`. Every operator has at least a normal case, a `null` case and a
type-mismatch case, and every reference form has its own case.

Every file in `graph-vectors/` is a JSON array of cases with a `name`, a complete `graph` and `expected`: no errors for
an accepted graph, or the list of `{"pointer": "...", "code": "..."}` entries a rejected graph must produce.

## Adding a case

1. Append it to the file whose subject it belongs to, keeping the graph or environment complete.
2. State the expectation you would defend in review, not the value the implementation happens to produce.
3. Run the app's vector test; it fails when a case is not satisfied and names it.

---
phase: 4
title: "Expression language and vectors"
status: pending
priority: P1
effort: "M"
dependencies: [3]
---

# Phase 4: Expression Language and Vectors

## Goal

Workflows can test and compute with a closed, side-effect-free expression language whose behaviour is fixed by shared test
vectors, evaluated by a pure-Java evaluator inside `workflow/`.

## Context

User decision: copy Survey's semantics, never import it and never edit Survey. Source to read first:
`contracts/survey/README.md` (operator table, number semantics, depth and node limits) and
`survey/contexts/authoring/src/domain/java/dev/ngb/survey/authoring/domain/expression/{Evaluator,Expr,ExprParser,ExprChecker,
Limits,Operator,Value}.java`, with `contracts/survey/expression-vectors/*.json` for the vector style. Differences for
workflow: references are `{"input": "<path>"}` (the trigger or manual payload), `{"step": "<nodeId>", "path": "<path>"}` (a
finished step's output), `{"var": "<name>"}` (a workflow variable set by `set` on a node), `{"item": "<path>"}` and
`{"index": true}` inside a for-each, and `{"now": true}` is NOT supported (no clock in expressions: time comes from nodes).
Paths are a closed dotted subset (`a.b.0.c`), no wildcards or filters.

## Files to create

- `contracts/workflow/expression.schema.json`, `contracts/workflow/expression-vectors/{arithmetic,logic,collections,text,
  references,limits}.json`
- `workflow/contexts/definitions/src/domain/.../expression/{Expr,ExprParser,ExprChecker,Evaluator,Value,Operator,Limits}.java`
- Tests: `ExpressionVectorTest` generated from every vector file, plus `ExprCheckerTest`

## Tasks

1. Define the operator set: the Survey list (comparison, `in`, `contains`, `and`, `or`, `not`, `exists`, `empty`, `count`,
   `sum`, `add`, `sub`, `mul`, `div`, `min`, `max`, `if`, `len`, `lower`) plus `concat` and `coalesce`, nothing else. No call,
   regex, clock, network, or host access.
2. Keep the number rule exactly: decimals, 6 fraction digits, `HALF_EVEN`, `div` by zero is `null`, a type mismatch is `null`,
   a comparison with `null` is `false`; evaluation never throws on data.
3. Limits: depth ≤ 16, ≤ 500 nodes per expression, array literal ≤ 100, ≤ 2,000 nodes per definition version.
4. Evaluate against an `Environment` (input, step outputs, vars, item, index) that the runtime fills; the evaluator has no
   dependency on the runtime or on persistence, so the simulate endpoint (phase 5) reuses it.
5. Vector format mirrors Survey's: `name`, `graph_fragment`/`environment`, `expected`; every operator has at least one vector
   for its normal case, its `null` case and its type-mismatch case. Add a vector for each reference form.

## Validation

- `ExpressionVectorTest` runs every vector and passes; deleting an evaluator branch makes a vector fail.
- `git diff --stat -- survey` is empty; no import of `dev.ngb.survey` anywhere in `workflow/`.
- An expression at the depth and node limits is accepted, one past it is rejected with a pointer and code.

## Risks

- Duplicated code diverges from Survey over time: accepted by the user; the vectors are workflow's own and are not required to
  track Survey's.

---
phase: 5
title: "Expression engine, preview and simulate"
status: done
priority: P1
effort: "L"
dependencies: [4]
---

# Phase 5: Expression Engine, Preview and Simulate

## Goal

One evaluator that decides visibility, the page path, computed variables, the score and piped text from a version and a
set of answers, proven by the shared vectors, and exposed to builders through preview and simulate.

## Files to create / modify

- Create: `survey/contexts/authoring/src/domain/java/dev/ngb/survey/authoring/domain/expression/` (`Expr` sealed
  hierarchy, `ExprParser` from JSON, `ExprChecker`, `Evaluator`, `Value`, `Limits`)
- Create: `.../domain/flow/` (`PathResolver`, `Visibility`, `ComputedVariables`, `Scorer`, `PipedText`, `FlowResult`)
- Create: `.../api/` (`SurveyFlow` facade used by `collection` and `insight`)
- Create: `survey/contexts/authoring/src/test/java/.../ExpressionVectorTest.java` (reads `contracts/survey/expression-vectors/*.json`)
- Modify: `contracts/openapi/survey.yaml`

## API

| Method and path | Permission | Notes |
|---|---|---|
| `POST /surveys/{id}/draft/preview?locale=` | `survey:survey:read` | The draft rendered for one locale, pages in order, piping shown with placeholders |
| `POST /surveys/{id}/draft/simulate` | `survey:survey:read` | Body `{answers, locale}`; returns `FlowResult` |
| `POST /surveys/{id}/versions/{no}/simulate` | `survey:survey:read` | Same, against a published version |

## Tasks

1. **Parse and check.** `ExprParser` turns JSON into a typed tree and rejects unknown operators, wrong arity, depth
   > 16, nodes > 500 and array literals > 100. `ExprChecker` resolves every `answer` and `var` reference against the
   version, rejects references to later computed variables (no cycles), and plugs into phase 4's validation hook with
   pointers.
2. **Evaluate.** Pure functions over `Value` (string, number, boolean, null, list). Numbers are `BigDecimal`; every
   arithmetic result is rounded to scale 6 with `RoundingMode.HALF_EVEN`, and equality uses `compareTo`, never
   `equals`. <!-- Updated: Validation Session 1 - decimal number semantics --> Evaluation never throws on data; type mismatches follow the null rules from phase 1.
3. **Flow.** `PathResolver` walks pages in order, applies `skip_if`, then the first matching `jump`, and stops at the
   end or at a jump to `end`. A question is *on the path* when its page is visited and its `visible_if` is true (absent
   means true). Computed variables are evaluated in declared order after all answers. The score is the sum of option
   scores and scoring rules for questions on the path. `FlowResult` = `{path, visible, vars, score, errors}`.
4. **Piping.** `PipedText` renders `["Bạn chọn ", {"ref": {"answer": "q1"}}, "?"]` to text, HTML-escapes the
   substituted value, and caps the rendered string at 2,000 characters.
5. **Vectors are the tests.** `ExpressionVectorTest` is a JUnit dynamic test per vector; there is no second hand-written
   expression test set that could drift from the contract. Unit tests may cover only the parser's error messages.
6. **Budget.** A version's total node count ≤ 2,000 is checked at publish; evaluation of one submit is measured in a
   test with the largest allowed version and must finish under 20 ms on CI.

## Verification

- `cd survey && ./gradlew test --tests '*ExpressionVector*'` passes every vector file.
- Simulate on a fixture version returns the same `FlowResult` as the matching vector.
- A version with a depth-17 expression fails publish with `limit_exceeded` at the right pointer.

## Risks

- Number semantics are the most likely client/server mismatch; the vectors must include rounding and division cases.

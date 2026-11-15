# Survey contracts

Three documents and one vector set, shared by the survey server and the fill UI that does not exist yet.

| File | Owns |
|---|---|
| `version.schema.json` | One published version of a survey: settings, pages, questions, options, matrix rows and columns, computed variables, scoring rules and translations |
| `expression.schema.json` | The expression language: literals, answer and variable references, and a closed operator set |
| `expression-vectors/*.json` | The behaviours both evaluators must reproduce. The server's tests are generated from these files; a future JS evaluator passes the same files |

`contracts/README.md` lists this folder with the other contracts.

## Number semantics

Every number is a decimal, never a binary float. Arithmetic rounds its result to 6 fraction digits with
`HALF_EVEN`; a comparison is exact on the rounded values; a number travels as a JSON number with at most 6 fraction
digits. Java uses `BigDecimal` and never `equals` — two equal values with different scales are equal, so comparison
is `compareTo`.

`div` by zero is `null`, and so is any arithmetic with a `null` operand. Type mismatches follow the same rule: an
operator given a value of the wrong type produces `null` rather than throwing, so evaluation never fails on data.
A comparison with `null` is `false`, on both sides.

## The expression language

One JSON AST. The operator set is closed: there is no function call, no regular expression and no access to the
host, the clock or the network. `{"answer": "<id>"}` reads one answer, `{"answer": "<id>", "row": "<id>"}` reads one
matrix cell, and `{"var": "<name>"}` reads a computed variable declared earlier in the same version.

| Operator | Arity | Meaning |
|---|---|---|
| `eq` `neq` `gt` `gte` `lt` `lte` | 2 | Comparison. Strings compare by code point, numbers by value, booleans by identity. A comparison across two types or against `null` is `false` |
| `in` | 2 | the left value is a member of the right list; when the left is itself a list, it is true if any member is listed |
| `contains` | 2 | the left list contains the right member, or the left text contains the right substring (case sensitive) |
| `and` `or` | 2-8 | Boolean. A `null` operand is false, so `or(true, null)` is true and `or(false, null)` is false |
| `not` | 1 | Negation; `not` of `null` is true |
| `exists` | 1 | The value is present: not `null`, not `""`, not `[]`. `0` and `false` exist |
| `empty` | 1 | The opposite of `exists` for text and lists; a number is never empty |
| `count` `sum` | 1 | Over a list. `count(null)` is 0, `count` of a scalar is `null`, `sum` of a list holding a non-number is `null` |
| `add` `sub` `mul` `div` | 2 | Decimal arithmetic, rounded to 6 fraction digits |
| `min` `max` | 2-8 | Decimal minimum and maximum |
| `if` | 3 | `if(condition, then, else)`; a `null` condition takes the `else` branch |
| `len` | 1 | Length of a text (code points) or a list; `null` of `null` and of a number |
| `lower` | 1 | Lower-cased text; `null` for anything that is not text |

Limits, enforced by the parser at draft save and at publish: depth ≤ 16, ≤ 500 nodes per expression, array literal
≤ 100 items, and ≤ 2,000 expression nodes in one version.

**Depth** is the number of nested operation nodes on the longest path, with a literal, `{"answer"}` or `{"var"}`
counting as depth 0. So `not(answer)` is depth 1, `not(not(answer))` is depth 2, and sixteen nested `not`s is the
deepest accepted expression. **Nodes** counts every expression node: an operation, an answer reference, a variable
reference, an array literal and a scalar literal are one node each. A `version`'s total is the sum over every
expression it carries, so a wide tree is as costly as a deep one.

## Piping

A translated string is either text or an ordered array of literal strings and `{"ref": <expression>}` tokens:

```json
["Bạn chọn ", {"ref": {"answer": "q1"}}, "?"]
```

The evaluator substitutes each token's value, HTML-escapes the substituted value, and caps the rendered string at
2,000 characters.

## Vector format

Every file in `expression-vectors/` is a JSON array. Each entry is one case:

| Member | Meaning |
|---|---|
| `name` | What the case proves, phrased as an assertion |
| `version_fragment` | A **complete** version document, so it validates against `version.schema.json`. The base is one page whose id is `11111111-1111-7111-8111-111111111111`, `default_locale` `en`, one locale `["en"]`, empty `computed`, empty `scoring`, and `translations: {"en": {"survey.title": "T"}}` |
| `answers` | The submitted answers, keyed by question id. A matrix answer is `{"<rowId>": "<columnId>"}`; a file answer is a list of upload ids |
| `expected.visible` | The visibility of **every** question in the version, keyed by question id: whether it is on the path and its `visible_if` holds |
| `expected.path` | The page ids visited, in order |
| `expected.vars` | Every computed variable's evaluated value |
| `expected.score` | The score, or `null` when the version does not enable quiz scoring |
| `expected.rendered` | Optional. A translation key whose value is a piping array, mapped to the string the evaluator must produce. Omit the member when the version pipes nothing |
| `expected.errors` | The structural errors an invalid version fails at, each `{"pointer": "/computed/0/expr", "code": "limit_exceeded"}`. An empty list means the version is valid and every other member is compared |

A `version_fragment` whose `expected.errors` is empty is validated against `version.schema.json` first, so a valid case
cannot drift from the schema. A case that expects an error is deliberately invalid in that one way and is asserted only
by the evaluator; the schema may or may not express the same limit, which is why the parser repeats the checks the
schema cannot carry.

An operator case puts the expression under test in a computed variable named `r` and reads it back through
`expected.vars.r`; that keeps every case in the same shape and proves the operator inside the real flow. A case that
expects a parse failure leaves `expected` as `visible: {}`, `path: []`, `vars: {}`, `score: null` and one pointer
in `errors`.

The seven files are split by subject, not by operator: `logic` (comparisons, booleans, jumps and skips),
`arithmetic` (decimals, rounding, `div` by zero), `text` (text operators and answers), `collections` (lists, matrix,
`count`, `sum`), `piping` (rendered text), `scoring` (quiz scoring and the cross-version question), and `limits`
(depth, node count, arity, dangling references, cycles).

## Adding a case

1. Append it to the file whose subject it belongs to; keep `version_fragment` a complete version document.
2. State the expectation you would defend in review, not the value the implementation happens to produce.
3. Run the server's vector test; it fails when a case is not satisfied and names it.

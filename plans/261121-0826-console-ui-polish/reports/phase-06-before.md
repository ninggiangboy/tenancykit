# Phase 6 checks before the fix

The seven icon, alignment and consistency checks of `13-interaction-audit.spec.ts` against the console as phase 5 left it. All seven fail. What they found:

- version rows are 57px (the active one), 53px and 52px tall instead of one height;
- detail labels and values are 2px and 4px apart on every sheet that was measured (22 rows);
- a native select draws no chevron;
- the theme menu offers no checked choice and no `menuitemradio` role, so the theme and language checks could not even select an item;
- the copy icon of an id changes the width of the text beside it on hover (the check is in the same run).

```
  ✘  1 tests/13-interaction-audit.spec.ts:674:5 › interaction audit › icons › the theme control shows the choice made, its three items have three icons and the current one is checked (1.5m)
  ✘  2 tests/13-interaction-audit.spec.ts:703:5 › interaction audit › icons › no navigation item shares an icon with another in the same app (569ms)
  ✘  3 tests/13-interaction-audit.spec.ts:721:5 › interaction audit › alignment › every version of a remote is as tall as the others and its button or badge sits on the row centre (1.1s)
  ✘  4 tests/13-interaction-audit.spec.ts:752:5 › interaction audit › alignment › the label and the value of a detail row share a baseline on single-line rows (7.2s)
  ✘  5 tests/13-interaction-audit.spec.ts:804:5 › interaction audit › one look for one control › a select in a dialog is as tall as the input beside it (1.1s)
  ✘  6 tests/13-interaction-audit.spec.ts:823:5 › interaction audit › one look for one control › hovering a copyable id moves nothing beside it (1.5s)
  ✘  7 tests/13-interaction-audit.spec.ts:847:5 › interaction audit › Vietnamese strings › with the language set to Vietnamese, no accessible name is left in English (1.5m)
    Error: row heights 57, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 53, 52
    > 746 |       expect([...heights], `row heights ${rows.map((row) => row.height).join(', ')}`).toHaveLength(1)
    Received: "billing/customers: label and value are 2px apart
    billing/customers: label and value are 2px apart
    billing/customers: label and value are 4px apart
    billing/invoices: label and value are 2px apart
    billing/invoices: label and value are 2px apart
    billing/invoices: label and value are 4px apart
    billing/invoices: label and value are 2px apart
    billing/invoices: label and value are 4px apart
    billing/wallets: label and value are 2px apart
    billing/wallets: label and value are 2px apart
    billing/wallets: label and value are 2px apart
    billing/wallets: label and value are 2px apart
    billing/wallets: label and value are 4px apart
    platform/tenants: label and value are 2px apart
    platform/tenants: label and value are 4px apart
    platform/remotes: label and value are 2px apart
    platform/remotes: label and value are 2px apart"
    Error: the select draws no chevron
    > 818 |       expect(heights.chevron, 'the select draws no chevron').toBe(true)
```

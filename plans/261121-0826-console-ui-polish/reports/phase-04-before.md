# Phase 4 checks before the fix

The six write, read and failure checks of `13-interaction-audit.spec.ts` against the console as phase 2 left it. All six fail, each on the fault it was written for: a dimmed saving switch (opacity 0.5), a button that changes width (53.7px to 75.4px while loading), a failed write shown nowhere inside its dialog, a failed sheet read that shows no sentence, a failed list with no server sentence, and no bar on a refreshing list.

```
  ✘  1 tests/13-interaction-audit.spec.ts:362:5 › interaction audit › write progress › a switch that saves keeps its colour and its size and shows a spinner (1.7s)
  ✘  2 tests/13-interaction-audit.spec.ts:396:5 › interaction audit › write progress › a button in progress keeps its width (1.7s)
  ✘  3 tests/13-interaction-audit.spec.ts:431:5 › interaction audit › write failure › a failed save says why inside the dialog and in a toast above the sheet, and the next opening is clean (16.5s)
  ✘  4 tests/13-interaction-audit.spec.ts:472:5 › interaction audit › read failure inside a sheet › a failed detail shows the sentence and Retry in the section, never the empty mark, and Retry recovers (16.1s)
  ✘  5 tests/13-interaction-audit.spec.ts:503:5 › interaction audit › list failure and refresh › a failed list says what the server said (15.3s)
  ✘  6 tests/13-interaction-audit.spec.ts:521:5 › interaction audit › list failure and refresh › a list that refreshes shows a bar within 300ms, and a fast answer shows none (3.1s)
    Error: a saving switch looks disabled
    Expected: 1
    Received: 0.5
    Error: expect(received).toBeCloseTo(expected, precision)
    Expected: 53.6986083984375
    Received: 75.359375
    Expected precision:    0
    Expected difference: < 0.5
    Received difference:   21.6607666015625
    Error: expect(locator).toBeVisible() failed
    Expected: visible
    Error: element(s) not found
    Error: expect(locator).toBeVisible() failed
    Expected: visible
    Error: element(s) not found
    Error: expect(locator).toBeVisible() failed
    Expected: visible
    Error: element(s) not found
    TimeoutError: page.waitForSelector: Timeout 1500ms exceeded.
```

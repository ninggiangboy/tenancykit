# Phase 5 checks before the fix

Search, facet and rendering-cost checks of `13-interaction-audit.spec.ts` against the console as phase 4 left it.

```
  ✘  1 tests/13-interaction-audit.spec.ts:559:5 › interaction audit › search requests and facet rows › typing a word of eight characters sends at most two list requests and ends with the whole word (1.9s)
  ✘  2 tests/13-interaction-audit.spec.ts:584:5 › interaction audit › search requests and facet rows › clicking the text of a facet option toggles it (1.5m)
PERF open-sheet {"frames":39,"worstFrameMs":33.3,"framesOver33ms":0,"framesOver50ms":0,"longTasks":0}
  ✓  3 tests/13-interaction-audit.spec.ts:610:5 › interaction audit › rendering cost › opening a record sheet (1.6s)
PERF scroll-list {"frames":55,"worstFrameMs":16.8,"framesOver33ms":0,"framesOver50ms":0,"longTasks":0}
  ✓  4 tests/13-interaction-audit.spec.ts:623:5 › interaction audit › rendering cost › scrolling a long list (1.5s)
PERF switch-theme {"frames":50,"worstFrameMs":16.8,"framesOver33ms":0,"framesOver50ms":0,"longTasks":0}
  ✓  5 tests/13-interaction-audit.spec.ts:637:5 › interaction audit › rendering cost › switching the theme (1.6s)
PERF type-search requests 8
PERF type-search {"frames":70,"worstFrameMs":16.8,"framesOver33ms":0,"framesOver50ms":0,"longTasks":0}
  ✘  6 tests/13-interaction-audit.spec.ts:650:5 › interaction audit › rendering cost › typing a search word (1.7s)
    Error: list requests: 8
    Received:    8
      - waiting for locator('[data-radix-popper-content-wrapper] label').filter({ hasText: 'Active' }).first().getByText('Active', { exact: true })
    Received:    8
      665 |       console.log(`PERF type-search requests ${requests}`)
```

Typing eight characters sends 8 list requests; the facet option's text does nothing (the row was a div without a handler). The three frame-cost checks pass before and after: they are guards for the numbers in performance-before-after.md.

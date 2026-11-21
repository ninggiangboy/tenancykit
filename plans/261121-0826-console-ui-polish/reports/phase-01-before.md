# Phase 1 and 3 checks before the fixes

Run of `tests/13-interaction-audit.spec.ts` on 2026-11-21 against the console and Platform as deployed before any change of this plan. 7 of 8 checks fail, which is the point: each one fails on the fault it was written for.

```
  ✘  1 tests/13-interaction-audit.spec.ts:74:5 › interaction audit › the frame and its scrolling › the frame is the viewport from 768px up and the page scrolls inside it, under sticky column titles (8.9s)
  ✓  2 tests/13-interaction-audit.spec.ts:139:5 › interaction audit › the frame and its scrolling › a table either fits at 768, 900, 1024 and 1280 with sticky titles, or scrolls sideways without them (34.3s)
  ✘  3 tests/13-interaction-audit.spec.ts:174:5 › interaction audit › the frame and its scrolling › the sidebar keeps the account row and the sign-out button in view (592ms)
  ✘  4 tests/13-interaction-audit.spec.ts:194:7 › interaction audit › the layer order › a menu opened from a sheet is above the sheet on desktop (1.4s)
  ✘  5 tests/13-interaction-audit.spec.ts:204:7 › interaction audit › the layer order › a toast raised while a sheet is open is above the sheet on desktop (1.8s)
  ✘  6 tests/13-interaction-audit.spec.ts:194:7 › interaction audit › the layer order › a menu opened from a sheet is above the sheet on phone (1.4s)
  ✓  7 tests/13-interaction-audit.spec.ts:204:7 › interaction audit › the layer order › a toast raised while a sheet is open is above the sheet on phone (2.2s)
  ✘  8 tests/13-interaction-audit.spec.ts:226:5 › interaction audit › the first entitlement › switching on an app a tenant never changed succeeds and stays on (22.7s)
    Error: expect(received).toBe(expected) // Object.is equality
    platform/users: the document scrolls (2925 > 520); only main may
    platform/roles: the document scrolls (2925 > 520); only main may
    billing/customers: the document scrolls (727 > 520); only main may
    billing/invoices: the document scrolls (1069 > 520); only main may
    billing/charges: the document scrolls (1195 > 520); only main may
    billing/plans: the document scrolls (675 > 520); only main may
    billing/wallets: the document scrolls (727 > 520); only main may
    billing/usage: the document scrolls (1617 > 520); only main may
    billing/webhooks: the document scrolls (582 > 520); only main may"
    > 135 |       expect(describeFaults(faults)).toBe('')
    Error: expect(received).toBeLessThanOrEqual(expected)
    Error: the menu is drawn behind the sheet
    Error: the toast is covered by the sheet
    Error: the menu is drawn behind the sheet
    Error: expect(locator).toBeChecked() failed
      - Expect "toBeChecked" getByRole('dialog').getByRole('switch').first() with timeout 15000ms
      - waiting for getByRole('dialog').getByRole('switch').first()
           - unexpected value "unchecked"
```

The phone toast check passes before the fix because on a phone the toast stands under the top bar, clear of the bottom sheet. It stays as a guard.

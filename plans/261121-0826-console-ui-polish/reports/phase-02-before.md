# Phase 2 leaving checks before the fix

Both leaving checks of `13-interaction-audit.spec.ts` against the console as phase 1 left it (sheets removed in the commit that closes them). Excerpt of the faults:

```
  ✘  1 tests/13-interaction-audit.spec.ts:270:5 › interaction audit › leaving › every record sheet leaves with its animation on Close, Escape and a click on the scrim, and gives focus back (58.4s)
  ✘  2 tests/13-interaction-audit.spec.ts:308:5 › interaction audit › leaving › every record sheet leaves by Close and by a drag down on a phone, and gives focus back (40.8s)
    platform/tenants by button: nothing moved or faded in the first 140ms
    platform/tenants by button: focus did not return to the row
    platform/tenants by escape: the sheet is gone on the first frame
    platform/tenants by escape: nothing moved or faded in the first 140ms
    platform/tenants by escape: focus did not return to the row
    platform/tenants by scrim: the sheet is gone on the first frame
    platform/tenants by scrim: nothing moved or faded in the first 140ms
    platform/tenants by scrim: focus did not return to the row
    platform/users by button: the sheet is gone on the first frame
    platform/users by button: nothing moved or faded in the first 140ms
    platform/users by button: focus did not return to the row
    platform/users by escape: the sheet is gone on the first frame
    platform/users by escape: nothing moved or faded in the first 140ms
    platform/users by escape: focus did not return to the row
    platform/users by scrim: the sheet is gone on the first frame
    platform/users by scrim: nothing moved or faded in the first 140ms
    platform/users by scrim: focus did not return to the row
    platform/roles by button: the sheet is gone on the first frame
    platform/roles by button: nothing moved or faded in the first 140ms
    platform/roles by button: focus did not return to the row
    platform/roles by escape: the sheet is gone on the first frame
    platform/roles by escape: nothing moved or faded in the first 140ms
    platform/roles by escape: focus did not return to the row
    platform/roles by scrim: the sheet is gone on the first frame
    platform/roles by scrim: nothing moved or faded in the first 140ms
    platform/roles by scrim: focus did not return to the row
    platform/remotes by button: the sheet is gone on the first frame
    platform/remotes by button: nothing moved or faded in the first 140ms
    platform/remotes by button: focus did not return to the row
    platform/remotes by escape: the sheet is gone on the first frame
    platform/remotes by escape: nothing moved or faded in the first 140ms
    platform/remotes by escape: focus did not return to the row
    platform/remotes by scrim: the sheet is gone on the first frame
    platform/remotes by scrim: nothing moved or faded in the first 140ms
    platform/remotes by scrim: focus did not return to the row
    billing/customers by button: the sheet is gone on the first frame
    billing/customers by button: nothing moved or faded in the first 140ms
    billing/customers by button: focus did not return to the row
```

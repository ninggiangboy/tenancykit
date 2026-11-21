# Rendering cost, before and after

Headless Chromium, 1280x800, one worker, the compose stack on this machine, sampled with `frameStats` in
`frontend/e2e/support/audit.ts` (every animation frame and every long task). "Before" is the console as phases 1 to 4
left it, measured with the same sampler on 2026-11-21 (and equal to the baseline `plan.md` recorded by hand). "After"
is the console with phase 5 deployed.

| Action | Before | After |
|---|---|---|
| Open a record sheet (invoices, first row, 450ms) | worst frame 33.3ms, 0 over 33.5ms, no long task | worst frame 16.8ms, 0 over 33.5ms, no long task |
| Scroll a long list (platform tenants, twelve 250px wheel steps) | worst frame 16.8ms | worst frame 16.8ms |
| Switch the theme (menu, Dark) | worst frame 16.8ms | worst frame 16.8ms |
| Type `INV-0001` at 40ms a key | **8 list requests**, worst frame 16.8ms | **1 list request**, worst frame 16.8ms |

Acceptance (no frame over 50ms, at most 2 over 33ms, no long task, at most 2 list requests for the typed word): met in
all four.

What changed the numbers, and what did not:

- The request count fell from 8 to 1 because the search box now waits 250ms after the last key.
- The worst frame of the sheet opening fell from 33.3ms to 16.8ms. The most likely cause is the full-viewport
  `backdrop-blur-[2px]` on the sheet scrim, removed in phase 1 (it re-rasterised under every frame of the slide); this
  was not isolated by measuring the removals one at a time. The `keydown` and media-query listeners that were removed and
  added again on every render, and the skeleton that repainted its background, are fixed too. The other three
  scenarios did not move, so in this environment they were not what those actions spent their time on.
- `plan.md` recorded "4 frames over 33ms" for the sheet before. This sampler counts frames over 33.5ms and found none
  before and after, so that count is not comparable; the worst frame is the figure to compare.

What this does not show: headless Chromium has no GPU, so it under-reports what a real screen does with blur, shadow and
compositing. **The Performance trace on real hardware that `plan.md` asks the owner to record, before and after, for
these four actions has not been taken, so whether the console still feels slow on a real machine is not known from this
report.** If it does, the trace names the cause and a new plan is opened for it; this plan does not call lag fixed on
headless numbers.

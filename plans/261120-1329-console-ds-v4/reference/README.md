# Reference material for this plan

- `design-system-v4/` is a copy of the Suite Console design system, version 1795165481-3dca, published on 2026-11-20. The live copy is the artifact https://claude.ai/artifact/CXbCvJyZEvS7Zx2NcQHCFi.
  - `README.md` is the brand book.
  - `acceptance.md` holds the acceptance criteria. The phases cite its check ids, such as T3 and M4.
  - `porting.md` explains how the system reaches `@suite/ui`.
  - `tokens.json` holds the v4 tokens.
  - `components/` holds each component's guidelines (`<Name>/README.md`), its preview (`<Name>/preview.html`) and the reference implementation (`bundle.js`, `bundle.css`, `index.d.ts`). The previews expect the design-system page to supply `tokens.css`, React 18 and the bundle, so they do not open on their own. Use the PNGs below to see them.
- `targets/` holds those previews rendered at their intended widths, in light and dark: what the finished screens should look like. File names read `<Component>-<theme>-<width>.png`.

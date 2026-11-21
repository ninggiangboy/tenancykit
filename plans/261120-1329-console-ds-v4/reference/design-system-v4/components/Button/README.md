# Button

Starts one action. Label it with a verb and the object ("Void invoice", "Rotate secret"), in sentence case. Variants follow shadcn, plus `danger`.

| variant | use |
|---|---|
| `default` | The one primary action of a view. It takes the app accent (Billing violet, Platform blue); ink outside an app. |
| `brand` | Rare: a primary action that sends something outside the suite (Send campaign). |
| `secondary` | A quiet alternative beside the primary one. |
| `outline` | Toolbar actions (Export, Columns) and secondary actions in a sheet. |
| `ghost` | Row actions, menu triggers, icon buttons in headers. |
| `danger` | A destructive action on a page or in a sheet (Void invoice, Deactivate): red text, no fill. It opens the confirmation. |
| `destructive` | Only the confirming button inside a Dialog ("Void 3 invoices"). Never on a page. |
| `link` | Navigation that reads as text ("View ledger"). Takes the app accent. |

## The consumer provides

- `children`, `onClick` and button attributes. With `iconOnly`, a string that becomes the `aria-label`; wrap it in `Tooltip`.
- `loading` while the request runs: the button disables itself and its icon becomes a spinner. Keep the label.
- `shortcut` when a key does the same thing (`['mod','k']`).

## UX rules

- One `default` per page head, sheet or dialog. On phones the page's `default` becomes the floating action button.
- Heights follow density: 36px comfortable, 32px compact, 44px on phones. `sm` is 32 / 28 / 36.
- Never disable a submit button to signal invalid input; let the click run validation and show the errors.
- Prefer reversible actions with an Undo toast over confirmations. Confirm only what cannot be undone (void, revoke, erase).

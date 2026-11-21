# Dialog

A modal decision or a short form. In the console it is mostly the confirmation of something that cannot be undone, and it always says what will happen. On phones it rises from the bottom like a bottom sheet.

## The consumer provides

- `open`, `onClose`, `title` (a question naming the action and the object, or the form's purpose), `description`, `children`, `footer` (Cancel, then the action).
- `role: 'alertdialog'` for destructive confirmations. `contained` renders inside the parent (previews).

## UX rules

- 520px wide; 640px for a two-column form. Fields follow density. On phones: full width from the bottom, one column, buttons stacked full width with the action first.
- List the impact with real counts (`.sc-impact`): what stops, what is kept, who is affected.
- For actions that cross a tenant boundary or erase data, ask the person to type the tenant code or record id; the button stays disabled until it matches.
- The confirming button repeats the verb ("Disable Mailroom"), never "OK" or "Yes". Only here may a button be the filled `destructive` variant.
- Focus moves into the dialog and returns to the opener; Esc, the scrim and a drag down on phones close it.

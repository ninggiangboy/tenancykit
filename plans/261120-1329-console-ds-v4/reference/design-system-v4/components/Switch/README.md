# Switch

Turns one setting on or off with immediate effect (enable an app, pause a webhook). There is no Save button around a switch, and no On/Off badge beside it: the switch is the state. On, it takes the app accent.

## The consumer provides

- `label`, `description`, `checked` / `onChange`.
- `busy` while the change is being saved: the switch stays in its new position (optimistic) and dims.

## UX rules

- Apply at once, then show a toast with Undo. If the request fails, flip it back and say why in a toast.
- If turning it off destroys or revokes something, open a confirmation Dialog that lists the impact first.
- On phones the whole row is the tap target (44px or more).

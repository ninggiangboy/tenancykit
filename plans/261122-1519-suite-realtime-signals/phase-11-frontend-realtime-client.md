---
phase: 11
title: "Frontend realtime client"
status: pending
priority: P1
effort: "2d"
dependencies: [2]
---

# Phase 11: Frontend realtime client

## Goal

A framework-light package `@suite/realtime` opens one authenticated stream, reconnects with replay, and delivers signals
to subscribers in coalesced batches, so a burst never makes the UI apply every event.

## Context

- `frontend/` is a pnpm and turbo workspace (`apps/platform-shell`, `apps/tenant-shell`, `packages/*`,
  `remotes/billing`, `remotes/platform`). Packages are TypeScript, tested with Vitest, linted with Biome.
- `@suite/auth` provides the access token and its expiry (`ConsoleSession.accessToken`, `expiresAt`).
- Remotes never import the shell or `@suite/auth`; they receive a `ShellContext` from `@suite/shell-sdk` (phase 12 adds
  the realtime member).
- The wire format is `contracts/commands/realtime.push.schema.json` for producers and `contracts/openapi/realtime.yaml`
  (phase 5) for the stream events.

## Requirements

1. `fetch`-based SSE reader (no `EventSource`): sends `Authorization: Bearer <token>` and `Last-Event-ID`, parses
   `hello`, `signal`, `resync` events and ignores comments; handles chunk boundaries and multi-line data.
2. Reconnect: exponential backoff with jitter, a fresh token each attempt, `Last-Event-ID` of the last signal id seen;
   a token that expires closes the stream and reconnects.
3. Batcher: collects signals for 300 ms, dedupes by `(signal, ref.id)` keeping the latest, then calls each subscriber
   once per batch.
4. `resync` handling: forwards `{scopes}` to subscribers once; on a reconnect that did not resume (`hello.resumed =
   false`) the client itself raises a resync for every registered scope.
5. Visibility: while the document is hidden, stop applying batches and keep the latest state; on return, deliver one
   resync for the scopes that saw activity.
6. Subscription API: `subscribe(pattern, handler)` where `pattern` is a signal name, a prefix with a trailing `*`, or an
   app scope; returns an unsubscribe function. Pure TypeScript core plus a thin React binding (`RealtimeProvider`,
   `useRealtime`) that does not depend on the shell.
7. A toast collapser helper: given incoming user-facing signals, at most one toast is visible and later ones collapse
   into a count ("+N").

## Architecture

```
fetch stream --> SSE parser --> Batcher(300ms, dedupe) --> Router --> subscribers (query invalidation, bell, toasts)
       ^                 |                                   ^
   reconnect/backoff     `-- resync ----------------------- scopes
```

## Files

Create under `frontend/packages/realtime/`: `package.json`, `tsconfig.json`, `vitest.config.ts`, `src/index.ts`,
`src/sse-parser.ts`, `src/stream-client.ts`, `src/batcher.ts`, `src/router.ts`, `src/toast-collapser.ts`,
`src/react.tsx`, and tests next to each.

Modify: `frontend/pnpm-workspace.yaml` only if packages are listed explicitly; `frontend/turbo.json` if a new task is
needed.

## Steps

1. Test-first `sse-parser`: split chunks, CRLF, comments, `id` and `event` fields, partial UTF-8, a very long data line.
2. `stream-client` with an injectable `fetch` and clock: connect, parse, reconnect with backoff and jitter, token refresh,
   `Last-Event-ID` resume, abort on dispose.
3. `batcher` with fake timers: 1,000 signals in 1 s with 30 distinct keys give exactly the distinct set per 300 ms window,
   latest value wins.
4. `router` and `resync` semantics, then visibility behaviour (fake `document.visibilityState`).
5. `toast-collapser`: one visible, the rest counted; test the "+N" text source as a count only (i18n belongs to the UI).
6. React binding and a smoke test rendering a provider with a fake stream.
7. `pnpm --filter @suite/realtime test`, `typecheck`, then `pnpm lint` from `frontend/`.

## Todo

- [ ] SSE parser with edge-case tests
- [ ] Stream client: backoff, token, resume
- [ ] Batcher with burst test
- [ ] Router, resync and visibility
- [ ] Toast collapser
- [ ] React binding
- [ ] Tests, typecheck, lint green

## Verification

- From `frontend/`: `pnpm --filter @suite/realtime test`, `pnpm typecheck`, `pnpm lint` pass.
- Burst test: 1,000 signals in 1 s produce at most ceil(1000 ms / 300 ms) batches and each distinct key at most once per
  batch.
- Reconnect test: a dropped stream resumes with the last id; a `hello` with `resumed=false` raises a resync.

## Risks

- A hand-written SSE parser may mishandle odd framing. Mitigation: the parser tests above and an integration test against
  the real service in phase 13.
- Many tabs mean many connections; the server caps per user (phase 5). A shared worker is out of scope.

## Security

- The token is only ever sent in the `Authorization` header, never logged or placed in a URL.
- Signals are treated as untrusted hints: the client only invalidates queries and never renders payload fields as HTML.

## Next

Phase 12.

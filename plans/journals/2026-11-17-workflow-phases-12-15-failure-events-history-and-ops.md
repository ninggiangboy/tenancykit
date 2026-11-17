# Workflow: phases 12 to 15

- Phase 12 added retry classes (RETRYABLE backs off on the token's `available_at`, FATAL does not), `on_error` routing,
  `FAILED_STUCK` dead letter, synchronous compensation in reverse completion order, and the retry/replay/cancel/steps API.
  Deliberate: a child instance reports failure to its parent instead of dead-lettering; compensation backoff is waited
  in-process.
- Phase 13 found that the webhook context phase 3 claimed to copy was absent, and copied it from the committed customer
  app, adapted (tables, permissions, `Workflow-*` header set, catalog). Ten event schemas live in
  `contracts/events/workflow`; `RuntimeEvents` and `TaskEvents` publish to the outbox in the state-change transaction.
- Phase 14 added the per-Tenant quota row and the 429 start cap, the history endpoints, bounded per-Tenant retention and
  the migrate runtime. Not enforced yet: payload and step-output byte caps. List filters are simple, not core-query.
- Phase 15 shipped the four sample graphs (checked publishable), the compose services, Caddy route, CI gate, smoke and
  chaos scripts, and rewrote `docs/workflow.md`. The compose smoke, the chaos cycles and the throughput baseline were not
  executed in this session; the plan's reconciliation records them as deployment verification.
- Bugs found and fixed on the way: a branch key dropped after the first node, a read outside a unit of work seeing
  nothing under RLS, an owner rule violated by a cross-context read, and a task's `on_expire` edge missing from the
  checker.

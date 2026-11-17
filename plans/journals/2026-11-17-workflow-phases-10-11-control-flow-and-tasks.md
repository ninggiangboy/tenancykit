# Workflow: phases 10 and 11

- Phase 10 closed control flow: parallel branches are node ids with a join that carries all/any/n_of, a branch keeps its
  key for the whole branch, and an early join cancels the branches it abandons. A for-each keeps its progress in one row,
  carries item and index on the token, can run iterations concurrently, and collects outputs and per-item errors. A
  sub-workflow starts a child instance linked to the parent token, pins the version it resolves, and refuses a cycle at
  publish and a depth above five at run time. Limits: 50 branches, 1,000 items, 1,000 steps, depth 5.
- A reference now also resolves to the document it names (`ExpressionEvaluator#document`), so a list of objects reaches a
  for-each as data instead of nulls; a step's output is readable as `{"step": "<nodeId>"}` through a step-output read.
- Phase 11 added human tasks: a task node parks its token; a user or group member sees and completes it under If-Match;
  a Tenant System completes by naming the person; deadlines escalate once and expire into the node's `on_expire` edge.
  Task deadlines live on the task row and a per-tenant sweep applies them (documented in the phase file).
- Two bugs found and fixed on the way: a branch key was dropped after the first node of a branch, and a query read
  outside a unit of work saw nothing under row-level security.

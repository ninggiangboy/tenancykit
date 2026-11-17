# Workflow events

The facts workflow announces. Each is delivered as a signed webhook to every subscribed endpoint, and its `data` member
is described by the schema beside this file. The envelope is the suite's one envelope, described in
[contracts/messaging/README.md](../../messaging/README.md): `id`, `type`, `aggregate`, `tenantId`, `occurredAt`,
`schemaVersion` and `data`.

| Type | Aggregate | Data |
|---|---|---|
| `instance.started` | `instance/<id>` | The instance, its definition code and pinned version, who started it |
| `instance.completed` | `instance/<id>` | The instance and when it ended |
| `instance.failed` | `instance/<id>` | The error class and a redacted reason |
| `instance.cancelled` | `instance/<id>` | The instance and when an operator stopped it |
| `instance.stuck` | `instance/<id>` | The reason it dead-lettered |
| `task.created` | `task/<id>` | The task, its instance, who was assigned, by when and what may be decided |
| `task.escalated` | `task/<id>` | The group that took over and when |
| `task.completed` | `task/<id>` | The decision, who made it and whether they were named by a system |
| `task.expired` | `task/<id>` | The task whose last deadline passed |
| `step.dead-lettered` | `instance/<id>` | The node, the error class and a redacted reason |

No payload carries the trigger input, a step's output, a connection secret or a credential. A new fact is a new schema
and a new contract test; an event never grows an ad-hoc field.

---
phase: 10
title: "AWS Infrastructure as Code, Scripts and Docs"
status: pending
priority: P2
effort: "L"
dependencies: [8, 9]
---

# Phase 10: AWS Infrastructure as Code, Scripts and Docs

## Goal

Write the complete Terraform and ops-script layer for `mailer` — module, IAM, secrets, database and broker
provisioning, pipeline wiring, smoke script and onboarding docs — so that it validates statically and is ready
to apply, without applying anything.

## Scope: infrastructure as code, not a deployment

The application is not live yet, so this phase stops at "ready to apply". It does **not** create a single AWS
resource.

In scope:

- Every `.tf` change needed for `terraform plan` to describe a complete mailer stack.
- Every ops script change, shared between compose and AWS where the script already is.
- The deploy pipeline's build / migrate / roll entries, written but not executed.
- Static validation only.

Out of scope for this phase, deferred to go-live:

- `terraform apply` against a real account.
- SES production access (account-level request, up to 96 h).
- Publishing the three DKIM CNAMEs and waiting for identity verification.
- Running `rds_init` or `mq_users` against a live server.
- One real send through the mailbox simulator.

Verification is therefore `fmt` + `init -backend=false` + `validate`, which the existing
`.github/workflows/terraform.yml` already runs on every change to `ops/terraform/**` with no AWS credentials.

## Key Insights

- `ops/terraform/ecs/iam.tf:12` states the current security model in a comment: *"The workloads call no AWS
  API themselves, so there is no task role."* Mailer breaks that. The comment and
  `ops/terraform/README.md` must be updated in the same commit, or the document lies to the next reader.
- Only one role exists today (`aws_iam_role.execution`, `ecs/iam.tf:13-16`). Never attach the SES or STS policy
  to it: it is shared by keycloak, platform, billing, rds-init and mq-users, so that would over-grant the whole
  suite. Create `aws_iam_role.task` and thread `task_role_arn` into the mailer module only.
- `tenant` has no Terraform module. The only background-worker template is Billing's worker:
  `ops/terraform/billing/task-defs.tf:114-153` (no `portMappings`, actuator TCP probe) and
  `ops/terraform/billing/services.tf:45-65` (service with no load balancer and no Service Connect).
- A new service needs entries in: `locals.tf:28-36` (`log_group_names`), `ecs/ecr.tf:5`
  (`toset(["platform","billing","keycloak"])`), `main.tf` (module + wiring), `outputs.tf:36-45`
  (`task_definition_arns`) and `:49-57` (`service_names`), `deploy.yml:67-83` (build/push), `:116-139`
  (migrate-by-key), `:155-158` (roll list).
- `rds/init-databases.sh` is one-shot and **fails on an existing role** by design
  (`rds/databases-roles.tf:20-23`). Adding mailer's database to a live environment needs a fresh one-shot task
  or a manual `psql`, not a re-run.
- SES Terraform creates the identity without a Route53 zone (`aws_sesv2_email_identity` returns three DKIM
  tokens); publishing the CNAMEs needs DNS access that Terraform may not have. The repo has no Route53 zone
  and `ops/terraform/README.md:156` already tells operators to manage DNS by hand. Keep that pattern.
- `ops/smoke/*` runs against compose only and cannot reach ECS. A production check must be its own script and
  must not depend on the compose stack.
- SES sandbox is per account and region, and production access is an account-level request with a 96-hour
  initial response. This is the one item on the critical path that code cannot unblock.

## Files to Create / Modify

Create:

- `ops/terraform/mailer/variables.tf`, `task-defs.tf`, `service.tf`, `outputs.tf`
- `ops/smoke/mailer-smoke.sh` — publish a command to the broker, assert the ledger row and Mailpit/SES result
- `docs/mailer.md` — what the service is, its queues, its statuses, the runbook for `DEAD` rows
- `docs/mailer-tenant-onboarding.md` — the trust policy the tenant creates (with the `ExternalId` condition —
  **that is where the value check lives**, not in our task role), the role's IAM policy, the region rule, the
  sandbox caveat, and what `SUCCEEDED` does and does not mean
- Modify: `docs/README.md:16-31` — add the two new documents to the reading-order table, or they are
  invisible
- Modify: `docs/suite-messaging.md:8-16` — add `mailer.commands` / `mailer.commands.dlq` to the topology table
- `.github/workflows/mailer.yml` — copy of `tenant.yml` with the isolation gate task name for mailer

Modify:

- `ops/terraform/ecs/iam.tf` — add `aws_iam_role.task` plus two inline policies; fix the `:12` comment; and
  **narrow the existing `read-suite-secrets` policy** so the mailer master key is not readable by keycloak,
  platform, billing, rds-init and mq-users (`ecs/iam.tf:23-34` currently grants `GetSecretValue` on every
  secret ARN in the map, which is the whole point of it)
- `ops/terraform/secrets/secrets.tf` — the master key must be **32 raw bytes, base64-encoded**
  (`AesGcmSecretCipher` refuses any other length, `core/core-spring/.../security/AesGcmSecretCipher.java:53-56`).
  A plain `random_password` yields alphanumerics that decode to 24 bytes, so map it through `base64encode` the
  way `BILLING_SECRETS_MASTER_KEY` already is (`secrets/secrets.tf:31-50`)
- `ops/terraform/ecs/outputs.tf` — export `task_role_arn`
- `ops/terraform/ecs/ecr.tf:5` — add `"mailer"`
- `ops/terraform/locals.tf:28-36` — add `mailer` and `mailer_migrate` log groups
- `ops/terraform/main.tf` — the `module "mailer"` block and `task_role_arn` wiring
- `ops/terraform/outputs.tf` — `task_definition_arns` and `service_names` entries
- `ops/terraform/rds/init-databases.sh` — `mailer_owner` / `mailer_app` / database / grants
- `ops/terraform/rds/databases-roles.tf` — the two new passwords in `init_secrets`
- `ops/terraform/secrets/secrets.tf` — `MAILER_MIGRATION_DB_PASSWORD`, `MAILER_DB_PASSWORD`,
  `MAILER_RABBITMQ_PASSWORD`, `MAILER_SECRETS_MASTER_KEY` (see the note above on its encoding)
- `ops/terraform/mq/broker.tf` + `mq/variables.tf` + `mq/users.tf` — the mailer broker user
- `ops/rabbitmq/declare-users.sh` — the mailer block (added in phase 5 for compose; reused here)
- `.github/workflows/deploy.yml` — build/push, `run_once mailer_migrate`, `roll mailer_migrate` handling
- `ops/terraform/README.md` — workloads table, the new task role, the SES gap
- `docs/overview-and-scope.md:100` — email is no longer an absolute non-goal; state the exception (a delivery
  service, not a notification service) and keep notifications out of scope
- `README.md` — `mailer/` in the directory table

## Architecture

Task role, deliberately narrow:

```hcl
resource "aws_iam_role" "task" {
  name               = "${var.name_prefix}-task"
  assume_role_policy = data.aws_iam_policy_document.assume_tasks.json
}

# SHARED mode: send as the suite's own verified identity.
# SendEmail takes an identity resource; GetAccount takes none, so it needs "*".
# Note the IAM namespace is `ses:` for both API generations; `sesv2:` is the SDK/ARN name, not an action prefix.
resource "aws_iam_role_policy" "task_ses" {
  role = aws_iam_role.task.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    {
      Effect   = "Allow"
      Action   = ["ses:SendEmail"]
      Resource = ["arn:aws:ses:${var.ses_region}:${data.aws_caller_identity.current.account_id}:identity/${var.ses_domain}"]
      Condition = { StringEquals = { "ses:FromAddress" = "noreply@${var.ses_domain}" } }
    },
    {
      Effect   = "Allow"
      Action   = ["ses:GetAccount"]
      Resource = "*"
    }
  ]})
}

# TENANT mode: assume only a role in an account we have seen a tenant register,
# and only with an external id present.
#
# `StringLike` on `sts:ExternalId = "*"` is a *presence* check, not a value check:
# `*` matches any string, so it forces mailer to send something but constrains nothing.
# The value check belongs in the TENANT's trust policy (see the onboarding doc).
# What this policy must do is limit the accounts and the role name.
resource "aws_iam_role_policy" "task_assume" {
  role = aws_iam_role.task.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [{
    Effect   = "Allow"
    Action   = "sts:AssumeRole"
    Resource = "arn:aws:iam::${aws_organizations_organization.this.id}:*"
    # in practice: one statement per registered tenant account, generated from the credential table
    # via a data source or a variable list. Never `arn:aws:iam::*:role/*`.
    Condition = {
      StringLike = { "sts:ExternalId" = "mailer-*" }
      StringEquals = { "aws:PrincipalArn" = aws_iam_role.task.arn }
    }
  }]})
}
```

The account list is the load-bearing part. Keep it as an explicit variable
(`var.tenant_aws_account_ids`) that ops maintains alongside `mailer_credentials.aws_account_id`, and assert in
`CredentialAdminUseCase` (phase 9) that a registered account is in that list. A policy whose only constraint
is a role-name glob is the confused-deputy hole reopened one layer down.

Service shape: one task definition family `mailer`, one service `mailer` (worker), plus a `mailer_migrate`
task definition run once before the roll. No ALB, no Service Connect: consumer only.

## Implementation Steps

1. Add the SES variables and the identity: `ses_region`, `ses_domain`, `aws_sesv2_email_identity` (Easy DKIM),
   optionally `aws_sesv2_email_identity_mail_from_attributes`. Publish the three DKIM CNAMEs manually in
   Route53 (or wherever the domain lives) and record the tokens in the PR; do not add a zone resource.
2. Add `aws_iam_role.task` + the two inline policies. Export `task_role_arn`.
3. Create `ops/terraform/mailer/` from `ops/terraform/billing/` but keep only what a worker needs: task
   definition with no port mappings, the actuator TCP probe, log group from `var.log_group_names`, secrets
   from `var.secret_arns`.
4. Wire `module "mailer"` in `main.tf` with `task_role_arn = module.ecs.task_role_arn`.
5. Add the ECR repo, log groups, outputs, and the RDS/mq additions.
6. Extend `deploy.yml`: build and push the mailer image, `run_once mailer_migrate` before the roll, then roll
   the mailer service.
7. Write `ops/smoke/mailer-smoke.sh`: publish one command through the broker to `mailer.commands`, then assert
   the ledger row reaches `SUCCEEDED`. It must take the broker URL and DB connection from the environment so it
   can run against either compose or AWS.
8. Write `docs/mailer-tenant-onboarding.md` with the exact trust policy, the role's IAM policy, the region
   rule, the sandbox caveat, and the statement that `SUCCEEDED` means accepted by SES.
9. Write `docs/mailer.md` with the status machine, the DLQ runbook, and how to resend a `DEAD` delivery.
10. Fix `ops/terraform/README.md` and `docs/overview-and-scope.md`, and add the CI workflow.

## Todo

- [ ] SES identity + optional MAIL FROM; three DKIM CNAMEs recorded in the PR
- [ ] `aws_iam_role.task` with the split SES statement and the account-allowlisted `sts:AssumeRole`
- [ ] `read-suite-secrets` narrowed so the mailer master key is not shared with other workloads
- [ ] `MAILER_SECRETS_MASTER_KEY` generated as `base64encode` of 32 bytes
- [ ] `ops/terraform/mailer/` module (worker shape, no ALB, no Service Connect)
- [ ] `ecr.tf`, `locals.tf` log groups, `main.tf`, `outputs.tf` entries
- [ ] RDS init + mq broker user + secrets
- [ ] `deploy.yml`: build/push, `run_once mailer_migrate`, roll
- [ ] `ops/smoke/mailer-smoke.sh` runnable against compose and AWS
- [ ] `docs/mailer.md`, `docs/mailer-tenant-onboarding.md`, `docs/README.md`, `docs/suite-messaging.md`, `docs/overview-and-scope.md`
- [ ] `ops/terraform/README.md` + the `ecs/iam.tf:12` comment corrected
- [ ] `terraform fmt -check` and `terraform validate` green with no AWS credentials

## Go-live Checklist (explicitly not this phase)

Recorded here so nothing is lost, but not executed until the application is ready to go live:

- [ ] `aws sesv2 get-account` confirms `ProductionAccessEnabled`, `SendingEnabled` and a non-zero quota
- [ ] The three DKIM CNAMEs are published and the identity reports `SUCCESS`
- [ ] One send to each of `success@`, `bounce@` and `complaint@simulator.amazonses.com`, proving the AWS
      contract inside the sandbox
- [ ] `terraform apply`, then `run_once rds_init`, `run_once mq_users`, `run_once mailer_migrate`, then roll

## Verification

Everything here runs without AWS credentials.

```bash
cd ops/terraform && terraform fmt -check -recursive .
cd ops/terraform && terraform init -backend=false && terraform validate
cd mailer && ./gradlew build
ops/smoke/mailer-smoke.sh        # against compose, not AWS
```

`terraform validate` is the gate that matters: it type-checks every module input and output, so a missing
variable, a wrong attribute or an unexported output fails here rather than in a manual apply. CI already runs
exactly this trio in `.github/workflows/terraform.yml:21-40`.

Also confirm the render, which catches the mistakes validation does not:

```bash
cd ops/terraform && terraform plan -input=false 2>&1 | head -40
```

A plan needs credentials, so do not run it unless one is available; on a machine with none, read
`terraform validate` plus the `terraform.yml` CI result instead, and say so in the PR.

Once an account exists, the go-live sequence is, in order: `terraform apply` → `run_once rds_init` →
`run_once mq_users` → build and push the four images → `run_once mailer_migrate` → roll the mailer service.
That sequence is written into `deploy.yml` by this phase; executing it is go-live, not this phase.

When an account is finally available, prove the AWS contract **before** production access, using the mailbox
simulator — it works inside the sandbox, does not consume the 24-hour quota, and does not touch reputation:

```bash
aws sesv2 get-account --region "$SES_REGION" \
  --query '{prod:ProductionAccessEnabled,enabled:SendingEnabled,quota:SendQuota}'
```

Then send once to each of `success@`, `bounce@` and `complaint@simulator.amazonses.com` and confirm the ledger
reaches `SUCCEEDED` for all three (SES accepts all three; the difference is the event it emits afterwards,
which this plan does not consume).

## Risk Assessment

| Risk | Signal it broke | Response |
|---|---|---|
| SES policy attached to the shared execution role | Every workload can send email | Attach only to `aws_iam_role.task`, threaded into the mailer module |
| Account still in the SES sandbox | First real send fails with `MessageRejected` | Check `ProductionAccessEnabled` before onboarding a tenant; this is the critical path |
| DKIM CNAMEs never published | Identity stays `Pending`; sends fail | Record the three tokens in the PR and confirm verification |
| `rds-init` re-run on a live environment | Task fails on "role already exists" | Use a fresh one-shot task or `psql`; do not re-run the existing one |
| `task_role_arn` not threaded | Task starts without credentials; every send is `AccessDenied` | Assert the task definition shows the role in `terraform plan` |
| Task role assumes a role in an account nobody vetted | `TENANT` mode sends from an unrelated company's identity | Keep an explicit `var.tenant_aws_account_ids`; reject a registration whose `aws_account_id` is not in it |
| Master key readable by every workload | A compromised non-mailer service can decrypt every tenant credential once it has the ciphertexts | Narrow `read-suite-secrets` per module; or move to a dedicated KMS key |
| Master key generated as a plain `random_password` | Boot fails: "must decode to exactly 32 bytes" | `base64encode` a 32-byte value, as `BILLING_SECRETS_MASTER_KEY` already does |
| Comments left stale | `ecs/iam.tf:12` still says no workload calls AWS | Fix it in this commit |

## Security Considerations

- The task role assumes tenant roles across accounts; the resource must be a role-name pattern, never `*` with
  no condition.
- `ses:SendEmail` is scoped to the suite's identity ARN.
- The master key for `mailer_credentials` lives in Secrets Manager and is injected as an environment secret;
  losing it means every `TENANT` row is undecryptable, so it needs the same backup story as the database.
- Logs must not contain recipient addresses at scale or message bodies. Log delivery id, outcome, error code.

## Next Steps

After this phase the plan's success criteria are all reachable. The remaining known gap is bounce/complaint
visibility for `TENANT` mode, which needs the tenant to allow our account to subscribe to its SNS topic; that is
a separate plan and is listed under the plan's Known Gaps.

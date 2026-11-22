# ECS Fargate deployment

The AWS path for the suite. `docker compose` remains the local path and is unchanged; this directory describes the
same workloads on ECS Fargate, with managed data services instead of containers.

| Workload | Runs | ECS shape |
|---|---|---|
| `keycloak` | The realm import and the OIDC endpoints | Service, Service Connect name `keycloak` |
| `platform` | Tenancy, access, integration, the token endpoint and JWKS | Service, 2 replicas, Service Connect name `platform` |
| `billing` (`api`) | HTTP, portal and Stripe webhooks | Service, 2 replicas, the ALB's default target |
| `billing-worker` | Every Billing job | Service, 2 replicas, no ALB target |
| `billing-migrate` | Flyway as the table owner, then exits | One-shot task definition, run by the pipeline |
| `mailer` | The `email.send` command listener and every mailer job | Service, 2 replicas, no ALB target and no Service Connect |
| `mailer-migrate` | Flyway as the table owner, then exits | One-shot task definition, run by the pipeline |
| `notification-api` | The user's inbox and preferences, templates, tenant settings and the dispatch ledger | Service, 2 replicas, ALB rule priority 40 |
| `notification-worker` | The `platform.events` listener, the outbox relay and housekeeping | Service, 2 replicas, no ALB target |
| `notification-migrate` | Flyway as the table owner, then exits | One-shot task definition, run by the pipeline |
| `mailroom-api` | Templates, receivers, groups, segments, emails, campaigns, settings and the public tracking paths | Service, 2 replicas, ALB rule priority 50 |
| `mailroom-worker` | The `platform.events` and `mailroom.mailer-events` listeners, the campaign jobs and housekeeping | Service, 2 replicas, no ALB target |
| `mailroom-migrate` | Flyway as the table owner, then exits | One-shot task definition, run by the pipeline |
| `rds-init` | Creates databases and roles, then exits | One-shot task definition, run once |
| `mq-users` | Applies broker permissions, then exits | One-shot task definition, run once |

RDS hosts `platform`, `billing`, `tenant`, `mailer`, `notification`, `mailroom` and `keycloak` databases with the same
owner/app role split as compose. Amazon MQ hosts the RabbitMQ broker with a user per service; mailer's is limited to
`mailer.commands`, its dead-letter queue and `suite.dlx`, and mailroom's to `mailer.commands` plus its own queues. Both are
private; tasks reach them through their security group.

## IAM

Every task except mailer's uses the shared execution role `${name_prefix}-task-execution`: it pulls images, writes logs
and injects container secrets. Its `read-suite-secrets` policy allows `secretsmanager:GetSecretValue` on every secret in
the map **except** `MAILER_SECRETS_MASTER_KEY`, the one value that decrypts every tenant credential — so Keycloak,
Platform, Billing, rds-init and mq-users can no longer read it. The other three mailer secrets stay in that set on
purpose: `rds-init` and `mq-users` inject them to create the mailer database roles, the database and the broker user, and
excluding them would fail both tasks at start.

Mailer's container secrets are injected by its own execution role (`<environment>-mailer-task-execution`), which reads
exactly the four mailer secrets. ECS resolves a container's `secrets` with the execution role, so splitting the read
policy per module is what makes the narrowing above work instead of failing every mailer task with a
`ResourceInitializationError`.

Mailer is the only workload that calls an AWS API, so it is the only one with a task role (`${name_prefix}-task`),
threaded into the mailer module alone and never attached to the shared execution role:

| Policy | Allows | Bounded by |
|---|---|---|
| `task_ses` | `ses:SendEmail` on the suite identity ARN, `ses:GetAccount` on `*` (GetAccount takes no resource) | `ses:FromAddress` must equal the configured from address |
| `task_assume` | `sts:AssumeRole` on one `mailer-*` role per registered tenant account | `sts:ExternalId` must be present and `aws:PrincipalArn` must be the task role; the *value* check lives in the tenant's trust policy |

`StringLike` on `sts:ExternalId = "mailer-*"` is a presence check, not a value check. `var.tenant_aws_account_ids` is
the load-bearing part: one resource ARN pattern per account ops has registered in `mailer_credentials`, never
`arn:aws:iam::*:role/*`. The IAM action prefix is `ses:` for both SES API generations; `sesv2:` is the SDK and ARN name
and is never an action prefix.

## SES

`ses_domain` and `ses_region` (empty follows `region`) describe the suite's sending identity, created as
`aws_sesv2_email_identity` in the `aws.ses` provider, and mailer reads both back as `MAILER_DEFAULT_FROM_ADDRESS` and
`MAILER_SES_REGION`. The identity is created **without a Route53 zone**: Terraform returns three DKIM tokens and an
operator publishes the three CNAMEs by hand where the domain's DNS lives, exactly as the ALB's DNS records are published
by hand. Until they resolve, the identity stays `Pending` and a SHARED-mode send is rejected. Production access is a
separate account-level request, so a new account stays in the SES sandbox and can only send to verified addresses. A
tenant that sends through its own identity is unaffected: it brings its own identity, region and sandbox state, which is
what `docs/mailer-tenant-onboarding.md` describes.

## Addresses

There is one rule about addresses: an issuer is public, a server-to-server URL is internal.

| Setting | Value | Why |
|---|---|---|
| `PLATFORM_TOKEN_ISSUER`, `PLATFORM_OIDC_ISSUER_URI` | `https://platform.<domain>`, `https://auth.<domain>/realms/billing` | These strings are stamped into tokens and compared exactly. |
| `BILLING_PLATFORM_ISSUER`, `BILLING_OIDC_ISSUER_URI` | the same public addresses | Billing validates what Platform and Keycloak minted. |
| `PLATFORM_OIDC_JWK_SET_URI`, `BILLING_PLATFORM_JWK_SET_URI` | `http://keycloak.<namespace>:8080/...`, `http://platform.<namespace>:8080/.well-known/jwks.json` | Fetched server-to-server, so no ALB round trip. |
| `BILLING_PLATFORM_BASE_URL` | `http://platform.<namespace>:8080` | Service Connect name. |
| `EMAIL_PLATFORM_BASE_URL`, `EMAIL_OIDC_ISSUER_URI` | `http://platform.<namespace>:8080`, the public Keycloak issuer | The replica source and the user-token issuer. |
| `MAILROOM_PUBLIC_BASE_URL` | `https://<domain>/mailroom` | The prefix a recipient's mail client reaches; the proxy forwards it to `mailroom-api`. |
| `PLATFORM_INTERNAL_HOSTNAMES` | `platform.<namespace>` | Platform resolves the tenant from Host; an unlisted internal FQDN gets a 404. This needs plan `261114-0930` in the code. |

The internal FQDN is `local.internal_fqdn` in `locals.tf`; no module hardcodes the namespace.

## Alb routing

| Priority | Condition | Target |
|---|---|---|
| 10 | path `/platform/*` | Platform |
| 20 | host `platform.<domain>` | Platform |
| 30 | host `auth.<domain>` | Keycloak |
| 40 | path `/notification/*` | Notification api |
| 50 | path `/mailroom/*` | Mailroom api |
| default | anything else, including every tenant host | Billing api |

The Host header is forwarded unchanged; rewriting it would break tenant resolution. ALB cannot strip a path prefix,
so the path rule forwards `/platform/*` as-is. The admin host is the root route that behaves exactly like compose.
HTTPS is enabled only when `certificate_arn` is set; otherwise the listener is HTTP and the public scheme in every
setting is `http`. Keycloak reads the public URL through `KC_HOSTNAME` and the forwarded scheme through
`KC_PROXY_HEADERS=xforwarded` (the 26.x replacement for the removed `KC_PROXY=edge`), which keeps its issuer public.

## State

Local state is the default so `terraform validate` works without an AWS account. Before applying from CI or a
second machine, switch to S3 + DynamoDB in `main.tf`:

```bash
aws s3api create-bucket --bucket <state-bucket> --region <region>
aws dynamodb create-table --table-name <lock-table> \
  --attribute-definitions AttributeName=LockID,AttributeType=S \
  --key-schema AttributeName=LockID,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST
```

Then replace `backend "local" {}` with the `backend "s3"` block in the comment above it. The deploy workflow assumes
shared state.

## Variables

Every value with a sensible development default lives in `variables.tf`. Override with a git-ignored `*.tfvars` file:

```hcl
region                    = "us-west-2"
environment               = "dev"
public_domain             = "billing.example.com"
service_connect_namespace = "tenancykit.local"
certificate_arn           = "" # an ACM ARN enables the HTTPS listener
image_tag                 = "2026-11-14"
platform_admin_email      = "admin@billing.example.com"
ses_domain                = "mail.example.com"
ses_region                = "us-west-2" # empty follows `region`
tenant_aws_account_ids    = ["111122223333"]
```

`platform_admin_email` creates the first Platform administrator through Keycloak on start; leave it empty to skip the
bootstrap. Its temporary password is generated into Secrets Manager.

There are no secrets in `*.tfvars`. Passwords are generated in `secrets/secrets.tf`, stored in Secrets Manager, and
referenced from task definitions by ARN only. The Platform signing key is an ECDSA P-256 key generated by Terraform
and stored the same way, so every replica signs with the same key.

`db_engine_version` and `mq_engine_version` are pinned in `variables.tf`; set them to versions the target region
actually offers before the first apply.

## First deployment

```bash
cd ops/terraform
terraform init
terraform apply -var-file=dev.tfvars
```

Then build and push the images into the repositories Terraform created:

```bash
# pick any tag; `variables.tf` defaults image_tag to "latest"
tag="2026-11-14"
registry="$(terraform output -json ecr_repository_urls | jq -r .platform)"

docker build -f ../../backend/services/platform/apps/app-boot/Dockerfile -t "$registry:$tag" ../..
docker push "$registry:$tag"

# billing (shared by migrate, api and worker)
billing_repo="$(terraform output -json ecr_repository_urls | jq -r .billing)"
docker build -f ../../backend/services/billing/apps/app-boot/Dockerfile -t "$billing_repo:$tag" ../..
docker push "$billing_repo:$tag"

# keycloak, with the realm export, the tenant username form and the sign-in theme baked in; the build context is the
# repository root
keycloak_repo="$(terraform output -json ecr_repository_urls | jq -r .keycloak)"
docker build -f keycloak/Dockerfile -t "$keycloak_repo:$tag" ../..
docker push "$keycloak_repo:$tag"

# mailer (shared by migrate and worker)
mailer_repo="$(terraform output -json ecr_repository_urls | jq -r .mailer)"
docker build -f ../../backend/services/mailer/apps/app-boot/Dockerfile -t "$mailer_repo:$tag" ../..
docker push "$mailer_repo:$tag"
```

Run the two one-shot initializers exactly once, before any service starts:

```bash
cluster="$(terraform output -raw cluster_name)"
subnets="$(terraform output -json private_subnet_ids | jq -r 'join(",")')"
sg="$(terraform output -raw task_security_group_id)"

run_once() {
  definition="$(terraform output -json task_definition_arns | jq -r --arg key "$1" '.[$key]')"
  task="$(aws ecs run-task --cluster "$cluster" --task-definition "$definition" --launch-type FARGATE \
    --network-configuration "awsvpcConfiguration={subnets=[$subnets],securityGroups=[$sg],assignPublicIp=DISABLED}" \
    --query 'tasks[0].taskArn' --output text)"
  aws ecs wait tasks-stopped --cluster "$cluster" --tasks "$task"
  code="$(aws ecs describe-tasks --cluster "$cluster" --tasks "$task" \
    --query 'tasks[0].containers[0].exitCode' --output text)"
  [ "$code" = "0" ] || { echo "$1 exited $code"; exit 1; }
}

run_once rds_init
run_once mq_users
```

Then run the migrate task and roll the services. The deploy workflow does this in order; by hand:

```bash
run_once billing_migrate
run_once mailer_migrate

for key in keycloak platform billing_api billing_worker mailer; do
  definition="$(terraform output -json task_definition_arns | jq -r --arg key "$key" '.[$key]')"
  service="$(terraform output -json service_names | jq -r --arg key "$key" '.[$key]')"
  aws ecs update-service --cluster "$cluster" --service "$service" \
    --task-definition "$definition" --force-new-deployment
done
```

`billing-migrate` and `mailer-migrate` are task definitions, not services: ECS has no
`depends_on: service_completed_successfully`. The api, worker and mailer services deliberately ignore task-definition
changes in Terraform, so they only move after their migrate has exited 0.

Point DNS at `terraform output -raw alb_dns_name`: the apex or tenant wildcard at Billing, `platform.<domain>` at
Platform, `auth.<domain>` at Keycloak.

## CI

`terraform.yml` runs `fmt -check`, `init -backend=false` and `validate` on every change under `ops/terraform/`, and
`plan` when the repository variable `AWS_ROLE_ARN` is set.

`deploy.yml` builds the four images, applies the stack, optionally bootstraps the data services, runs Billing's and
Mailer's migrate, and rolls the services. It needs `AWS_ROLE_ARN`, `AWS_REGION`, `PUBLIC_DOMAIN`, the optional
`SERVICE_CONNECT_NAMESPACE` / `CERTIFICATE_ARN` variables, and shared state.

## Verification and known gaps

What this directory proves without an AWS account:

```bash
terraform fmt -check -recursive ops/terraform/
terraform -chdir=ops/terraform init -backend=false
terraform -chdir=ops/terraform validate
```

`validate` catches syntax, type and module-wiring errors only. It cannot prove a task starts, that the realm imports,
or that a request routes. Those need an AWS sandbox.

The existing smoke scripts are written against compose, not this stack:

- `ops/smoke/isolation.sh` opens the compose network and reads `.env`, so it cannot run against ECS. Isolation there
  is enforced by the RDS roles and the broker permissions the init tasks apply; proving it needs an equivalent check
  from inside the VPC.
- `ops/smoke/suite-smoke.sh` takes `PROXY_URL` and `KEYCLOAK_URL`, but it also hardcodes `<tenant>.localhost` Host
  headers and calls Platform under `/platform/*`, relying on Caddy's prefix strip. Against the ALB those Hosts are
  wrong and the prefix is forwarded unchanged, so a run needs an adapter (public base domain plus a strip-capable
  proxy) or the app-side path change described above.

Checks that do work as written on a sandbox:

```bash
# Public issuer and JWKS
curl -fsS "https://auth.<domain>/realms/billing/.well-known/openid-configuration" | jq .issuer

# Platform admin host root (the route that matches compose)
curl -fsS "https://platform.<domain>/actuator/health/readiness"
```

Known gaps, recorded rather than hidden:

- **Plan `261114-0930` must land first.** Until `PLATFORM_INTERNAL_HOSTNAMES` exists in Platform, Billing's internal
  call 404s even though the task definitions are correct.
- **ALB path rewriting.** `/platform/*` is forwarded without stripping; compose's Caddy strips it. The admin host is
  unaffected.
- **Amazon MQ permissions.** The broker has users but no per-user exchange/queue permissions; `mq-users` applies them
  through the management API. Its exit code is the proof; it needs the sandbox.
- **SES.** The identity is created without a Route53 zone, so its three DKIM CNAMEs are published by hand and it stays
  `Pending` until a send is attempted; the account also stays in the SES sandbox until production access is granted.
  Proving a send works means the mailbox simulator, not a real recipient.
- **One NAT gateway.** Development only; production adds one per zone.

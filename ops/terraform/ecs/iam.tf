data "aws_iam_policy_document" "assume_tasks" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# The task role's own trust policy. It is identical today because the same ECS agent assumes both roles, but it is written
# separately so a later change to one role's trust cannot quietly widen the other's.
data "aws_iam_policy_document" "assume_task_role" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

data "aws_caller_identity" "current" {}

locals {
  # The one secret that only the mailer runtime injects. The other three mailer secrets are injected by the one-shot
  # rds-init and mq-users tasks (they create the mailer database roles, the mailer database and the broker user), and
  # those tasks use this shared execution role, so they stay in the allowed set; excluding them would fail both tasks at
  # container start. Nothing in them decrypts anything. This one does: it is the key every tenant credential is
  # encrypted under, and mailer injects it through its own execution role (ops/terraform/mailer/iam.tf).
  mailer_only_secret_names = ["MAILER_SECRETS_MASTER_KEY"]
}

# Pulls images from ECR, writes container logs and injects each workload's secrets. Keycloak, Platform, Billing,
# rds-init and mq-users call no AWS API, so the suite's only task role belongs to mailer and is not attached here.
resource "aws_iam_role" "execution" {
  name               = "${var.name_prefix}-task-execution"
  assume_role_policy = data.aws_iam_policy_document.assume_tasks.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Mailer is the one workload that calls AWS: it sends through SES, and in TENANT mode it assumes a role in the tenant's
# own account first. Both policies are attached here and nowhere else, so no other service can send email or assume a
# tenant role. The role is threaded into the mailer module alone as task_role_arn.
resource "aws_iam_role" "task" {
  name               = "${var.name_prefix}-task"
  assume_role_policy = data.aws_iam_policy_document.assume_task_role.json
}

# SHARED mode: send as the suite's own verified identity, from one address and no other. The IAM namespace is `ses:` for
# both SES API generations; `sesv2:` is the SDK and ARN service name, never an action prefix.
data "aws_iam_policy_document" "task_ses" {
  statement {
    actions   = ["ses:SendEmail"]
    resources = ["arn:aws:ses:${var.ses_region}:${data.aws_caller_identity.current.account_id}:identity/${var.ses_domain}"]

    condition {
      test     = "StringEquals"
      variable = "ses:FromAddress"
      values   = [var.ses_from_address]
    }
  }

  # GetAccount takes no resource, which is why it is the one statement on "*": it is the read-only call the credential
  # probe and the health job make, and it can neither send nor read message content.
  statement {
    actions   = ["ses:GetAccount"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "task_ses" {
  name   = "task_ses"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task_ses.json
}

# TENANT mode: assume a role in an account ops has registered, and only one named mailer-*.
#
# `StringLike` on `sts:ExternalId = "mailer-*"` is a *presence* check, not a value check: `*` matches any string, so it
# forces mailer to present something and constrains nothing. The value check belongs in the tenant's trust policy, which
# is the only place that knows the external id; see docs/mailer-tenant-onboarding.md. What this policy has to bound is
# the account list and the role name, so the resource is one ARN pattern per registered account and never
# `arn:aws:iam::*:role/*`. `var.tenant_aws_account_ids` is maintained by ops beside `mailer_credentials.aws_account_id`.
data "aws_iam_policy_document" "task_assume" {
  statement {
    actions   = ["sts:AssumeRole"]
    resources = [for account_id in var.tenant_aws_account_ids : "arn:aws:iam::${account_id}:role/mailer-*"]

    condition {
      test     = "StringLike"
      variable = "sts:ExternalId"
      values   = ["mailer-*"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:PrincipalArn"
      values   = [aws_iam_role.task.arn]
    }
  }
}

resource "aws_iam_role_policy" "task_assume" {
  name   = "task_assume"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task_assume.json
}

# Narrowed: every suite secret except the mailer master key. Keycloak, Platform, Billing, rds-init and mq-users can no
# longer read the one value that decrypts every tenant's mailer credential, which is what a compromised non-mailer
# service would be after once it also has the ciphertexts. `NotResources` keeps the statement's meaning ("every suite
# secret but these") instead of a list that has to be extended whenever a secret is added.
#
# Mailer's own container secrets are injected by mailer's own execution role, which reads exactly the four mailer secrets:
# ECS resolves a container's `secrets` with the execution role, so excluding the master key from this shared role would
# otherwise fail every mailer task at start with a ResourceInitializationError. That half lives in
# ops/terraform/mailer/iam.tf; together they are what makes the narrowing work rather than merely look narrow.
data "aws_iam_policy_document" "secrets" {
  statement {
    actions       = ["secretsmanager:GetSecretValue"]
    not_resources = [for name in local.mailer_only_secret_names : var.secret_arns[name]]
  }
}

resource "aws_iam_role_policy" "secrets" {
  name   = "read-suite-secrets"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.secrets.json
}

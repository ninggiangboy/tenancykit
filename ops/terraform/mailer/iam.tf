locals {
  # Every secret mailer injects, in one place: the execution role policy below and the task definitions read the same
  # list, so a container can never ask for a secret whose ARN the role cannot read.
  secret_names = [
    "MAILER_DB_PASSWORD",
    "MAILER_MIGRATION_DB_PASSWORD",
    "MAILER_RABBITMQ_PASSWORD",
    "MAILER_SECRETS_MASTER_KEY",
  ]
}

data "aws_iam_policy_document" "assume_tasks" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# Mailer's own execution role, not the suite's shared one. ECS resolves a container's `secrets` with the execution role,
# and the shared `read-suite-secrets` policy now excludes `MAILER_SECRETS_MASTER_KEY` so that no other workload — the five
# that run through it — can read the key every tenant credential is encrypted under. Mailer therefore injects its secrets
# through this role, which reads exactly the four ARNs above: nothing more, and nothing the container cannot pull.
resource "aws_iam_role" "execution" {
  name               = "${var.environment}-mailer-task-execution"
  assume_role_policy = data.aws_iam_policy_document.assume_tasks.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "secrets" {
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [for name in local.secret_names : var.secret_arns[name]]
  }
}

resource "aws_iam_role_policy" "secrets" {
  name   = "read-mailer-secrets"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.secrets.json
}

locals {
  init_environment = [
    { name = "PGHOST", value = aws_db_instance.this.address },
    { name = "PGPORT", value = tostring(aws_db_instance.this.port) },
    { name = "PGUSER", value = aws_db_instance.this.username },
    { name = "PGSSLMODE", value = "require" },
  ]

  init_secrets = [
    { name = "PGPASSWORD", valueFrom = var.secret_arns["POSTGRES_SUPERUSER_PASSWORD"] },
    { name = "PLATFORM_MIGRATION_DB_PASSWORD", valueFrom = var.secret_arns["PLATFORM_MIGRATION_DB_PASSWORD"] },
    { name = "PLATFORM_DB_PASSWORD", valueFrom = var.secret_arns["PLATFORM_DB_PASSWORD"] },
    { name = "BILLING_MIGRATION_DB_PASSWORD", valueFrom = var.secret_arns["BILLING_MIGRATION_DB_PASSWORD"] },
    { name = "BILLING_DB_PASSWORD", valueFrom = var.secret_arns["BILLING_DB_PASSWORD"] },
    { name = "MAILER_MIGRATION_DB_PASSWORD", valueFrom = var.secret_arns["MAILER_MIGRATION_DB_PASSWORD"] },
    { name = "MAILER_DB_PASSWORD", valueFrom = var.secret_arns["MAILER_DB_PASSWORD"] },
    { name = "NOTIFICATION_MIGRATION_DB_PASSWORD", valueFrom = var.secret_arns["NOTIFICATION_MIGRATION_DB_PASSWORD"] },
    { name = "NOTIFICATION_DB_PASSWORD", valueFrom = var.secret_arns["NOTIFICATION_DB_PASSWORD"] },
    { name = "MAILROOM_MIGRATION_DB_PASSWORD", valueFrom = var.secret_arns["MAILROOM_MIGRATION_DB_PASSWORD"] },
    { name = "MAILROOM_DB_PASSWORD", valueFrom = var.secret_arns["MAILROOM_DB_PASSWORD"] },
    { name = "CUSTOMER_MIGRATION_DB_PASSWORD", valueFrom = var.secret_arns["CUSTOMER_MIGRATION_DB_PASSWORD"] },
    { name = "CUSTOMER_DB_PASSWORD", valueFrom = var.secret_arns["CUSTOMER_DB_PASSWORD"] },
    { name = "LOYALTY_MIGRATION_DB_PASSWORD", valueFrom = var.secret_arns["LOYALTY_MIGRATION_DB_PASSWORD"] },
    { name = "LOYALTY_DB_PASSWORD", valueFrom = var.secret_arns["LOYALTY_DB_PASSWORD"] },
    { name = "SURVEY_MIGRATION_DB_PASSWORD", valueFrom = var.secret_arns["SURVEY_MIGRATION_DB_PASSWORD"] },
    { name = "SURVEY_DB_PASSWORD", valueFrom = var.secret_arns["SURVEY_DB_PASSWORD"] },
    { name = "BOOKING_MIGRATION_DB_PASSWORD", valueFrom = var.secret_arns["BOOKING_MIGRATION_DB_PASSWORD"] },
    { name = "BOOKING_DB_PASSWORD", valueFrom = var.secret_arns["BOOKING_DB_PASSWORD"] },
    { name = "WORKFLOW_MIGRATION_DB_PASSWORD", valueFrom = var.secret_arns["WORKFLOW_MIGRATION_DB_PASSWORD"] },
    { name = "WORKFLOW_DB_PASSWORD", valueFrom = var.secret_arns["WORKFLOW_DB_PASSWORD"] },
    { name = "ATTRIBUTION_MIGRATION_DB_PASSWORD", valueFrom = var.secret_arns["ATTRIBUTION_MIGRATION_DB_PASSWORD"] },
    { name = "ATTRIBUTION_DB_PASSWORD", valueFrom = var.secret_arns["ATTRIBUTION_DB_PASSWORD"] },
    { name = "KEYCLOAK_DB_PASSWORD", valueFrom = var.secret_arns["KEYCLOAK_DB_PASSWORD"] },
  ]
}

# A one-shot task, not a service: ECS has no equivalent of compose's service_completed_successfully, so the pipeline
# runs this and waits for exit code 0 before any service starts. Re-running it fails on an existing role, which is
# intended: it runs once per database server.
resource "aws_ecs_task_definition" "init" {
  family                   = "${var.environment}-rds-init"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([
    {
      name       = "rds-init"
      image      = var.init_image
      essential  = true
      entryPoint = ["bash"]
      command    = ["-c", file("${path.module}/init-databases.sh")]

      environment = local.init_environment
      secrets     = local.init_secrets

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = var.log_group_name
          awslogs-region        = var.region
          awslogs-stream-prefix = "init"
        }
      }
    }
  ])
}

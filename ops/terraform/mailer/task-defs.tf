locals {
  # The settings both runtimes read. The migrate profile turns the listener and every job off and runs Flyway as the
  # table owner; the worker profile runs the listener and the jobs. Mailer has no api runtime, so there is no third
  # definition.
  common_environment = [
    { name = "JAVA_TOOL_OPTIONS", value = "-XX:MaxRAMPercentage=50" },
    { name = "MAILER_DB_URL", value = "jdbc:postgresql://${var.db_host}:${var.db_port}/mailer?sslmode=require" },
    { name = "MAILER_DB_USER", value = "mailer_app" },
    { name = "MAILER_MIGRATION_DB_USER", value = "mailer_owner" },
    { name = "MAILER_RABBITMQ_HOST", value = var.broker_host },
    { name = "MAILER_RABBITMQ_PORT", value = tostring(var.broker_port) },
    { name = "MAILER_RABBITMQ_USER", value = "mailer" },
    { name = "SPRING_RABBITMQ_SSL_ENABLED", value = "true" },
    # SES, not the `smtp` default: a deployment sends through the suite's verified identity, and a TENANT row overrides
    # the identity per from-key. `MAILER_SES_ENDPOINT` stays empty, so the SDK reaches the real endpoint.
    { name = "MAILER_SENDER", value = "ses" },
    { name = "MAILER_SES_REGION", value = var.ses_region },
    { name = "MAILER_DEFAULT_FROM_ADDRESS", value = var.default_from_address },
  ]

  common_secrets = [for name in local.secret_names : { name = name, valueFrom = var.secret_arns[name] }]

  # The management port is the server port; mailer exposes no business HTTP, so the probe is the only listener.
  ready_probe = ["CMD", "bash", "-c", "exec 3<>/dev/tcp/localhost/8080 && printf 'GET /actuator/health/readiness HTTP/1.0\\r\\n\\r\\n' >&3 && head -20 <&3 | grep -q UP"]
}

resource "aws_ecs_task_definition" "migrate" {
  family                   = "${var.environment}-mailer-migrate"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = var.task_role_arn

  container_definitions = jsonencode([
    {
      name      = "mailer-migrate"
      image     = var.image
      essential = true

      # Flyway runs as the table owner; the worker connects as mailer_app with Flyway off.
      environment = concat(local.common_environment, [
        { name = "SPRING_PROFILES_ACTIVE", value = "migrate" },
      ])
      secrets = local.common_secrets

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = var.log_group_names["migrate"]
          awslogs-region        = var.region
          awslogs-stream-prefix = "migrate"
        }
      }
    }
  ])
}

resource "aws_ecs_task_definition" "worker" {
  family                   = "${var.environment}-mailer"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = var.task_role_arn

  container_definitions = jsonencode([
    {
      name      = "mailer"
      image     = var.image
      essential = true

      # No port mapping: mailer serves no business HTTP and nothing calls it. It keeps the actuator probe the
      # deployment needs to tell a stuck worker from a healthy one.
      environment = concat(local.common_environment, [
        { name = "SPRING_PROFILES_ACTIVE", value = "worker" },
      ])
      secrets = local.common_secrets

      healthCheck = {
        command     = local.ready_probe
        interval    = 15
        timeout     = 5
        retries     = 5
        startPeriod = 90
      }

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = var.log_group_names["worker"]
          awslogs-region        = var.region
          awslogs-stream-prefix = "worker"
        }
      }
    }
  ])
}

locals {
  # The settings all three runtimes read. The migrate profile turns web and jobs off and runs Flyway as the table
  # owner; the api profile serves HTTP; the worker profile runs the listener, the relay and housekeeping.
  common_environment = [
    { name = "JAVA_TOOL_OPTIONS", value = "-XX:MaxRAMPercentage=50" },
    { name = "EMAIL_DB_URL", value = "jdbc:postgresql://${var.db_host}:${var.db_port}/email?sslmode=require" },
    { name = "EMAIL_DB_USER", value = "email_app" },
    { name = "EMAIL_MIGRATION_DB_USER", value = "email_owner" },
    { name = "EMAIL_RABBITMQ_HOST", value = var.broker_host },
    { name = "EMAIL_RABBITMQ_PORT", value = tostring(var.broker_port) },
    { name = "EMAIL_RABBITMQ_USER", value = "email" },
    { name = "SPRING_RABBITMQ_SSL_ENABLED", value = "true" },
    { name = "EMAIL_PLATFORM_BASE_URL", value = var.platform_internal_url },
    { name = "EMAIL_PLATFORM_ISSUER", value = var.platform_public_issuer },
    { name = "EMAIL_PLATFORM_JWK_SET_URI", value = var.platform_internal_jwks },
    { name = "EMAIL_OIDC_ISSUER_URI", value = var.keycloak_public_issuer },
    { name = "EMAIL_OIDC_JWK_SET_URI", value = var.keycloak_internal_jwks },
    { name = "PLATFORM_EMAIL_SERVICE_CLIENT_ID", value = "email" },
    { name = "EMAIL_PORT", value = "8080" },
    { name = "EMAIL_TRACKING_KEY_ID", value = "k1" },
    { name = "EMAIL_PUBLIC_BASE_URL", value = var.email_public_base_url },
  ]

  common_secrets = [
    { name = "EMAIL_DB_PASSWORD", valueFrom = var.secret_arns["EMAIL_DB_PASSWORD"] },
    { name = "EMAIL_MIGRATION_DB_PASSWORD", valueFrom = var.secret_arns["EMAIL_MIGRATION_DB_PASSWORD"] },
    { name = "EMAIL_RABBITMQ_PASSWORD", valueFrom = var.secret_arns["EMAIL_RABBITMQ_PASSWORD"] },
    { name = "PLATFORM_EMAIL_SERVICE_CLIENT_SECRET", valueFrom = var.secret_arns["EMAIL_REPLICA_CLIENT_SECRET"] },
    { name = "EMAIL_TRACKING_SECRET", valueFrom = var.secret_arns["EMAIL_TRACKING_SECRET"] },
  ]

  ready_probe = ["CMD", "bash", "-c", "exec 3<>/dev/tcp/localhost/8080 && printf 'GET /actuator/health/readiness HTTP/1.0\\r\\n\\r\\n' >&3 && head -20 <&3 | grep -q UP"]

  # The readiness group includes replicaState, so an api task that has never seen a completed rebuild stays down and
  # the worker is the only runtime that can bring it up.
  api_readiness = local.ready_probe
}

resource "aws_ecs_task_definition" "migrate" {
  family                   = "${var.environment}-email-migrate"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([
    {
      name      = "email-migrate"
      image     = var.image
      essential = true

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

resource "aws_ecs_task_definition" "api" {
  family                   = "${var.environment}-email-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([
    {
      name      = "email-api"
      image     = var.image
      essential = true

      portMappings = [
        { name = "http", containerPort = 8080, protocol = "tcp" }
      ]

      environment = concat(local.common_environment, [
        { name = "SPRING_PROFILES_ACTIVE", value = "api" },
      ])
      secrets = local.common_secrets

      healthCheck = {
        command     = local.api_readiness
        interval    = 15
        timeout     = 5
        retries     = 5
        startPeriod = 90
      }

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = var.log_group_names["api"]
          awslogs-region        = var.region
          awslogs-stream-prefix = "api"
        }
      }
    }
  ])
}

resource "aws_ecs_task_definition" "worker" {
  family                   = "${var.environment}-email"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([
    {
      name      = "email"
      image     = var.image
      essential = true

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

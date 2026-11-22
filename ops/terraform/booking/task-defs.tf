locals {
  # The settings all three runtimes read. The migrate profile turns web and jobs off and runs Flyway as the table
  # owner; the api profile serves HTTP and the public gate; the worker profile runs the platform-events listener, the
  # relay, webhook delivery and the jobs that expire holds, offers and waitlist entries. Nothing here sets a webhook
  # destination policy: the default is false and a deployment leaves it so.
  common_environment = [
    { name = "JAVA_TOOL_OPTIONS", value = "-XX:MaxRAMPercentage=50" },
    { name = "BOOKING_DB_URL", value = "jdbc:postgresql://${var.db_host}:${var.db_port}/booking?sslmode=require" },
    { name = "BOOKING_DB_USER", value = "booking_app" },
    { name = "BOOKING_MIGRATION_DB_USER", value = "booking_owner" },
    { name = "BOOKING_RABBITMQ_HOST", value = var.broker_host },
    { name = "BOOKING_RABBITMQ_PORT", value = tostring(var.broker_port) },
    { name = "BOOKING_RABBITMQ_USER", value = "booking" },
    { name = "SPRING_RABBITMQ_SSL_ENABLED", value = "true" },
    { name = "BOOKING_PLATFORM_BASE_URL", value = var.platform_internal_url },
    { name = "BOOKING_PLATFORM_ISSUER", value = var.platform_public_issuer },
    { name = "BOOKING_PLATFORM_JWK_SET_URI", value = var.platform_internal_jwks },
    { name = "BOOKING_OIDC_ISSUER_URI", value = var.keycloak_public_issuer },
    { name = "BOOKING_OIDC_JWK_SET_URI", value = var.keycloak_internal_jwks },
    { name = "PLATFORM_BOOKING_SERVICE_CLIENT_ID", value = "booking" },
    { name = "BOOKING_PORT", value = "8080" },
    { name = "BOOKING_TOKEN_KEY_ID", value = "k1" },
  ]

  common_secrets = [
    { name = "BOOKING_DB_PASSWORD", valueFrom = var.secret_arns["BOOKING_DB_PASSWORD"] },
    { name = "BOOKING_MIGRATION_DB_PASSWORD", valueFrom = var.secret_arns["BOOKING_MIGRATION_DB_PASSWORD"] },
    { name = "BOOKING_RABBITMQ_PASSWORD", valueFrom = var.secret_arns["BOOKING_RABBITMQ_PASSWORD"] },
    { name = "PLATFORM_BOOKING_SERVICE_CLIENT_SECRET", valueFrom = var.secret_arns["BOOKING_REPLICA_CLIENT_SECRET"] },
    { name = "BOOKING_TOKEN_SECRET", valueFrom = var.secret_arns["BOOKING_TOKEN_SECRET"] },
    { name = "BOOKING_SECRETS_MASTER_KEY", valueFrom = var.secret_arns["BOOKING_SECRETS_MASTER_KEY"] },
  ]

  ready_probe = ["CMD", "bash", "-c", "exec 3<>/dev/tcp/localhost/8080 && printf 'GET /actuator/health/readiness HTTP/1.0\\r\\n\\r\\n' >&3 && head -20 <&3 | grep -q UP"]
}

resource "aws_ecs_task_definition" "migrate" {
  family                   = "${var.environment}-booking-migrate"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([
    {
      name      = "booking-migrate"
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
  family                   = "${var.environment}-booking-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([
    {
      name      = "booking-api"
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
        command     = local.ready_probe
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
  family                   = "${var.environment}-booking-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([
    {
      name      = "booking-worker"
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

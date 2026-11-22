locals {
  # The settings all three runtimes read. The migrate profile turns web and jobs off and runs Flyway as the table
  # owner; the api profile serves HTTP, the public redirect and the collection endpoint; the worker profile runs the
  # platform-events listener, the ingest consumer, the partition, retention, credit and rollup jobs and the relay. No
  # GeoIP file is mounted, so a click's country stays null; the reader treats an absent file as allowed. The click key
  # and the visitor hash key have no default on purpose: the app refuses to start without them.
  common_environment = [
    { name = "JAVA_TOOL_OPTIONS", value = "-XX:MaxRAMPercentage=50" },
    { name = "ATTRIBUTION_DB_URL", value = "jdbc:postgresql://${var.db_host}:${var.db_port}/attribution?sslmode=require" },
    { name = "ATTRIBUTION_DB_USER", value = "attribution_app" },
    { name = "ATTRIBUTION_MIGRATION_DB_USER", value = "attribution_owner" },
    { name = "ATTRIBUTION_RABBITMQ_HOST", value = var.broker_host },
    { name = "ATTRIBUTION_RABBITMQ_PORT", value = tostring(var.broker_port) },
    { name = "ATTRIBUTION_RABBITMQ_USER", value = "attribution" },
    { name = "SPRING_RABBITMQ_SSL_ENABLED", value = "true" },
    { name = "ATTRIBUTION_PLATFORM_BASE_URL", value = var.platform_internal_url },
    { name = "ATTRIBUTION_PLATFORM_ISSUER", value = var.platform_public_issuer },
    { name = "ATTRIBUTION_PLATFORM_JWK_SET_URI", value = var.platform_internal_jwks },
    { name = "ATTRIBUTION_OIDC_ISSUER_URI", value = var.keycloak_public_issuer },
    { name = "ATTRIBUTION_OIDC_JWK_SET_URI", value = var.keycloak_internal_jwks },
    { name = "PLATFORM_ATTRIBUTION_SERVICE_CLIENT_ID", value = "attribution" },
    { name = "ATTRIBUTION_PORT", value = "8080" },
    { name = "ATTRIBUTION_PUBLIC_BASE_URL", value = var.public_base_url },
    { name = "ATTRIBUTION_CLICK_KEY_ID", value = "k1" },
  ]

  common_secrets = [
    { name = "ATTRIBUTION_DB_PASSWORD", valueFrom = var.secret_arns["ATTRIBUTION_DB_PASSWORD"] },
    { name = "ATTRIBUTION_MIGRATION_DB_PASSWORD", valueFrom = var.secret_arns["ATTRIBUTION_MIGRATION_DB_PASSWORD"] },
    { name = "ATTRIBUTION_RABBITMQ_PASSWORD", valueFrom = var.secret_arns["ATTRIBUTION_RABBITMQ_PASSWORD"] },
    { name = "PLATFORM_ATTRIBUTION_SERVICE_CLIENT_SECRET", valueFrom = var.secret_arns["ATTRIBUTION_REPLICA_CLIENT_SECRET"] },
    { name = "ATTRIBUTION_CLICK_SECRET", valueFrom = var.secret_arns["ATTRIBUTION_CLICK_SECRET"] },
    { name = "ATTRIBUTION_VISITOR_HASH_KEY", valueFrom = var.secret_arns["ATTRIBUTION_VISITOR_HASH_KEY"] },
  ]

  ready_probe = ["CMD", "bash", "-c", "exec 3<>/dev/tcp/localhost/8080 && printf 'GET /actuator/health/readiness HTTP/1.0\\r\\n\\r\\n' >&3 && head -20 <&3 | grep -q UP"]
}

resource "aws_ecs_task_definition" "migrate" {
  family                   = "${var.environment}-attribution-migrate"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([
    {
      name      = "attribution-migrate"
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
  family                   = "${var.environment}-attribution-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([
    {
      name      = "attribution-api"
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
  family                   = "${var.environment}-attribution-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([
    {
      name      = "attribution-worker"
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

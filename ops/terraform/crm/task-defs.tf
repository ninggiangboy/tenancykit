locals {
  # The settings all three runtimes read. The migrate profile turns web and jobs off and runs Flyway as the table
  # owner; the api profile serves HTTP; the worker profile runs the listener, the relay, the delivery round and
  # housekeeping. Nothing here sets a webhook destination policy: crm's default is false, and a deployment leaves it so.
  common_environment = [
    { name = "JAVA_TOOL_OPTIONS", value = "-XX:MaxRAMPercentage=50" },
    { name = "CRM_DB_URL", value = "jdbc:postgresql://${var.db_host}:${var.db_port}/crm?sslmode=require" },
    { name = "CRM_DB_USER", value = "crm_app" },
    { name = "CRM_MIGRATION_DB_USER", value = "crm_owner" },
    { name = "CRM_RABBITMQ_HOST", value = var.broker_host },
    { name = "CRM_RABBITMQ_PORT", value = tostring(var.broker_port) },
    { name = "CRM_RABBITMQ_USER", value = "crm" },
    { name = "SPRING_RABBITMQ_SSL_ENABLED", value = "true" },
    { name = "CRM_PLATFORM_BASE_URL", value = var.platform_internal_url },
    { name = "CRM_PLATFORM_ISSUER", value = var.platform_public_issuer },
    { name = "CRM_PLATFORM_JWK_SET_URI", value = var.platform_internal_jwks },
    { name = "CRM_OIDC_ISSUER_URI", value = var.keycloak_public_issuer },
    { name = "CRM_OIDC_JWK_SET_URI", value = var.keycloak_internal_jwks },
    { name = "PLATFORM_CRM_SERVICE_CLIENT_ID", value = "crm" },
    { name = "CRM_PORT", value = "8080" },
    { name = "CRM_PUBLIC_BASE_URL", value = var.public_base_url },
  ]

  common_secrets = [
    { name = "CRM_DB_PASSWORD", valueFrom = var.secret_arns["CRM_DB_PASSWORD"] },
    { name = "CRM_MIGRATION_DB_PASSWORD", valueFrom = var.secret_arns["CRM_MIGRATION_DB_PASSWORD"] },
    { name = "CRM_RABBITMQ_PASSWORD", valueFrom = var.secret_arns["CRM_RABBITMQ_PASSWORD"] },
    { name = "PLATFORM_CRM_SERVICE_CLIENT_SECRET", valueFrom = var.secret_arns["CRM_REPLICA_CLIENT_SECRET"] },
    { name = "CRM_SECRETS_MASTER_KEY", valueFrom = var.secret_arns["CRM_SECRETS_MASTER_KEY"] },
  ]

  ready_probe = ["CMD", "bash", "-c", "exec 3<>/dev/tcp/localhost/8080 && printf 'GET /actuator/health/readiness HTTP/1.0\\r\\n\\r\\n' >&3 && head -20 <&3 | grep -q UP"]
}

resource "aws_ecs_task_definition" "migrate" {
  family                   = "${var.environment}-crm-migrate"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([
    {
      name      = "crm-migrate"
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
  family                   = "${var.environment}-crm-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([
    {
      name      = "crm-api"
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
  family                   = "${var.environment}-crm-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([
    {
      name      = "crm-worker"
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

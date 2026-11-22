locals {
  # The settings all three runtimes read. The migrate profile runs Flyway as the table owner and exits; the api profile
  # serves HTTP; the worker profile owns the claim loop, the sweepers, the relay, webhook delivery, task deadlines and
  # retention. Workflow is the Tenant side of the suite: a node calls an app on the Tenant's own hostname with a token
  # minted at Platform's public token endpoint, so every app address here is public and the destination policy that
  # refuses a private address stays on.
  common_environment = [
    { name = "JAVA_TOOL_OPTIONS", value = "-XX:MaxRAMPercentage=50" },
    { name = "WORKFLOW_DB_URL", value = "jdbc:postgresql://${var.db_host}:${var.db_port}/workflow?sslmode=require" },
    { name = "WORKFLOW_DB_USER", value = "workflow_app" },
    { name = "WORKFLOW_MIGRATION_DB_USER", value = "workflow_owner" },
    { name = "WORKFLOW_RABBITMQ_HOST", value = var.broker_host },
    { name = "WORKFLOW_RABBITMQ_PORT", value = tostring(var.broker_port) },
    { name = "WORKFLOW_RABBITMQ_USER", value = "workflow" },
    { name = "SPRING_RABBITMQ_SSL_ENABLED", value = "true" },
    { name = "WORKFLOW_PLATFORM_BASE_URL", value = var.platform_internal_url },
    { name = "WORKFLOW_PLATFORM_ISSUER", value = var.platform_public_issuer },
    { name = "WORKFLOW_PLATFORM_JWK_SET_URI", value = var.platform_internal_jwks },
    { name = "WORKFLOW_OIDC_ISSUER_URI", value = var.keycloak_public_issuer },
    { name = "WORKFLOW_OIDC_JWK_SET_URI", value = var.keycloak_internal_jwks },
    { name = "PLATFORM_WORKFLOW_SERVICE_CLIENT_ID", value = "workflow" },
    { name = "WORKFLOW_PORT", value = "8080" },
    { name = "WORKFLOW_SECRET_KEY_ID", value = "k1" },
    { name = "WORKFLOW_TOKEN_URL", value = var.token_url },
    { name = "WORKFLOW_BILLING_URL", value = var.app_urls["billing"] },
    { name = "WORKFLOW_MAILROOM_URL", value = var.app_urls["mailroom"] },
    { name = "WORKFLOW_CUSTOMER_URL", value = var.app_urls["customer"] },
    { name = "WORKFLOW_LOYALTY_URL", value = var.app_urls["loyalty"] },
    { name = "WORKFLOW_SURVEY_URL", value = var.app_urls["survey"] },
  ]

  common_secrets = [
    { name = "WORKFLOW_DB_PASSWORD", valueFrom = var.secret_arns["WORKFLOW_DB_PASSWORD"] },
    { name = "WORKFLOW_MIGRATION_DB_PASSWORD", valueFrom = var.secret_arns["WORKFLOW_MIGRATION_DB_PASSWORD"] },
    { name = "WORKFLOW_RABBITMQ_PASSWORD", valueFrom = var.secret_arns["WORKFLOW_RABBITMQ_PASSWORD"] },
    { name = "PLATFORM_WORKFLOW_SERVICE_CLIENT_SECRET", valueFrom = var.secret_arns["WORKFLOW_REPLICA_CLIENT_SECRET"] },
    { name = "WORKFLOW_SECRET_KEY", valueFrom = var.secret_arns["WORKFLOW_SECRET_KEY"] },
  ]

  ready_probe = ["CMD", "bash", "-c", "exec 3<>/dev/tcp/localhost/8080 && printf 'GET /actuator/health/readiness HTTP/1.0\\r\\n\\r\\n' >&3 && head -20 <&3 | grep -q UP"]
}

resource "aws_ecs_task_definition" "migrate" {
  family                   = "${var.environment}-workflow-migrate"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([
    {
      name      = "workflow-migrate"
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
  family                   = "${var.environment}-workflow-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([
    {
      name      = "workflow-api"
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
  family                   = "${var.environment}-workflow-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([
    {
      name      = "workflow-worker"
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

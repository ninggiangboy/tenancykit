locals {
  # The same settings every runtime reads. The migrate profile switches Flyway on and web and jobs off, but the context
  # still binds every property at startup, so the three task definitions share this block.
  common_environment = [
    { name = "JAVA_TOOL_OPTIONS", value = "-XX:MaxRAMPercentage=50" },
    { name = "BILLING_DB_URL", value = "jdbc:postgresql://${var.db_host}:${var.db_port}/billing?sslmode=require" },
    { name = "BILLING_DB_USER", value = "billing_app" },
    { name = "BILLING_MIGRATION_DB_USER", value = "billing_owner" },
    { name = "BILLING_RABBITMQ_HOST", value = var.broker_host },
    { name = "BILLING_RABBITMQ_PORT", value = tostring(var.broker_port) },
    { name = "BILLING_RABBITMQ_USER", value = "billing" },
    { name = "SPRING_RABBITMQ_SSL_ENABLED", value = "true" },
    { name = "BILLING_OIDC_ISSUER_URI", value = var.keycloak_public_issuer },
    { name = "BILLING_OIDC_JWK_SET_URI", value = var.keycloak_internal_jwks },
    # The address Billing calls is the internal one; the issuer it must accept stays public.
    { name = "BILLING_PLATFORM_BASE_URL", value = var.platform_internal_url },
    { name = "BILLING_PLATFORM_JWK_SET_URI", value = var.platform_internal_jwks },
    { name = "BILLING_PLATFORM_ISSUER", value = var.platform_public_issuer },
    { name = "BILLING_REPLICA_CLIENT_ID", value = "billing" },
    { name = "BILLING_BASE_DOMAIN", value = var.base_domain },
    { name = "BILLING_PUBLIC_BASE_URL", value = var.public_base_url },
    # Both are production values: webhooks may not target private or insecure addresses, and the portal cookie is
    # Secure. Compose flips them for localhost only.
    { name = "BILLING_WEBHOOKS_ALLOW_INSECURE_TARGETS", value = "false" },
    { name = "BILLING_PORTAL_SECURE_COOKIE", value = "true" },
  ]

  common_secrets = [
    { name = "BILLING_DB_PASSWORD", valueFrom = var.secret_arns["BILLING_DB_PASSWORD"] },
    { name = "BILLING_MIGRATION_DB_PASSWORD", valueFrom = var.secret_arns["BILLING_MIGRATION_DB_PASSWORD"] },
    { name = "BILLING_RABBITMQ_PASSWORD", valueFrom = var.secret_arns["BILLING_RABBITMQ_PASSWORD"] },
    { name = "BILLING_REPLICA_CLIENT_SECRET", valueFrom = var.secret_arns["BILLING_REPLICA_CLIENT_SECRET"] },
    { name = "BILLING_SECRETS_MASTER_KEY", valueFrom = var.secret_arns["BILLING_SECRETS_MASTER_KEY"] },
  ]

  ready_probe = ["CMD", "bash", "-c", "exec 3<>/dev/tcp/localhost/8080 && printf 'GET /actuator/health/readiness HTTP/1.0\\r\\n\\r\\n' >&3 && head -20 <&3 | grep -q UP"]
}

resource "aws_ecs_task_definition" "migrate" {
  family                   = "${var.environment}-billing-migrate"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([
    {
      name      = "billing-migrate"
      image     = var.image
      essential = true

      # Flyway runs as the table owner; api and worker connect as billing_app with Flyway off.
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
  family                   = "${var.environment}-billing-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([
    {
      name      = "billing"
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
  family                   = "${var.environment}-billing-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([
    {
      name      = "billing-worker"
      image     = var.image
      essential = true

      # No port mapping: the worker serves no business HTTP. It keeps the actuator probe and deliberately does not
      # enable virtual threads, which would replace the scheduler the jobs rely on.
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

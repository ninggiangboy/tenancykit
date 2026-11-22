locals {
  container_environment = [
    { name = "JAVA_TOOL_OPTIONS", value = "-XX:MaxRAMPercentage=50" },
    { name = "PLATFORM_DB_URL", value = "jdbc:postgresql://${var.db_host}:${var.db_port}/platform?sslmode=require" },
    # The runtime connects as the unprivileged app role; Flyway migrates on start with the owner credentials below.
    { name = "PLATFORM_DB_USER", value = "platform_app" },
    { name = "PLATFORM_MIGRATION_DB_USER", value = "platform_owner" },
    { name = "PLATFORM_RABBITMQ_HOST", value = var.broker_host },
    { name = "PLATFORM_RABBITMQ_PORT", value = tostring(var.broker_port) },
    { name = "PLATFORM_RABBITMQ_USER", value = "platform" },
    { name = "SPRING_RABBITMQ_SSL_ENABLED", value = "true" },
    { name = "PLATFORM_KEYCLOAK_URL", value = var.keycloak_internal_url },
    { name = "PLATFORM_KEYCLOAK_REALM", value = "tenancykit" },
    { name = "PLATFORM_KEYCLOAK_ADMIN_CLIENT_ID", value = "platform-admin" },
    { name = "PLATFORM_BASE_DOMAIN", value = var.base_domain },
    { name = "PLATFORM_HOSTNAME", value = var.public_host },
    # Without the internal FQDN here, Billing's service-to-service call arrives with a Host that resolves to no tenant
    # and gets a 404.
    { name = "PLATFORM_INTERNAL_HOSTNAMES", value = var.internal_hostname },
    { name = "PLATFORM_TOKEN_ISSUER", value = var.public_issuer },
    { name = "PLATFORM_TOKEN_ALLOW_EPHEMERAL_KEY", value = "false" },
    { name = "PLATFORM_OIDC_ISSUER_URI", value = var.keycloak_public_issuer },
    { name = "PLATFORM_OIDC_JWK_SET_URI", value = var.keycloak_internal_jwks },
    { name = "PLATFORM_SERVICE_CLIENT_ID", value = "billing" },
    { name = "PLATFORM_NOTIFICATION_SERVICE_CLIENT_ID", value = "notification" },
    { name = "PLATFORM_BOOTSTRAP_ADMIN_EMAIL", value = var.admin_email },
  ]

  container_secrets = [
    { name = "PLATFORM_DB_PASSWORD", valueFrom = var.secret_arns["PLATFORM_DB_PASSWORD"] },
    { name = "PLATFORM_MIGRATION_DB_PASSWORD", valueFrom = var.secret_arns["PLATFORM_MIGRATION_DB_PASSWORD"] },
    { name = "PLATFORM_RABBITMQ_PASSWORD", valueFrom = var.secret_arns["PLATFORM_RABBITMQ_PASSWORD"] },
    { name = "PLATFORM_KEYCLOAK_ADMIN_CLIENT_SECRET", valueFrom = var.secret_arns["PLATFORM_KEYCLOAK_ADMIN_CLIENT_SECRET"] },
    { name = "PLATFORM_TOKEN_SIGNING_KEY", valueFrom = var.secret_arns["PLATFORM_TOKEN_SIGNING_KEY"] },
    { name = "PLATFORM_SERVICE_CLIENT_SECRET_HASH", valueFrom = var.secret_arns["PLATFORM_SERVICE_CLIENT_SECRET_HASH"] },
    { name = "PLATFORM_NOTIFICATION_SERVICE_CLIENT_SECRET_HASH", valueFrom = var.secret_arns["PLATFORM_NOTIFICATION_SERVICE_CLIENT_SECRET_HASH"] },
    # One replica client per app. Platform's client ids default to the app's name, so only the hash needs to arrive; an
    # app whose hash is missing here cannot authenticate its replica, and its readiness never turns UP.
    { name = "PLATFORM_MAILROOM_SERVICE_CLIENT_SECRET_HASH", valueFrom = var.secret_arns["PLATFORM_MAILROOM_SERVICE_CLIENT_SECRET_HASH"] },
    { name = "PLATFORM_CUSTOMER_SERVICE_CLIENT_SECRET_HASH", valueFrom = var.secret_arns["PLATFORM_CUSTOMER_SERVICE_CLIENT_SECRET_HASH"] },
    { name = "PLATFORM_LOYALTY_SERVICE_CLIENT_SECRET_HASH", valueFrom = var.secret_arns["PLATFORM_LOYALTY_SERVICE_CLIENT_SECRET_HASH"] },
    { name = "PLATFORM_SURVEY_SERVICE_CLIENT_SECRET_HASH", valueFrom = var.secret_arns["PLATFORM_SURVEY_SERVICE_CLIENT_SECRET_HASH"] },
    { name = "PLATFORM_BOOKING_SERVICE_CLIENT_SECRET_HASH", valueFrom = var.secret_arns["PLATFORM_BOOKING_SERVICE_CLIENT_SECRET_HASH"] },
    { name = "PLATFORM_WORKFLOW_SERVICE_CLIENT_SECRET_HASH", valueFrom = var.secret_arns["PLATFORM_WORKFLOW_SERVICE_CLIENT_SECRET_HASH"] },
    { name = "PLATFORM_ATTRIBUTION_SERVICE_CLIENT_SECRET_HASH", valueFrom = var.secret_arns["PLATFORM_ATTRIBUTION_SERVICE_CLIENT_SECRET_HASH"] },
    { name = "PLATFORM_BOOTSTRAP_ADMIN_TEMPORARY_PASSWORD", valueFrom = var.secret_arns["PLATFORM_BOOTSTRAP_ADMIN_TEMPORARY_PASSWORD"] },
  ]
}

resource "aws_ecs_task_definition" "this" {
  family                   = "${var.environment}-platform"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([
    {
      name      = "platform"
      image     = var.image
      essential = true

      portMappings = [
        { name = "http", containerPort = 8080, protocol = "tcp" }
      ]

      environment = local.container_environment
      secrets     = local.container_secrets

      healthCheck = {
        command     = ["CMD", "bash", "-c", "exec 3<>/dev/tcp/localhost/8080 && printf 'GET /actuator/health/readiness HTTP/1.0\\r\\n\\r\\n' >&3 && head -20 <&3 | grep -q UP"]
        interval    = 15
        timeout     = 5
        retries     = 5
        startPeriod = 90
      }

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = var.log_group_name
          awslogs-region        = var.region
          awslogs-stream-prefix = "platform"
        }
      }
    }
  ])
}

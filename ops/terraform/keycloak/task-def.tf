locals {
  container_environment = [
    { name = "KC_DB", value = "postgres" },
    { name = "KC_DB_URL", value = "jdbc:postgresql://${var.db_host}:${var.db_port}/keycloak?sslmode=require" },
    { name = "KC_DB_USERNAME", value = "keycloak_owner" },
    # The issuer must be the address the user signs in at. The ALB terminates TLS and forwards the original scheme, so
    # Keycloak stamps the public URL into every token instead of the internal Service Connect name.
    { name = "KC_HOSTNAME", value = var.public_url },
    { name = "KC_HOSTNAME_STRICT", value = "true" },
    { name = "KC_HTTP_ENABLED", value = "true" },
    { name = "KC_PROXY_HEADERS", value = "xforwarded" },
    { name = "KC_BOOTSTRAP_ADMIN_USERNAME", value = "admin" },
    { name = "KC_LOG_LEVEL", value = "INFO" },
  ]

  container_secrets = [
    { name = "KC_DB_PASSWORD", valueFrom = var.secret_arns["KEYCLOAK_DB_PASSWORD"] },
    { name = "KC_BOOTSTRAP_ADMIN_PASSWORD", valueFrom = var.secret_arns["KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD"] },
    # The realm export substitutes these two placeholders when it imports.
    { name = "PLATFORM_KEYCLOAK_ADMIN_CLIENT_SECRET", valueFrom = var.secret_arns["PLATFORM_KEYCLOAK_ADMIN_CLIENT_SECRET"] },
    { name = "SMOKE_CLIENT_SECRET", valueFrom = var.secret_arns["SMOKE_CLIENT_SECRET"] },
  ]
}

resource "aws_ecs_task_definition" "this" {
  family                   = "${var.environment}-keycloak"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([
    {
      name      = "keycloak"
      image     = var.image
      essential = true
      # Production mode: `start` reads the database settings, unlike the dev entrypoint, which forces the embedded store.
      command = ["start", "--import-realm"]

      portMappings = [
        { name = "http", containerPort = 8080, protocol = "tcp" }
      ]

      environment = local.container_environment
      secrets     = local.container_secrets

      healthCheck = {
        command     = ["CMD-SHELL", "exec 3<>/dev/tcp/localhost/8080 && printf 'GET /realms/master HTTP/1.0\\r\\n\\r\\n' >&3 && head -1 <&3 | grep -q '200'"]
        interval    = 15
        timeout     = 5
        retries     = 10
        startPeriod = 120
      }

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = var.log_group_name
          awslogs-region        = var.region
          awslogs-stream-prefix = "keycloak"
        }
      }
    }
  ])
}

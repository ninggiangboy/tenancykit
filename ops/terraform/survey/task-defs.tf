locals {
  # The settings all three runtimes read. The migrate profile turns web and jobs off and runs Flyway as the table
  # owner; the api profile serves HTTP and the public fill surface; the worker profile runs the platform-events
  # listener, the relay, webhook delivery, export, abandon and housekeeping jobs. Uploads go to the S3 bucket in
  # storage.tf: the server and a respondent's browser both reach the regional endpoint, which is why the two endpoints
  # are the same address here and differ only in compose. No GeoIP file is mounted, so every country stays null; the
  # reader treats an absent file as allowed and the health detail says so. Nothing here sets a webhook destination
  # policy: the default is false and a deployment leaves it so.
  common_environment = [
    { name = "JAVA_TOOL_OPTIONS", value = "-XX:MaxRAMPercentage=50" },
    { name = "SURVEY_DB_URL", value = "jdbc:postgresql://${var.db_host}:${var.db_port}/survey?sslmode=require" },
    { name = "SURVEY_DB_USER", value = "survey_app" },
    { name = "SURVEY_MIGRATION_DB_USER", value = "survey_owner" },
    { name = "SURVEY_RABBITMQ_HOST", value = var.broker_host },
    { name = "SURVEY_RABBITMQ_PORT", value = tostring(var.broker_port) },
    { name = "SURVEY_RABBITMQ_USER", value = "survey" },
    { name = "SPRING_RABBITMQ_SSL_ENABLED", value = "true" },
    { name = "SURVEY_PLATFORM_BASE_URL", value = var.platform_internal_url },
    { name = "SURVEY_PLATFORM_ISSUER", value = var.platform_public_issuer },
    { name = "SURVEY_PLATFORM_JWK_SET_URI", value = var.platform_internal_jwks },
    { name = "SURVEY_OIDC_ISSUER_URI", value = var.keycloak_public_issuer },
    { name = "SURVEY_OIDC_JWK_SET_URI", value = var.keycloak_internal_jwks },
    { name = "PLATFORM_SURVEY_SERVICE_CLIENT_ID", value = "survey" },
    { name = "SURVEY_PORT", value = "8080" },
    { name = "SURVEY_PUBLIC_BASE_URL", value = var.public_base_url },
    { name = "SURVEY_LINK_KEY_ID", value = "k1" },
    { name = "SURVEY_S3_ENDPOINT", value = "https://s3.${var.region}.amazonaws.com" },
    { name = "SURVEY_S3_PUBLIC_ENDPOINT", value = "https://s3.${var.region}.amazonaws.com" },
    { name = "SURVEY_S3_BUCKET", value = aws_s3_bucket.uploads.bucket },
    { name = "SURVEY_S3_REGION", value = var.region },
    { name = "SURVEY_S3_ACCESS_KEY", value = aws_iam_access_key.uploads.id },
  ]

  common_secrets = [
    { name = "SURVEY_DB_PASSWORD", valueFrom = var.secret_arns["SURVEY_DB_PASSWORD"] },
    { name = "SURVEY_MIGRATION_DB_PASSWORD", valueFrom = var.secret_arns["SURVEY_MIGRATION_DB_PASSWORD"] },
    { name = "SURVEY_RABBITMQ_PASSWORD", valueFrom = var.secret_arns["SURVEY_RABBITMQ_PASSWORD"] },
    { name = "PLATFORM_SURVEY_SERVICE_CLIENT_SECRET", valueFrom = var.secret_arns["SURVEY_REPLICA_CLIENT_SECRET"] },
    { name = "SURVEY_LINK_SECRET", valueFrom = var.secret_arns["SURVEY_LINK_SECRET"] },
    { name = "SURVEY_SECRETS_MASTER_KEY", valueFrom = var.secret_arns["SURVEY_SECRETS_MASTER_KEY"] },
    { name = "SURVEY_S3_SECRET_KEY", valueFrom = aws_secretsmanager_secret_version.s3_secret_key.arn },
  ]

  ready_probe = ["CMD", "bash", "-c", "exec 3<>/dev/tcp/localhost/8080 && printf 'GET /actuator/health/readiness HTTP/1.0\\r\\n\\r\\n' >&3 && head -20 <&3 | grep -q UP"]
}

resource "aws_ecs_task_definition" "migrate" {
  family                   = "${var.environment}-survey-migrate"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([
    {
      name      = "survey-migrate"
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
  family                   = "${var.environment}-survey-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([
    {
      name      = "survey-api"
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
  family                   = "${var.environment}-survey-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([
    {
      name      = "survey-worker"
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

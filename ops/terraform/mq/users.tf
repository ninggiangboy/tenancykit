locals {
  endpoints           = aws_mq_broker.this.instances[0].endpoints
  amqp_endpoint       = one([for endpoint in local.endpoints : endpoint if startswith(endpoint, "amqps://")])
  management_endpoint = one([for endpoint in local.endpoints : endpoint if startswith(endpoint, "https://")])

  amqp_address = split(":", replace(local.amqp_endpoint, "amqps://", ""))
  amqp_host    = local.amqp_address[0]
  amqp_port    = tonumber(local.amqp_address[1])

  management_url = "https://${split(":", replace(local.management_endpoint, "https://", ""))[0]}"
}

# Amazon MQ for RabbitMQ accepts users on the broker, but it has no per-user resource permissions: the exchange and
# queue limits that ops/rabbitmq/declare-users.sh sets have no equivalent in the broker resource. The script runs as a
# one-shot task against the management API after the broker exists, so the broker ends up with the same three scoped
# users as the compose stack. The broker's `user` blocks only guarantee the users exist with the right passwords.
resource "aws_ecs_task_definition" "users" {
  family                   = "${var.environment}-mq-users"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = var.execution_role_arn

  container_definitions = jsonencode([
    {
      name       = "mq-users"
      image      = var.users_image
      essential  = true
      entryPoint = ["sh"]
      command    = ["-c", file("${path.module}/../../rabbitmq/declare-users.sh")]

      environment = [
        { name = "BROKER_URL", value = local.management_url },
        { name = "ADMIN_USER", value = "admin" },
      ]

      secrets = [
        { name = "ADMIN_PASSWORD", valueFrom = var.secret_arns["MQ_ADMIN_PASSWORD"] },
        { name = "PLATFORM_BROKER_PASSWORD", valueFrom = var.secret_arns["PLATFORM_RABBITMQ_PASSWORD"] },
        { name = "BILLING_BROKER_PASSWORD", valueFrom = var.secret_arns["BILLING_RABBITMQ_PASSWORD"] },
        { name = "MAILER_BROKER_PASSWORD", valueFrom = var.secret_arns["MAILER_RABBITMQ_PASSWORD"] },
        { name = "NOTIFICATION_BROKER_PASSWORD", valueFrom = var.secret_arns["NOTIFICATION_RABBITMQ_PASSWORD"] },
        { name = "EMAIL_BROKER_PASSWORD", valueFrom = var.secret_arns["EMAIL_RABBITMQ_PASSWORD"] },
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = var.log_group_name
          awslogs-region        = var.region
          awslogs-stream-prefix = "users"
        }
      }
    }
  ])
}

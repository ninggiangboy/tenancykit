resource "aws_ecs_service" "this" {
  name            = "${var.environment}-keycloak"
  cluster         = var.cluster_arn
  task_definition = aws_ecs_task_definition.this.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  health_check_grace_period_seconds  = 180
  deployment_minimum_healthy_percent = 50
  deployment_maximum_percent         = 200

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [var.task_security_group_id]
    assign_public_ip = false
  }

  # Service Connect gives every task a DNS name in the namespace, so Platform and Billing reach Keycloak at
  # keycloak.<namespace> without an ALB round trip or a Host rewrite.
  service_connect_configuration {
    enabled   = true
    namespace = var.service_connect_namespace_arn

    service {
      port_name = "http"

      client_alias {
        dns_name = "keycloak"
        port     = 8080
      }
    }
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.this.arn
    container_name   = "keycloak"
    container_port   = 8080
  }

  depends_on = [aws_lb_listener_rule.this]
}

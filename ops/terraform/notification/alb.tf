resource "aws_lb_target_group" "this" {
  name                 = "${var.environment}-notification-tg"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = var.vpc_id
  deregistration_delay = 30

  health_check {
    enabled             = true
    path                = "/actuator/health/readiness"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

# Mirrors Caddy's /notification/* route. ALB cannot strip a path prefix, so this rule forwards the path unchanged and
# the service's own controller mapping expects /api/v1/...; a caller that relies on the stripped form reaches the api
# service through Service Connect until a rewrite rule (or a dedicated host) is added. The limitation is the same one
# Platform's path rule documents.
resource "aws_lb_listener_rule" "path" {
  listener_arn = var.listener_arn
  priority     = 40

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this.arn
  }

  condition {
    path_pattern {
      values = ["/notification/*"]
    }
  }
}

resource "aws_lb_target_group" "this" {
  name                 = "${var.environment}-email-tg"
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

# Mirrors Caddy's /email/* route. ALB cannot strip a path prefix, so this rule forwards the path unchanged. The
# service serves /email/api/v1/... for that reason, and the public tracking controller maps both /t/... (the stripped
# form the compose proxy produces) and /email/t/... (the form this rule forwards), so a pixel, a click and an
# unsubscribe work in either runtime.
resource "aws_lb_listener_rule" "path" {
  listener_arn = var.listener_arn
  priority     = 50

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this.arn
  }

  condition {
    path_pattern {
      values = ["/email/*"]
    }
  }
}

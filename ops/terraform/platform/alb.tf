resource "aws_lb_target_group" "this" {
  name                 = "${var.environment}-platform-tg"
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

# Rule 10 mirrors Caddy's /platform/* route. ALB cannot strip a path prefix the way Caddy does, so this rule forwards
# the path unchanged; the admin host below is the root route that behaves exactly like compose. A caller that relies on
# the stripped form should use the admin host until the path rule is either dropped or backed by a rewrite.
resource "aws_lb_listener_rule" "path" {
  listener_arn = var.listener_arn
  priority     = 10

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this.arn
  }

  condition {
    path_pattern {
      values = ["/platform/*"]
    }
  }
}

# Rule 20: platform.<domain> serves Platform at its root, and the Host header is forwarded unchanged so tenant
# resolution still sees the public hostname.
resource "aws_lb_listener_rule" "host" {
  listener_arn = var.listener_arn
  priority     = 20

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this.arn
  }

  condition {
    host_header {
      values = [var.public_host]
    }
  }
}

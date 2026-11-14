# Default deny. Every rule below is written as its own resource so the ALB and task groups can reference each other
# without a cycle.

resource "aws_security_group" "alb" {
  name        = "${var.environment}-core-billing-alb"
  description = "Public entry point: HTTP and HTTPS from the internet, forwarding to tasks on 8080."
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${var.environment}-core-billing-alb"
  }
}

resource "aws_security_group" "task" {
  name        = "${var.environment}-core-billing-task"
  description = "Fargate tasks: 8080 from the ALB and from each other (Service Connect)."
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${var.environment}-core-billing-task"
  }
}

resource "aws_security_group" "data" {
  name        = "${var.environment}-core-billing-data"
  description = "RDS and Amazon MQ: reachable only from the tasks."
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${var.environment}-core-billing-data"
  }
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTP from the internet"

  cidr_ipv4   = "0.0.0.0/0"
  from_port   = 80
  to_port     = 80
  ip_protocol = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTPS from the internet"

  cidr_ipv4   = "0.0.0.0/0"
  from_port   = 443
  to_port     = 443
  ip_protocol = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "alb_to_tasks" {
  security_group_id            = aws_security_group.alb.id
  description                  = "Forward to task ports only"
  referenced_security_group_id = aws_security_group.task.id

  from_port   = 8080
  to_port     = 8080
  ip_protocol = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "task_from_alb" {
  security_group_id            = aws_security_group.task.id
  description                  = "Application traffic from the ALB"
  referenced_security_group_id = aws_security_group.alb.id

  from_port   = 8080
  to_port     = 8080
  ip_protocol = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "task_from_tasks" {
  security_group_id            = aws_security_group.task.id
  description                  = "Service Connect traffic between tasks"
  referenced_security_group_id = aws_security_group.task.id

  from_port   = 8080
  to_port     = 8080
  ip_protocol = "tcp"
}

# Pulling images, reading secrets, writing logs and reaching RDS/MQ all leave the task; a destination-scoped egress
# rule would have to repeat those endpoints, so this stays open in the development environment.
resource "aws_vpc_security_group_egress_rule" "task_all" {
  security_group_id = aws_security_group.task.id
  description       = "Egress for ECR, Secrets Manager, CloudWatch Logs, RDS and Amazon MQ"

  cidr_ipv4   = "0.0.0.0/0"
  ip_protocol = "-1"
}

resource "aws_vpc_security_group_ingress_rule" "data_postgres" {
  security_group_id            = aws_security_group.data.id
  description                  = "PostgreSQL from the tasks"
  referenced_security_group_id = aws_security_group.task.id

  from_port   = 5432
  to_port     = 5432
  ip_protocol = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "data_amqps" {
  security_group_id            = aws_security_group.data.id
  description                  = "Amazon MQ AMQPS from the tasks"
  referenced_security_group_id = aws_security_group.task.id

  from_port   = 5671
  to_port     = 5671
  ip_protocol = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "data_management" {
  security_group_id            = aws_security_group.data.id
  description                  = "Amazon MQ management API from the broker-users task"
  referenced_security_group_id = aws_security_group.task.id

  from_port   = 443
  to_port     = 443
  ip_protocol = "tcp"
}

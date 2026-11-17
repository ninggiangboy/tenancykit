resource "aws_mq_broker" "this" {
  broker_name         = var.name_prefix
  engine_type         = "RabbitMQ"
  engine_version      = var.engine_version
  host_instance_type  = var.instance_type
  deployment_mode     = "SINGLE_INSTANCE"
  publicly_accessible = false
  security_groups     = [var.data_security_group_id]
  subnet_ids          = [var.private_subnet_ids[0]]

  auto_minor_version_upgrade = true

  user {
    username = "admin"
    password = var.admin_password
  }

  # Amazon MQ takes users on the broker itself (there is no separate user resource). The broker ends up with the same
  # three service users as compose; their resource permissions are applied by the one-shot task in users.tf.
  user {
    username = "platform"
    password = var.platform_password
  }

  user {
    username = "billing"
    password = var.billing_password
  }

  # Mailer consumes commands and publishes one event to mailer.events; the one-shot task scopes this user to
  # mailer.commands, mailer.events, its dead-letter pair and the suite's dead-letter exchange.
  user {
    username = "mailer"
    password = var.mailer_password
  }

  # Notification consumes platform.events and publishes exactly one command to mailer.commands. The one-shot task
  # scopes this user to those resources and nothing else.
  user {
    username = "notification"
    password = var.notification_password
  }

  # Mailroom consumes platform.events and mailer.events on two queues of its own and publishes one command to
  # mailer.commands. The one-shot task scopes this user to those and to nothing else.
  user {
    username = "mailroom"
    password = var.mailroom_password
  }

  # customer and loyalty are replica consumers only: each binds one queue to platform.events, owns no exchange and publishes
  # nothing, because every fact they hold leaves as a signed webhook. The one-shot task therefore scopes each to its own
  # queue, its dead-letter pair, suite.dlx and a read grant on platform.events — no write grant on any exchange.
  user {
    username = "customer"
    password = var.customer_password
  }

  user {
    username = "loyalty"
    password = var.loyalty_password
  }
}

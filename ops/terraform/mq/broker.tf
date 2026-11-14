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
  # two service users as compose; their resource permissions are applied by the one-shot task in users.tf.
  user {
    username = "platform"
    password = var.platform_password
  }

  user {
    username = "billing"
    password = var.billing_password
  }
}

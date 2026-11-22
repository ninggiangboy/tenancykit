output "region" {
  description = "Region every resource lives in."
  value       = var.region
}

output "alb_dns_name" {
  description = "ALB DNS name. Point the public DNS records at it."
  value       = module.alb.alb_dns_name
}

output "public_url" {
  description = "Public base URL the ALB answers on."
  value       = module.alb.public_url
}

output "cluster_name" {
  description = "ECS cluster name, for `aws ecs run-task` and `update-service`."
  value       = module.ecs.cluster_name
}

output "private_subnet_ids" {
  description = "Private subnets a one-shot task runs in."
  value       = module.network.private_subnet_ids
}

output "task_security_group_id" {
  description = "Security group a one-shot task attaches."
  value       = module.network.task_security_group_id
}

output "ecr_repository_urls" {
  description = "ECR repositories the images are pushed to."
  value       = module.ecs.ecr_repository_urls
}

output "task_definition_arns" {
  description = "Task definitions the deploy pipeline runs or rolls out."
  value = {
    keycloak             = module.keycloak.task_definition_arn
    platform             = module.platform.task_definition_arn
    billing_api          = module.billing.api_task_definition_arn
    billing_worker       = module.billing.worker_task_definition_arn
    billing_migrate      = module.billing.migrate_task_definition_arn
    mailer               = module.mailer.worker_task_definition_arn
    mailer_migrate       = module.mailer.migrate_task_definition_arn
    notification_api     = module.notification.api_task_definition_arn
    notification_worker  = module.notification.worker_task_definition_arn
    notification_migrate = module.notification.migrate_task_definition_arn
    mailroom_api         = module.mailroom.api_task_definition_arn
    mailroom_worker      = module.mailroom.worker_task_definition_arn
    mailroom_migrate     = module.mailroom.migrate_task_definition_arn
    customer_api         = module.customer.api_task_definition_arn
    customer_worker      = module.customer.worker_task_definition_arn
    customer_migrate     = module.customer.migrate_task_definition_arn
    loyalty_api          = module.loyalty.api_task_definition_arn
    loyalty_worker       = module.loyalty.worker_task_definition_arn
    loyalty_migrate      = module.loyalty.migrate_task_definition_arn
    survey_api           = module.survey.api_task_definition_arn
    survey_worker        = module.survey.worker_task_definition_arn
    survey_migrate       = module.survey.migrate_task_definition_arn
    booking_api          = module.booking.api_task_definition_arn
    booking_worker       = module.booking.worker_task_definition_arn
    booking_migrate      = module.booking.migrate_task_definition_arn
    workflow_api         = module.workflow.api_task_definition_arn
    workflow_worker      = module.workflow.worker_task_definition_arn
    workflow_migrate     = module.workflow.migrate_task_definition_arn
    attribution_api      = module.attribution.api_task_definition_arn
    attribution_worker   = module.attribution.worker_task_definition_arn
    attribution_migrate  = module.attribution.migrate_task_definition_arn
    rds_init             = module.rds.init_task_definition_arn
    mq_users             = module.mq.users_task_definition_arn
  }
}

output "service_names" {
  description = "ECS service names the deploy pipeline rolls out."
  value = {
    keycloak            = module.keycloak.service_name
    platform            = module.platform.service_name
    billing_api         = module.billing.api_service_name
    billing_worker      = module.billing.worker_service_name
    mailer              = module.mailer.service_name
    notification_api    = module.notification.api_service_name
    notification_worker = module.notification.worker_service_name
    mailroom_api        = module.mailroom.api_service_name
    mailroom_worker     = module.mailroom.worker_service_name
    customer_api        = module.customer.api_service_name
    customer_worker     = module.customer.worker_service_name
    loyalty_api         = module.loyalty.api_service_name
    loyalty_worker      = module.loyalty.worker_service_name
    survey_api          = module.survey.api_service_name
    survey_worker       = module.survey.worker_service_name
    booking_api         = module.booking.api_service_name
    booking_worker      = module.booking.worker_service_name
    workflow_api        = module.workflow.api_service_name
    workflow_worker     = module.workflow.worker_service_name
    attribution_api     = module.attribution.api_service_name
    attribution_worker  = module.attribution.worker_service_name
  }
}

output "secret_arns" {
  description = "Secrets Manager ARNs. Values are never exported by Terraform."
  value       = module.secrets.arns
}

output "keycloak" {
  description = "Keycloak addresses: the public issuer users sign in at and the internal URL for server-to-server calls."
  value = {
    public_issuer = local.keycloak_public_issuer
    internal_url  = local.keycloak_internal_url
    internal_jwks = local.keycloak_internal_jwks
  }
}

output "platform" {
  description = "Platform addresses: the public issuer it stamps into tokens and the internal URL Billing calls."
  value = {
    public_issuer     = local.platform_public_issuer
    internal_url      = local.platform_internal_url
    internal_jwks     = local.platform_internal_jwks
    internal_hostname = local.internal_fqdn
  }
}

output "billing" {
  description = "Billing's public base URL."
  value       = local.billing_public_url
}

output "customer" {
  description = "Customer's public base URL."
  value       = local.customer_public_url
}

output "loyalty" {
  description = "Loyalty's public base URL."
  value       = local.loyalty_public_url
}

output "survey" {
  description = "Survey's public base URL and the bucket its uploads and exports land in."
  value = {
    public_url     = local.survey_public_url
    uploads_bucket = module.survey.uploads_bucket
  }
}

output "attribution" {
  description = "Attribution's public base URL, the root of a Tenant's own domain."
  value       = local.attribution_public_url
}

output "rds_endpoint" {
  description = "RDS address and port."
  value = {
    address = module.rds.address
    port    = module.rds.port
  }
}

output "mq_endpoints" {
  description = "Amazon MQ RabbitMQ endpoints."
  value = {
    amqp_host           = module.mq.amqp_host
    amqp_port           = module.mq.amqp_port
    management_endpoint = module.mq.management_endpoint
  }
}

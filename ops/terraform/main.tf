terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }

  # Local state is the default because no shared bucket exists yet and `terraform validate` must run without AWS
  # credentials. Before applying from more than one machine, create an S3 bucket and a DynamoDB lock table and
  # replace this block with:
  #
  # backend "s3" {
  #   bucket         = "<state-bucket>"
  #   key            = "tenancykit/terraform.tfstate"
  #   region         = "<region>"
  #   dynamodb_table = "<lock-table>"
  #   encrypt        = true
  # }
  backend "local" {}
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "tenancykit"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}

# The suite's SES identity and the mailer clients that send from it are pinned to one region: an identity exists per
# account *and* per region, so both take it from `ses_region`, which falls back to `region`.
provider "aws" {
  alias  = "ses"
  region = local.ses_region

  default_tags {
    tags = {
      Project     = "tenancykit"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}

module "network" {
  source = "./network"

  name_prefix = local.name_prefix
  vpc_cidr    = var.vpc_cidr
}

module "secrets" {
  source = "./secrets"

  suite_name  = local.suite_name
  environment = var.environment
}

module "ecs" {
  source = "./ecs"

  name_prefix            = local.name_prefix
  suite_name             = local.suite_name
  environment            = var.environment
  secret_arns            = module.secrets.arns
  log_group_names        = local.log_group_names
  log_retention_days     = var.log_retention_days
  ses_region             = local.ses_region
  ses_domain             = var.ses_domain
  ses_from_address       = local.ses_from_address
  tenant_aws_account_ids = var.tenant_aws_account_ids
}

module "service_discovery" {
  source = "./service-discovery"

  namespace = var.service_connect_namespace
}

module "alb" {
  source = "./alb"

  name_prefix           = local.name_prefix
  environment           = var.environment
  vpc_id                = module.network.vpc_id
  public_subnet_ids     = module.network.public_subnet_ids
  alb_security_group_id = module.network.alb_security_group_id
  certificate_arn       = var.certificate_arn
}

module "rds" {
  source = "./rds"

  name_prefix            = local.name_prefix
  environment            = var.environment
  region                 = var.region
  private_subnet_ids     = module.network.private_subnet_ids
  data_security_group_id = module.network.data_security_group_id
  task_security_group_id = module.network.task_security_group_id
  cluster_arn            = module.ecs.cluster_arn
  execution_role_arn     = module.ecs.execution_role_arn
  log_group_name         = module.ecs.log_group_names["rds_init"]
  instance_class         = var.db_instance_class
  engine_version         = var.db_engine_version
  allocated_storage      = var.db_allocated_storage
  master_password        = module.secrets.values["POSTGRES_SUPERUSER_PASSWORD"]
  secret_arns            = module.secrets.arns
}

module "mq" {
  source = "./mq"

  name_prefix            = local.name_prefix
  environment            = var.environment
  region                 = var.region
  private_subnet_ids     = module.network.private_subnet_ids
  data_security_group_id = module.network.data_security_group_id
  task_security_group_id = module.network.task_security_group_id
  cluster_arn            = module.ecs.cluster_arn
  execution_role_arn     = module.ecs.execution_role_arn
  log_group_name         = module.ecs.log_group_names["mq_users"]
  instance_type          = var.mq_instance_type
  engine_version         = var.mq_engine_version
  admin_password         = module.secrets.values["MQ_ADMIN_PASSWORD"]
  platform_password      = module.secrets.values["PLATFORM_RABBITMQ_PASSWORD"]
  billing_password       = module.secrets.values["BILLING_RABBITMQ_PASSWORD"]
  mailer_password        = module.secrets.values["MAILER_RABBITMQ_PASSWORD"]
  notification_password  = module.secrets.values["NOTIFICATION_RABBITMQ_PASSWORD"]
  mailroom_password         = module.secrets.values["MAILROOM_RABBITMQ_PASSWORD"]
  secret_arns            = module.secrets.arns
}

module "keycloak" {
  source = "./keycloak"

  environment                   = var.environment
  region                        = var.region
  cluster_arn                   = module.ecs.cluster_arn
  execution_role_arn            = module.ecs.execution_role_arn
  private_subnet_ids            = module.network.private_subnet_ids
  task_security_group_id        = module.network.task_security_group_id
  vpc_id                        = module.network.vpc_id
  service_connect_namespace_arn = module.service_discovery.namespace_arn
  log_group_name                = module.ecs.log_group_names["keycloak"]
  image                         = "${module.ecs.ecr_repository_urls["keycloak"]}:${var.image_tag}"
  desired_count                 = var.keycloak_desired_count
  listener_arn                  = module.alb.listener_arn
  public_host                   = local.keycloak_public_host
  public_url                    = "${local.public_scheme}://${local.keycloak_public_host}"
  internal_url                  = local.keycloak_internal_url
  realm                         = local.realm
  public_issuer                 = local.keycloak_public_issuer
  db_host                       = module.rds.address
  db_port                       = module.rds.port
  secret_arns                   = module.secrets.arns
}

module "platform" {
  source = "./platform"

  environment                   = var.environment
  region                        = var.region
  cluster_arn                   = module.ecs.cluster_arn
  execution_role_arn            = module.ecs.execution_role_arn
  private_subnet_ids            = module.network.private_subnet_ids
  task_security_group_id        = module.network.task_security_group_id
  vpc_id                        = module.network.vpc_id
  service_connect_namespace_arn = module.service_discovery.namespace_arn
  log_group_name                = module.ecs.log_group_names["platform"]
  image                         = "${module.ecs.ecr_repository_urls["platform"]}:${var.image_tag}"
  desired_count                 = var.platform_desired_count
  listener_arn                  = module.alb.listener_arn
  public_host                   = local.platform_public_host
  base_domain                   = var.public_domain
  internal_hostname             = local.internal_fqdn
  keycloak_internal_url         = local.keycloak_internal_url
  keycloak_public_issuer        = local.keycloak_public_issuer
  keycloak_internal_jwks        = local.keycloak_internal_jwks
  public_issuer                 = local.platform_public_issuer
  db_host                       = module.rds.address
  db_port                       = module.rds.port
  broker_host                   = module.mq.amqp_host
  broker_port                   = module.mq.amqp_port
  admin_email                   = var.platform_admin_email
  secret_arns                   = module.secrets.arns
}

module "billing" {
  source = "./billing"

  environment                   = var.environment
  region                        = var.region
  cluster_arn                   = module.ecs.cluster_arn
  execution_role_arn            = module.ecs.execution_role_arn
  private_subnet_ids            = module.network.private_subnet_ids
  task_security_group_id        = module.network.task_security_group_id
  service_connect_namespace_arn = module.service_discovery.namespace_arn
  log_group_names = {
    api     = module.ecs.log_group_names["billing_api"]
    worker  = module.ecs.log_group_names["billing_worker"]
    migrate = module.ecs.log_group_names["billing_migrate"]
  }
  image                    = "${module.ecs.ecr_repository_urls["billing"]}:${var.image_tag}"
  api_desired_count        = var.billing_api_desired_count
  worker_desired_count     = var.billing_worker_desired_count
  billing_target_group_arn = module.alb.billing_target_group_arn
  public_base_url          = local.billing_public_url
  base_domain              = var.public_domain
  platform_internal_url    = local.platform_internal_url
  platform_internal_jwks   = local.platform_internal_jwks
  platform_public_issuer   = local.platform_public_issuer
  keycloak_public_issuer   = local.keycloak_public_issuer
  keycloak_internal_jwks   = local.keycloak_internal_jwks
  db_host                  = module.rds.address
  db_port                  = module.rds.port
  broker_host              = module.mq.amqp_host
  broker_port              = module.mq.amqp_port
  secret_arns              = module.secrets.arns
}

module "mailer" {
  source = "./mailer"

  environment            = var.environment
  region                 = var.region
  cluster_arn            = module.ecs.cluster_arn
  task_role_arn          = module.ecs.task_role_arn
  private_subnet_ids     = module.network.private_subnet_ids
  task_security_group_id = module.network.task_security_group_id
  image                  = "${module.ecs.ecr_repository_urls["mailer"]}:${var.image_tag}"
  desired_count          = var.mailer_desired_count
  db_host                = module.rds.address
  db_port                = module.rds.port
  broker_host            = module.mq.amqp_host
  broker_port            = module.mq.amqp_port
  ses_region             = local.ses_region
  default_from_address   = local.ses_from_address
  secret_arns            = module.secrets.arns
  log_group_names = {
    worker  = module.ecs.log_group_names["mailer"]
    migrate = module.ecs.log_group_names["mailer_migrate"]
  }
}

module "notification" {
  source = "./notification"

  environment                   = var.environment
  region                        = var.region
  cluster_arn                   = module.ecs.cluster_arn
  execution_role_arn            = module.ecs.execution_role_arn
  private_subnet_ids            = module.network.private_subnet_ids
  task_security_group_id        = module.network.task_security_group_id
  vpc_id                        = module.network.vpc_id
  service_connect_namespace_arn = module.service_discovery.namespace_arn
  log_group_names = {
    api     = module.ecs.log_group_names["notification_api"]
    worker  = module.ecs.log_group_names["notification"]
    migrate = module.ecs.log_group_names["notification_migrate"]
  }
  image                  = "${module.ecs.ecr_repository_urls["notification"]}:${var.image_tag}"
  api_desired_count      = var.notification_api_desired_count
  worker_desired_count   = var.notification_worker_desired_count
  listener_arn           = module.alb.listener_arn
  platform_internal_url  = local.platform_internal_url
  platform_internal_jwks = local.platform_internal_jwks
  platform_public_issuer = local.platform_public_issuer
  keycloak_public_issuer = local.keycloak_public_issuer
  keycloak_internal_jwks = local.keycloak_internal_jwks
  db_host                = module.rds.address
  db_port                = module.rds.port
  broker_host            = module.mq.amqp_host
  broker_port            = module.mq.amqp_port
  secret_arns            = module.secrets.arns
}

module "mailroom" {
  source = "./mailroom"

  environment                   = var.environment
  region                        = var.region
  cluster_arn                   = module.ecs.cluster_arn
  execution_role_arn            = module.ecs.execution_role_arn
  private_subnet_ids            = module.network.private_subnet_ids
  task_security_group_id        = module.network.task_security_group_id
  vpc_id                        = module.network.vpc_id
  service_connect_namespace_arn = module.service_discovery.namespace_arn
  log_group_names = {
    api     = module.ecs.log_group_names["mailroom_api"]
    worker  = module.ecs.log_group_names["mailroom"]
    migrate = module.ecs.log_group_names["mailroom_migrate"]
  }
  image                  = "${module.ecs.ecr_repository_urls["mailroom"]}:${var.image_tag}"
  api_desired_count      = var.mailroom_api_desired_count
  worker_desired_count   = var.mailroom_worker_desired_count
  listener_arn           = module.alb.listener_arn
  mailroom_public_base_url  = local.mailroom_public_url
  platform_internal_url  = local.platform_internal_url
  platform_internal_jwks = local.platform_internal_jwks
  platform_public_issuer = local.platform_public_issuer
  keycloak_public_issuer = local.keycloak_public_issuer
  keycloak_internal_jwks = local.keycloak_internal_jwks
  db_host                = module.rds.address
  db_port                = module.rds.port
  broker_host            = module.mq.amqp_host
  broker_port            = module.mq.amqp_port
  secret_arns            = module.secrets.arns
}

# The suite's own verified sending identity, which SHARED-mode sends come from. Easy DKIM returns three tokens; this
# repository has no Route53 zone, so an operator publishes the three CNAMEs by hand where the domain's DNS lives and the
# identity stays Pending until they resolve (see README.md).
resource "aws_sesv2_email_identity" "suite" {
  provider = aws.ses

  email_identity = var.ses_domain
}

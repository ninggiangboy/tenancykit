variable "region" {
  description = "AWS region for every resource."
  type        = string
  default     = "us-west-2"
}

variable "environment" {
  description = "Deployment environment. Prefixes every resource name and the Secrets Manager path."
  type        = string
  default     = "dev"
}

variable "public_domain" {
  description = "Public domain the ALB is reached at. Placeholder: replace it in a tfvars file before applying."
  type        = string
  default     = "example.com"
}

variable "service_connect_namespace" {
  description = "Cloud Map namespace for Service Connect. Internal FQDNs take the form <service>.<namespace>."
  type        = string
  default     = "tenancykit.local"
}

variable "vpc_cidr" {
  description = "CIDR block of the VPC. Public and private subnets are carved from it in two availability zones."
  type        = string
  default     = "10.42.0.0/16"
}

variable "certificate_arn" {
  description = "ACM certificate for the HTTPS listener. Empty serves HTTP only, which is the development default."
  type        = string
  default     = ""
}

variable "image_tag" {
  description = "Tag the task definitions pull from ECR. CI pushes this tag after building the images."
  type        = string
  default     = "latest"
}

variable "db_instance_class" {
  description = "RDS instance class."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_engine_version" {
  description = "PostgreSQL engine version on RDS. Pin a version the target region supports; compose uses postgres:18."
  type        = string
  default     = "18.1"
}

variable "db_allocated_storage" {
  description = "RDS allocated storage in GiB."
  type        = number
  default     = 20
}

variable "mq_instance_type" {
  description = "Amazon MQ host instance type."
  type        = string
  default     = "mq.t3.micro"
}

variable "mq_engine_version" {
  description = "Amazon MQ RabbitMQ engine version. Pin a version the target region supports."
  type        = string
  default     = "3.13.7"
}

variable "platform_admin_email" {
  description = "First Platform administrator created on start through Keycloak. Empty skips the bootstrap."
  type        = string
  default     = ""
}

variable "keycloak_desired_count" {
  description = "Keycloak tasks. One is enough for a development realm import; production can raise it."
  type        = number
  default     = 1
}

variable "platform_desired_count" {
  description = "Platform tasks."
  type        = number
  default     = 2
}

variable "billing_api_desired_count" {
  description = "Billing api tasks."
  type        = number
  default     = 2
}

variable "billing_worker_desired_count" {
  description = "Billing worker tasks."
  type        = number
  default     = 2
}

variable "mailer_desired_count" {
  description = "Mailer worker tasks."
  type        = number
  default     = 2
}

variable "notification_api_desired_count" {
  description = "Notification api tasks."
  type        = number
  default     = 2
}

variable "notification_worker_desired_count" {
  description = "Notification worker tasks."
  type        = number
  default     = 2
}

variable "mailroom_api_desired_count" {
  description = "Email api tasks."
  type        = number
  default     = 2
}

variable "mailroom_worker_desired_count" {
  description = "Email worker tasks."
  type        = number
  default     = 2
}

variable "customer_api_desired_count" {
  description = "Customer api tasks."
  type        = number
  default     = 2
}

variable "customer_worker_desired_count" {
  description = "Customer worker tasks."
  type        = number
  default     = 2
}

variable "loyalty_api_desired_count" {
  description = "Loyalty api tasks."
  type        = number
  default     = 2
}

variable "loyalty_worker_desired_count" {
  description = "Loyalty worker tasks."
  type        = number
  default     = 2
}

variable "survey_api_desired_count" {
  description = "Survey api tasks."
  type        = number
  default     = 2
}

variable "survey_worker_desired_count" {
  description = "Survey worker tasks."
  type        = number
  default     = 2
}

variable "booking_api_desired_count" {
  description = "Booking api tasks."
  type        = number
  default     = 2
}

variable "booking_worker_desired_count" {
  description = "Booking worker tasks."
  type        = number
  default     = 2
}

variable "workflow_api_desired_count" {
  description = "Workflow api tasks."
  type        = number
  default     = 2
}

variable "workflow_worker_desired_count" {
  description = "Workflow worker tasks."
  type        = number
  default     = 2
}

variable "attribution_api_desired_count" {
  description = "Attribution api tasks."
  type        = number
  default     = 2
}

variable "attribution_worker_desired_count" {
  description = "Attribution worker tasks."
  type        = number
  default     = 2
}

variable "ses_domain" {
  description = "Domain of the suite's verified SES identity. Mailer sends from noreply@<domain> in SHARED mode."
  type        = string
  default     = "example.com"
}

variable "ses_region" {
  description = "Region of the suite's SES identity. Empty follows `region`; both are reported as MAILER_SES_REGION."
  type        = string
  default     = ""
}

variable "tenant_aws_account_ids" {
  description = "AWS accounts whose mailer-* role the mailer task role may assume, one entry per tenant account."
  type        = list(string)
  default     = []
}

variable "log_retention_days" {
  description = "CloudWatch Logs retention for every task."
  type        = number
  default     = 14
}

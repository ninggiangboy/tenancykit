variable "name_prefix" {
  description = "Prefix of every resource name, already carrying the environment and the suite name."
  type        = string
}

variable "suite_name" {
  description = "Suite name, the first segment of every ECR repository path."
  type        = string
}

variable "environment" {
  description = "Deployment environment, the second segment of every ECR repository path."
  type        = string
}

variable "secret_arns" {
  description = "Secrets the execution role may read."
  type        = map(string)
}

variable "log_group_names" {
  description = "CloudWatch Log group per task family."
  type        = map(string)
}

variable "log_retention_days" {
  description = "Log retention in days."
  type        = number
}

variable "ses_region" {
  description = "Region of the suite's SES identity, part of the ARN the task role may send from."
  type        = string
}

variable "ses_domain" {
  description = "Domain of the suite's verified SES identity, the resource name in that ARN."
  type        = string
}

variable "ses_from_address" {
  description = "The one address the task role may send from; the SES policy conditions on ses:FromAddress."
  type        = string
}

variable "tenant_aws_account_ids" {
  description = "AWS accounts whose mailer-* role the task role may assume. One resource ARN pattern per account."
  type        = list(string)
}

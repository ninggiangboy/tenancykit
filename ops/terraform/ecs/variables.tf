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

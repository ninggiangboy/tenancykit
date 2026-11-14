variable "environment" {
  description = "Deployment environment. Prefixes every resource name."
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

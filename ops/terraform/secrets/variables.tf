variable "suite_name" {
  description = "Suite name, the first segment of every secret name."
  type        = string
}

variable "environment" {
  description = "Deployment environment. Part of every secret name."
  type        = string
}

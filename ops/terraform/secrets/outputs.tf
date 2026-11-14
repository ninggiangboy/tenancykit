output "arns" {
  description = "Secret ARNs keyed by the environment variable each one feeds."
  value       = { for name, secret in aws_secretsmanager_secret.this : name => secret.arn }
}

output "values" {
  description = "Generated values for the two resources that need the plaintext while creating infrastructure."
  value       = local.secret_values
  sensitive   = true
}

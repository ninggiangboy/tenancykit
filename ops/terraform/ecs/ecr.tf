# Five repositories back ten workloads: Platform, Billing (migrate, api and worker), Mailer (worker and migrate),
# Notification (migrate, api and worker) and the Keycloak image that bakes the realm export.
resource "aws_ecr_repository" "this" {
  for_each = toset(["platform", "billing", "keycloak", "mailer", "notification"])

  name                 = "${var.suite_name}/${var.environment}/${each.key}"
  image_tag_mutability = "MUTABLE"
  force_delete         = true

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "this" {
  for_each = aws_ecr_repository.this

  repository = each.value.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Keep the last ten images"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 10
        }
        action = {
          type = "expire"
        }
      }
    ]
  })
}

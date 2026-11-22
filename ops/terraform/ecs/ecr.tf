# One repository per deployable: Platform, Billing, Mailer, Notification, Mailroom, Customer, Loyalty, Survey, Booking,
# Workflow, Attribution and the Keycloak image that bakes the realm export. A service's migrate, api and worker runtimes
# share that service's repository, so the count of repositories is the count of deployables and not of workloads.
resource "aws_ecr_repository" "this" {
  for_each = toset(["platform", "billing", "keycloak", "mailer", "notification", "mailroom", "customer", "loyalty", "survey", "booking", "workflow", "attribution"])

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

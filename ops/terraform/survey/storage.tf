data "aws_caller_identity" "current" {}

locals {
  bucket_name = "${var.suite_name}-${var.environment}-survey-uploads-${data.aws_caller_identity.current.account_id}"
}

# Survey keeps a respondent's file uploads and a survey's CSV exports here. Compose runs SeaweedFS in this role; on AWS
# it is a private bucket. The app signs its own presigned URLs with static credentials, so the bucket is reached through
# a dedicated IAM user rather than a task role: survey's S3 client takes an access key and a secret and has no
# credential-provider chain to fall back on.
resource "aws_s3_bucket" "uploads" {
  bucket = local.bucket_name

  # Development: destroying the stack removes the bucket together with whatever respondents left in it, the same
  # trade-off the ECR repositories and the secrets make.
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "uploads" {
  bucket = aws_s3_bucket.uploads.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "uploads" {
  bucket = aws_s3_bucket.uploads.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# A respondent's browser PUTs the file straight to S3 with the presigned URL, from a page served on the public domain or
# one of its tenant subdomains, so the bucket must answer that cross-origin PUT. Reads are for the presigned export link.
resource "aws_s3_bucket_cors_configuration" "uploads" {
  bucket = aws_s3_bucket.uploads.id

  cors_rule {
    allowed_methods = ["PUT", "GET", "HEAD"]
    allowed_origins = var.allowed_origins
    allowed_headers = ["*"]
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }
}

resource "aws_iam_user" "uploads" {
  name = "${var.environment}-${var.suite_name}-survey-uploads"
}

# Exactly what S3UploadStore and S3ExportStore call: put, get, head and delete an object, and presign the first two.
# ListBucket is what turns HeadObject on a missing key into a 404 instead of a 403, which the verifier relies on.
data "aws_iam_policy_document" "uploads" {
  statement {
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.uploads.arn}/*"]
  }

  statement {
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.uploads.arn]
  }
}

resource "aws_iam_user_policy" "uploads" {
  name   = "survey-uploads"
  user   = aws_iam_user.uploads.name
  policy = data.aws_iam_policy_document.uploads.json
}

resource "aws_iam_access_key" "uploads" {
  user = aws_iam_user.uploads.name
}

# The access key id is not a secret and goes into the task environment; the secret key is stored the same way every
# other credential is, and the shared execution role injects it at start.
resource "aws_secretsmanager_secret" "s3_secret_key" {
  name = "${var.suite_name}/${var.environment}/survey_s3_secret_key"

  # Development: destroying the stack releases the name at once instead of holding it for a recovery window.
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "s3_secret_key" {
  secret_id     = aws_secretsmanager_secret.s3_secret_key.id
  secret_string = aws_iam_access_key.uploads.secret
}

resource "aws_db_subnet_group" "this" {
  name       = "${var.environment}-core-billing"
  subnet_ids = var.private_subnet_ids
}

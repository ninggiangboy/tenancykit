resource "aws_db_subnet_group" "this" {
  name       = var.name_prefix
  subnet_ids = var.private_subnet_ids
}

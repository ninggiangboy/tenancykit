# One managed PostgreSQL server hosts a database per service, exactly as the compose stack does. The master account
# creates the roles; every service then connects as its own owner or app role.
resource "aws_db_instance" "this" {
  identifier = var.name_prefix

  engine               = "postgres"
  engine_version       = var.engine_version
  instance_class       = var.instance_class
  allocated_storage    = var.allocated_storage
  storage_type         = "gp3"
  storage_encrypted    = true
  db_subnet_group_name = aws_db_subnet_group.this.name

  vpc_security_group_ids = [var.data_security_group_id]
  publicly_accessible    = false

  username = "postgres"
  password = var.master_password

  # Development defaults. A production environment sets a final snapshot and deletion protection.
  skip_final_snapshot     = true
  deletion_protection     = false
  backup_retention_period = 1
  apply_immediately       = true
}

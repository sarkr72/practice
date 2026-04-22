terraform {
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.60" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }
}

variable "name"                    { type = string }
variable "vpc_id"                  { type = string }
variable "subnet_ids"              { type = list(string) }
variable "allowed_security_groups" { type = list(string) }
variable "instance_class"          { type = string }
variable "allocated_storage"       { type = number }
variable "database_name"           { type = string }
variable "master_username"         { type = string }

# Generate a strong random password and stash in Secrets Manager.
# The app reads it via External Secrets Operator in k8s — never passes through plaintext.
resource "random_password" "master" {
  length  = 32
  special = true
  # RDS disallows some chars in passwords
  override_special = "!#$%&*()-_=+[]{}<>:?"
}

resource "aws_secretsmanager_secret" "db" {
  name = "${var.name}-db-master"
}

resource "aws_secretsmanager_secret_version" "db" {
  secret_id = aws_secretsmanager_secret.db.id
  secret_string = jsonencode({
    username = var.master_username
    password = random_password.master.result
  })
}

resource "aws_db_subnet_group" "this" {
  name       = "${var.name}-db"
  subnet_ids = var.subnet_ids
}

resource "aws_security_group" "this" {
  name   = "${var.name}-rds"
  vpc_id = var.vpc_id
}

resource "aws_security_group_rule" "ingress_mysql" {
  for_each                 = toset(var.allowed_security_groups)
  type                     = "ingress"
  from_port                = 3306
  to_port                  = 3306
  protocol                 = "tcp"
  source_security_group_id = each.value
  security_group_id        = aws_security_group.this.id
}

resource "aws_db_instance" "this" {
  identifier              = var.name
  engine                  = "mysql"
  engine_version          = "8.0"
  instance_class          = var.instance_class
  allocated_storage       = var.allocated_storage
  storage_encrypted       = true

  db_name                 = var.database_name
  username                = var.master_username
  password                = random_password.master.result

  db_subnet_group_name    = aws_db_subnet_group.this.name
  vpc_security_group_ids  = [aws_security_group.this.id]

  backup_retention_period = 7
  deletion_protection     = true
  skip_final_snapshot     = false
  final_snapshot_identifier = "${var.name}-final-${formatdate("YYYYMMDDhhmmss", timestamp())}"

  # Enable IAM auth so apps can use short-lived tokens instead of a password
  iam_database_authentication_enabled = true

  lifecycle {
    # final_snapshot_identifier changes every apply due to timestamp; ignore to keep plans clean
    ignore_changes = [final_snapshot_identifier]
  }
}

output "endpoint"   { value = aws_db_instance.this.endpoint }
output "secret_arn" { value = aws_secretsmanager_secret.db.arn }

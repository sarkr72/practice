# ---------------------------------------------------------------------------
# RDS MySQL
# Single primary instance + optional read replica.
#
# Engine choice: matches the app stack (mysql-connector-j, flyway-mysql,
# AUTO_INCREMENT/DATETIME(6)/ENGINE=InnoDB syntax in V1__initial_schema.sql,
# docker-compose mysql:8.0). Don't switch to Postgres without rewriting
# migrations and pom.xml.
# ---------------------------------------------------------------------------

resource "aws_db_subnet_group" "main" {
  name       = "${local.app}-${var.env}-db"
  subnet_ids = data.aws_subnets.default.ids

  tags = {
    Name = "${local.app}-${var.env}-db-subnet-group"
  }
}

resource "aws_security_group" "db" {
  name        = "${local.app}-${var.env}-db"
  description = "MySQL ingress from ECS tasks"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "MySQL from ECS tasks"
    from_port       = 3306
    to_port         = 3306
    protocol        = "tcp"
    security_groups = [aws_security_group.tasks.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# Master password — generated, stored in Secrets Manager.
# Spring app reads it via the DB_PASSWORD secret reference in the ECS task def.
resource "random_password" "db_master" {
  length  = 32
  special = true
  # RDS MySQL forbids '/', '"', '@', space in passwords.
  override_special = "!#$%&*()-_=+[]{}<>:?"
}

# Sync the generated password into the DB_PASSWORD secret created in secrets.tf.
resource "aws_secretsmanager_secret_version" "db_password" {
  secret_id     = aws_secretsmanager_secret.db_password.id
  secret_string = random_password.db_master.result
}

resource "aws_db_instance" "primary" {
  identifier     = "${local.app}-${var.env}-primary"
  engine         = "mysql"
  engine_version = var.db_engine_version
  instance_class = var.db_instance_class

  db_name  = var.db_name
  username = var.db_username
  password = random_password.db_master.result

  allocated_storage     = var.db_allocated_storage
  max_allocated_storage = var.db_allocated_storage * 5
  storage_type          = "gp3"
  storage_encrypted     = true

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible    = false

  backup_retention_period = var.env == "prod" ? 7 : 1
  backup_window           = "03:00-04:00"
  maintenance_window      = "sun:04:00-sun:05:00"

  parameter_group_name = aws_db_parameter_group.mysql.name

  deletion_protection       = var.env == "prod"
  skip_final_snapshot       = var.env != "prod"
  final_snapshot_identifier = var.env == "prod" ? "${local.app}-${var.env}-final-${formatdate("YYYY-MM-DD-hhmm", timestamp())}" : null

  performance_insights_enabled          = var.env == "prod"
  performance_insights_retention_period = var.env == "prod" ? 7 : null

  enabled_cloudwatch_logs_exports = ["error", "general", "slowquery"]

  apply_immediately = var.env != "prod"

  lifecycle {
    ignore_changes = [
      final_snapshot_identifier,
      password,
    ]
  }
}

resource "aws_db_instance" "replica" {
  count = var.create_read_replica ? 1 : 0

  identifier          = "${local.app}-${var.env}-replica"
  replicate_source_db = aws_db_instance.primary.identifier
  instance_class      = var.db_instance_class

  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible    = false

  storage_encrypted   = true
  skip_final_snapshot = true
  apply_immediately   = var.env != "prod"

  performance_insights_enabled          = var.env == "prod"
  performance_insights_retention_period = var.env == "prod" ? 7 : null
}

resource "aws_db_parameter_group" "mysql" {
  name   = "${local.app}-${var.env}-mysql80"
  family = "mysql8.0"

  parameter {
    name  = "slow_query_log"
    value = "1"
  }

  parameter {
    name  = "long_query_time"
    value = "1"
  }
}

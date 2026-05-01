# ---------------------------------------------------------------------------
# Secrets + non-secret config for the ECS task.
#
# Spinnaker's task definition (in spinnaker/pipelines/ems-deploy-cicd.json)
# references these by path:
#
#   /<env>/image-uploader/DB_HOST       (SSM)
#   /<env>/image-uploader/DB_PORT       (SSM)
#   /<env>/image-uploader/DB_NAME       (SSM)
#   /<env>/image-uploader/DB_USERNAME   (SSM)
#   /<env>/image-uploader/DB_PASSWORD   (Secrets Manager — populated by rds.tf)
#   /<env>/image-uploader/JWT_SECRET    (Secrets Manager — populated out-of-band, prod only)
#
# Why split SSM vs Secrets Manager: DB host/port/name/username aren't sensitive
# but DO change with infrastructure, so they belong in config storage. The
# password and JWT secret are sensitive and belong in Secrets Manager (which
# supports rotation, KMS encryption, and 30-day recovery in prod).
#
# Both can be referenced from the ECS task def's `secrets:` field — ECS
# fetches the value at task start and injects it as an env var.
# ---------------------------------------------------------------------------

# ---------- SSM parameters (non-secret config) ----------

resource "aws_ssm_parameter" "db_host" {
  name  = "/${var.env}/${local.app}/DB_HOST"
  type  = "String"
  value = aws_db_instance.primary.address
}

resource "aws_ssm_parameter" "db_port" {
  name  = "/${var.env}/${local.app}/DB_PORT"
  type  = "String"
  value = "3306"
}

resource "aws_ssm_parameter" "db_name" {
  name  = "/${var.env}/${local.app}/DB_NAME"
  type  = "String"
  value = var.db_name
}

resource "aws_ssm_parameter" "db_username" {
  name  = "/${var.env}/${local.app}/DB_USERNAME"
  type  = "String"
  value = var.db_username
}

# ---------- Secrets Manager (sensitive) ----------

# DB_PASSWORD: created here, populated by rds.tf from random_password.
resource "aws_secretsmanager_secret" "db_password" {
  name        = "/${var.env}/${local.app}/DB_PASSWORD"
  description = "RDS master password for ${local.app}-${var.env}"

  recovery_window_in_days = var.env == "prod" ? 30 : 0
}

# JWT_SECRET: prod only, populated out-of-band via:
#   aws secretsmanager put-secret-value --secret-id <arn> --secret-string '...'
resource "aws_secretsmanager_secret" "jwt_secret" {
  count = var.env == "prod" ? 1 : 0

  name        = "/${var.env}/${local.app}/JWT_SECRET"
  description = "JWT signing secret for ${local.app}-${var.env}"

  recovery_window_in_days = 30
}

# ---------- IAM: let the task execution role read these ----------

data "aws_iam_policy_document" "read_secrets" {
  statement {
    sid     = "ReadSsmParameters"
    actions = ["ssm:GetParameters", "ssm:GetParameter"]
    resources = [
      aws_ssm_parameter.db_host.arn,
      aws_ssm_parameter.db_port.arn,
      aws_ssm_parameter.db_name.arn,
      aws_ssm_parameter.db_username.arn,
    ]
  }

  statement {
    sid     = "ReadSecretsManager"
    actions = ["secretsmanager:GetSecretValue"]
    resources = concat(
      [aws_secretsmanager_secret.db_password.arn],
      var.env == "prod" ? [aws_secretsmanager_secret.jwt_secret[0].arn] : [],
    )
  }
}

resource "aws_iam_policy" "read_secrets" {
  name   = "${local.app}-${var.env}-read-secrets"
  policy = data.aws_iam_policy_document.read_secrets.json
}

resource "aws_iam_role_policy_attachment" "task_exec_read_secrets" {
  role       = aws_iam_role.task_execution.name
  policy_arn = aws_iam_policy.read_secrets.arn
}

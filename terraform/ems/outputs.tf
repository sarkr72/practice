# Outputs are the contract between the Terraform-managed platform and its
# consumers. After `terraform apply`, the deploy workflow targets `ecs_cluster`
# + `ecs_service`; the optional Spinnaker pipeline reads the target group / SG /
# subnet / role names below.

output "alb_url" {
  description = "Public URL of the load balancer."
  value       = "http://${aws_lb.main.dns_name}"
}

output "alb_dns_name" {
  description = "Raw ALB DNS name — point a Route53 record at this."
  value       = aws_lb.main.dns_name
}

output "ecs_cluster" {
  description = "ECS cluster name (consumed by the app layer + Spinnaker pipeline)."
  value       = aws_ecs_cluster.main.name
}

output "ecs_cluster_arn" {
  description = "ECS cluster ARN — the app layer sets the service's `cluster` to this."
  value       = aws_ecs_cluster.main.arn
}

output "target_group_stable_arn" {
  description = "Stable target group ARN."
  value       = aws_lb_target_group.stable.arn
}

output "target_group_stable_name" {
  description = "Stable target group name (referenced from Spinnaker pipeline)."
  value       = aws_lb_target_group.stable.name
}

output "target_group_canary_arn" {
  description = "Canary target group ARN."
  value       = aws_lb_target_group.canary.arn
}

output "target_group_canary_name" {
  description = "Canary target group name (referenced from Spinnaker pipeline)."
  value       = aws_lb_target_group.canary.name
}

output "tasks_security_group_id" {
  description = "Security group for ECS tasks (referenced from Spinnaker pipeline)."
  value       = aws_security_group.tasks.id
}

output "tasks_security_group_name" {
  description = "Security group name (Spinnaker can reference by name)."
  value       = aws_security_group.tasks.name
}

output "subnet_ids" {
  description = "Subnet IDs that Spinnaker should place tasks in."
  value       = data.aws_subnets.default.ids
}

output "task_execution_role_arn" {
  description = "IAM role assumed by the ECS agent (image pull, secrets, logs)."
  value       = aws_iam_role.task_execution.arn
}

output "task_execution_role_name" {
  description = "Task execution role name (Spinnaker references by name)."
  value       = aws_iam_role.task_execution.name
}

output "task_role_arn" {
  description = "IAM role assumed by the app at runtime."
  value       = aws_iam_role.task.arn
}

output "task_role_name" {
  description = "Task role name (referenced as `iamRole` in the Spinnaker pipeline)."
  value       = aws_iam_role.task.name
}

output "log_group" {
  description = "CloudWatch log group for the service."
  value       = aws_cloudwatch_log_group.service.name
}

output "ecr_repository_uri" {
  description = "ECR repo URI for pushing images."
  value       = aws_ecr_repository.app.repository_url
}

# ---------- DB ----------

output "db_primary_endpoint" {
  description = "MySQL primary (read+write) endpoint."
  value       = aws_db_instance.primary.endpoint
}

output "db_reader_endpoint" {
  description = "MySQL reader endpoint (replica if exists, else primary)."
  value       = var.create_read_replica ? aws_db_instance.replica[0].endpoint : aws_db_instance.primary.endpoint
}

output "db_name" {
  description = "MySQL database name."
  value       = aws_db_instance.primary.db_name
}

# ---------- Secrets / config paths ----------

output "ssm_parameter_paths" {
  description = "SSM parameter paths the ECS task references."
  value = {
    db_host     = aws_ssm_parameter.db_host.name
    db_port     = aws_ssm_parameter.db_port.name
    db_name     = aws_ssm_parameter.db_name.name
    db_username = aws_ssm_parameter.db_username.name
  }
}

output "secret_arns" {
  description = "Secrets Manager ARNs (DB_PASSWORD always; JWT_SECRET in prod)."
  value = merge(
    { db_password = aws_secretsmanager_secret.db_password.arn },
    var.env == "prod" ? { jwt_secret = aws_secretsmanager_secret.jwt_secret[0].arn } : {},
  )
  sensitive = true
}

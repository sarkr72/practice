output "ecs_service" {
  description = "ECS service name — the deploy workflow rolls new task defs onto this with `update-service`."
  value       = aws_ecs_service.app.name
}

output "ecs_cluster" {
  description = "ECS cluster name (passed through from the platform layer for convenience)."
  value       = local.platform.ecs_cluster
}

output "task_definition_family" {
  description = "Task-definition family the deploy workflow registers revisions under."
  value       = aws_ecs_task_definition.bootstrap.family
}

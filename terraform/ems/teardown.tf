# ---------------------------------------------------------------------------
# Destroy-time cleanup for the ECS service.
#
# The ECS *service* (ems-<env>) is NOT a Terraform resource — it's created at
# deploy time by .github/workflows/deploy.yml via `aws ecs create-service`.
# Terraform therefore doesn't know about it and can't include it in a destroy
# plan. But the live service blocks the teardown of resources Terraform DOES
# own:
#   - aws_ecs_cluster        -> ClusterContainsServicesException
#   - aws_security_group.tasks -> DependencyViolation (task ENIs hold the SG)
#
# This null_resource drains and deletes the service on `terraform destroy`,
# before the cluster and tasks SG are destroyed (the depends_on below forces
# that ordering, since destroy runs in reverse dependency order). The result:
# `terraform destroy` is a single command again, no manual pre-steps.
#
# Cross-platform note: the command is one AWS CLI chain so it runs identically
# under cmd.exe (local Windows) and bash (the infra.yml runner). on_failure =
# continue handles the case where the service was never created or is already
# gone — the "service not found" error is harmless and must not abort destroy.
# ---------------------------------------------------------------------------

resource "null_resource" "ecs_service_teardown" {
  triggers = {
    cluster = aws_ecs_cluster.main.name
    service = "${local.app}-${var.env}"
    region  = var.aws_region
  }

  depends_on = [
    aws_ecs_cluster.main,
    aws_security_group.tasks,
  ]

  provisioner "local-exec" {
    when       = destroy
    on_failure = continue
    command    = "aws ecs delete-service --cluster ${self.triggers.cluster} --service ${self.triggers.service} --region ${self.triggers.region} --force && aws ecs wait services-inactive --cluster ${self.triggers.cluster} --services ${self.triggers.service} --region ${self.triggers.region}"
  }
}

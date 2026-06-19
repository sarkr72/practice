# ---------------------------------------------------------------------------
# IAM for ECS tasks.
#
# Two roles, both referenced by name from the Spinnaker pipeline JSON:
#   ems-<env>-task-exec  -> ECS pulls image, fetches secrets, ships logs
#   ems-<env>-task       -> the app's runtime role (S3 access, etc.)
# Permission to read SSM/Secrets is attached in secrets.tf.
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "ecs_task_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "task_execution" {
  name               = "${local.app}-${var.env}-task-exec"
  assume_role_policy = data.aws_iam_policy_document.ecs_task_assume.json
}

resource "aws_iam_role_policy_attachment" "task_execution_managed" {
  role       = aws_iam_role.task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Runtime role. Add S3/SQS/etc. permissions here as the app grows.
resource "aws_iam_role" "task" {
  name               = "${local.app}-${var.env}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_task_assume.json
}

# IRSA: Spinnaker SAs assume this AWS role via the EKS OIDC provider.
# Clouddriver uses it for both (a) S3 access to the persistence bucket and
# (b) AWS API calls to deploy ECS services into the same account.
#
# `subject` covers every SA in the `spinnaker` namespace so we don't have to
# enumerate one per microservice. Narrow to specific SAs in production.

data "aws_iam_policy_document" "spinnaker_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [module.eks.oidc_provider_arn]
    }

    condition {
      test     = "StringLike"
      variable = "${module.eks.oidc_provider}:sub"
      values   = ["system:serviceaccount:spinnaker:*"]
    }

    condition {
      test     = "StringEquals"
      variable = "${module.eks.oidc_provider}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }

  # Allow the role to assume itself. Spinnaker's clouddriver re-assumes its
  # own role at deploy time (per-account assumeRole pattern). Without this,
  # the deploy stage fails with AccessDenied on sts:AssumeRole.
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "AWS"
      identifiers = ["arn:aws:iam::${var.aws_account_id}:role/spinnaker-managed"]
    }
  }
}

resource "aws_iam_role" "spinnaker" {
  name               = "spinnaker-managed"
  assume_role_policy = data.aws_iam_policy_document.spinnaker_assume.json
}

# ECS + ELB managed policies cover createServerGroup / target group registration.
resource "aws_iam_role_policy_attachment" "spinnaker_ecs" {
  role       = aws_iam_role.spinnaker.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonECS_FullAccess"
}

resource "aws_iam_role_policy_attachment" "spinnaker_elb" {
  role       = aws_iam_role.spinnaker.name
  policy_arn = "arn:aws:iam::aws:policy/ElasticLoadBalancingFullAccess"
}

# Tighter inline: S3 persistence + PassRole for ECS task roles + read
# SSM/Secrets so clouddriver can validate task-def env references at deploy.
data "aws_iam_policy_document" "spinnaker_inline" {
  statement {
    sid     = "PersistenceBucket"
    actions = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:ListBucket"]
    resources = [
      aws_s3_bucket.persistence.arn,
      "${aws_s3_bucket.persistence.arn}/*",
    ]
  }

  statement {
    sid     = "PassEcsTaskRoles"
    actions = ["iam:PassRole", "iam:GetRole"]
    # Spinnaker passes the ems task / task-execution roles into ECS RunTask,
    # and calls GetRole on them while validating the task definition.
    resources = [
      "arn:aws:iam::${var.aws_account_id}:role/ems-*-task",
      "arn:aws:iam::${var.aws_account_id}:role/ems-*-task-exec",
    ]
  }

  statement {
    sid     = "ReadConfigForTaskDefs"
    actions = ["ssm:GetParameter", "ssm:GetParameters", "secretsmanager:GetSecretValue"]
    resources = ["*"]
  }

  # Spinnaker's caching agents (SecretCachingAgent, AmazonCertificateCachingAgent,
  # etc.) inventory the account every minute. Without these List/Describe calls,
  # the caches stay null and the ECS createServerGroup validator NPEs with
  # "Cannot invoke Collection.size() because c is null".
  statement {
    sid     = "CachingAgentInventory"
    actions = [
      "secretsmanager:ListSecrets",
      "secretsmanager:DescribeSecret",
      "acm:ListCertificates",
      "acm:DescribeCertificate",
      "ec2:DescribeSecurityGroups",
      "ec2:DescribeSubnets",
      "ec2:DescribeVpcs",
      "ec2:DescribeInstances",
      "ec2:DescribeAvailabilityZones",
      "ec2:DescribeKeyPairs",
      "ec2:DescribeImages",
      "elasticloadbalancing:DescribeLoadBalancers",
      "elasticloadbalancing:DescribeTargetGroups",
      "elasticloadbalancing:DescribeListeners",
      "elasticloadbalancing:DescribeRules",
      "elasticloadbalancing:DescribeTags",
      "iam:ListRoles",
      "iam:ListInstanceProfiles",
      "iam:ListServerCertificates",
      "ecs:ListClusters",
      "ecs:DescribeClusters",
      "ecs:ListServices",
      "ecs:DescribeServices",
      "ecs:ListTasks",
      "ecs:DescribeTasks",
      "ecs:ListTaskDefinitions",
      "ecs:DescribeTaskDefinition",
      "ecs:ListContainerInstances",
      "ecs:DescribeContainerInstances",
      "logs:DescribeLogGroups",
      "logs:DescribeLogStreams",
    ]
    resources = ["*"]
  }

  statement {
    sid       = "EcrAuthAndPull"
    actions   = ["ecr:GetAuthorizationToken", "ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage", "ecr:DescribeImages", "ecr:DescribeRepositories", "ecr:ListImages"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "spinnaker_inline" {
  name   = "spinnaker-managed-inline"
  role   = aws_iam_role.spinnaker.id
  policy = data.aws_iam_policy_document.spinnaker_inline.json
}

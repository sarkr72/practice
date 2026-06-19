# ---------------------------------------------------------------------------
# GitHub Actions OIDC trust.
#
# Lets the deploy workflow (.github/workflows/deploy.yml) assume a short-lived
# AWS role with NO static access keys stored in GitHub. Trust is scoped to
# pushes on the `main` branch of the repo below — a token minted for any other
# repo, branch, or a pull_request event cannot assume this role.
#
# Lives in the spinnaker root (not ems) because an OIDC provider is
# account-global: only one provider per URL per AWS account. The role only
# needs to push images to the `ems` ECR repo; triggering Spinnaker is an
# outbound HTTPS call from the runner and needs no AWS permission.
#
# After `terraform apply`, copy the `github_actions_role_arn` output into the
# GitHub repo's Actions variables as AWS_ROLE_ARN.
# ---------------------------------------------------------------------------

variable "github_repo" {
  description = "GitHub <owner>/<repo> whose main branch may assume the CI role."
  type        = string
  default     = "sarkr72/practice"
}

# Pull GitHub's current OIDC signing cert so the thumbprint never goes stale.
data "tls_certificate" "github" {
  url = "https://token.actions.githubusercontent.com/.well-known/openid-configuration"
}

resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.github.certificates[0].sha1_fingerprint]
}

data "aws_iam_policy_document" "github_actions_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # Only main-branch pushes. Use "repo:${var.github_repo}:*" if you later
    # want tags or other branches to deploy too.
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repo}:ref:refs/heads/main"]
    }
  }
}

resource "aws_iam_role" "github_actions" {
  name               = "github-actions-ems"
  assume_role_policy = data.aws_iam_policy_document.github_actions_assume.json
}

# Minimal ECR push permissions, scoped to the ems repository.
# GetAuthorizationToken is account-wide by API design (resource must be "*").
data "aws_iam_policy_document" "github_actions" {
  statement {
    sid       = "EcrAuth"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "EcrPushPull"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
    ]
    resources = ["arn:aws:ecr:${var.aws_region}:${var.aws_account_id}:repository/ems"]
  }
}

resource "aws_iam_role_policy" "github_actions" {
  name   = "github-actions-ems-ecr"
  role   = aws_iam_role.github_actions.id
  policy = data.aws_iam_policy_document.github_actions.json
}

output "github_actions_role_arn" {
  description = "Set this as the AWS_ROLE_ARN variable in GitHub > Settings > Actions."
  value       = aws_iam_role.github_actions.arn
}

# ---------------------------------------------------------------------------
# Separate role for the INFRA workflow (.github/workflows/infra.yml).
#
# Why a second role: running `terraform apply` creates EKS, RDS, IAM roles,
# VPC plumbing, etc. — that needs broad permissions, unlike the app deploy
# which only pushes an image. Keeping them as two roles means a leaked app
# token can never run terraform, and vice-versa (least privilege by separation).
#
# SECURITY NOTE: this attaches AdministratorAccess. That is acceptable here
# because (a) it's a personal learning account, and (b) infra.yml is
# `workflow_dispatch` only — it can ONLY be started by a human clicking "Run
# workflow", never by a code push. The trust below still restricts it to the
# main branch of this one repo. For a real org, replace AdministratorAccess
# with a scoped policy (or run infra from a separate repo/account entirely).
#
# Chicken-and-egg: this role is created BY terraform, so the FIRST
# `terraform apply` of this spinnaker root must be run from your laptop. After
# that, copy `github_actions_infra_role_arn` into the AWS_INFRA_ROLE_ARN
# GitHub variable and manage all later changes from the infra.yml button.
# ---------------------------------------------------------------------------

resource "aws_iam_role" "github_actions_infra" {
  name               = "github-actions-ems-infra"
  assume_role_policy = data.aws_iam_policy_document.github_actions_assume.json
}

resource "aws_iam_role_policy_attachment" "github_actions_infra_admin" {
  role       = aws_iam_role.github_actions_infra.name
  policy_arn = "arn:aws:iam::aws:policy/AdministratorAccess"
}

output "github_actions_infra_role_arn" {
  description = "Set this as the AWS_INFRA_ROLE_ARN variable in GitHub > Settings > Actions."
  value       = aws_iam_role.github_actions_infra.arn
}

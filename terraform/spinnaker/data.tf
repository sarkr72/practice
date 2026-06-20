# Reuse the default VPC for simplicity. Spinnaker on its own VPC is the
# right call for production; out of scope for this learning setup.
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }

  # EKS control planes cannot run in us-east-1e. Restrict to the AZs EKS
  # supports so the default-VPC subnet in 1e isn't handed to the cluster.
  filter {
    name   = "availability-zone"
    values = ["us-east-1a", "us-east-1b", "us-east-1c", "us-east-1d", "us-east-1f"]
  }
}

data "aws_caller_identity" "current" {}

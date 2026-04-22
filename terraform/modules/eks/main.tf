# Managed node group on EKS. Uses the official community module for cluster setup
# because doing this yourself (control plane IAM, OIDC provider, aws-auth config map)
# is where most homegrown Terraform goes wrong.

terraform {
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.60" }
  }
}

variable "name"            { type = string }
variable "cluster_version" { type = string }
variable "vpc_id"          { type = string }
variable "subnet_ids"      { type = list(string) }
variable "node_group_size" {
  type = object({
    desired = number
    min     = number
    max     = number
  })
}

module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.24"

  cluster_name    = var.name
  cluster_version = var.cluster_version

  vpc_id     = var.vpc_id
  subnet_ids = var.subnet_ids

  cluster_endpoint_public_access  = true   # dev convenience; tighten in prod
  cluster_endpoint_private_access = true

  # Required for IRSA (IAM Roles for Service Accounts) — used by AWS Load Balancer
  # Controller, External Secrets Operator, cluster autoscaler, etc.
  enable_irsa = true

  eks_managed_node_groups = {
    default = {
      min_size     = var.node_group_size.min
      max_size     = var.node_group_size.max
      desired_size = var.node_group_size.desired
      instance_types = ["t3.medium"]
      capacity_type  = "ON_DEMAND"
    }
  }
}

output "cluster_name"            { value = module.eks.cluster_name }
output "cluster_endpoint"        { value = module.eks.cluster_endpoint }
output "cluster_oidc_issuer_url" { value = module.eks.cluster_oidc_issuer_url }
output "node_security_group_id"  { value = module.eks.node_security_group_id }

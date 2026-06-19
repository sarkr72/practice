# EKS cluster that hosts Spinnaker (operator + microservices). Uses the
# upstream `terraform-aws-modules/eks/aws` v20 module so we don't reinvent
# control plane / node group / addon wiring.
#
# Sizing: 2x t3.large = 16 GB / 4 vCPU total. Spinnaker's microservices
# (deck, gate, clouddriver, orca, front50, echo, rosco) sum to ~6-8 GB
# headroom, which fits comfortably.
#
# IRSA is enabled so SAs in the spinnaker namespace can assume the AWS role
# defined in iam.tf without long-lived keys.

module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.0"

  cluster_name    = var.cluster_name
  cluster_version = var.cluster_version

  cluster_endpoint_public_access = true

  enable_cluster_creator_admin_permissions = true

  vpc_id     = data.aws_vpc.default.id
  subnet_ids = data.aws_subnets.default.ids

  eks_managed_node_groups = {
    spinnaker = {
      instance_types = [var.node_instance_type]
      min_size       = 1
      max_size       = 3
      desired_size   = var.node_desired_size
      capacity_type  = "ON_DEMAND"
    }
  }

  cluster_addons = {
    coredns                = {}
    kube-proxy             = {}
    vpc-cni                = {}
    eks-pod-identity-agent = {}
  }
}

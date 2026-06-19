output "cluster_name" {
  description = "EKS cluster name."
  value       = module.eks.cluster_name
}

output "cluster_endpoint" {
  description = "EKS API server endpoint."
  value       = module.eks.cluster_endpoint
}

output "kubeconfig_command" {
  description = "Run this to populate ~/.kube/config for the Spinnaker cluster."
  value       = "aws eks update-kubeconfig --name ${module.eks.cluster_name} --region ${var.aws_region}"
}

output "spinnaker_role_arn" {
  description = "IRSA role ARN — annotate Spinnaker SAs with this in the SpinnakerService manifest."
  value       = aws_iam_role.spinnaker.arn
}

output "persistence_bucket" {
  description = "S3 bucket for front50 / clouddriver persistence."
  value       = aws_s3_bucket.persistence.id
}

output "oidc_provider_arn" {
  description = "EKS OIDC provider ARN (already wired into IRSA, exposed for ad-hoc roles)."
  value       = module.eks.oidc_provider_arn
}

output "deck_url_hint" {
  description = "After `kubectl apply`-ing the SpinnakerService, find the Deck URL with: kubectl -n spinnaker get svc spin-deck"
  value       = "kubectl -n spinnaker get svc spin-deck -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'"
}

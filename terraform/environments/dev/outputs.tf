# These outputs feed into Kubernetes ConfigMaps/Secrets in the deploy pipeline.

output "vpc_id" {
  value = module.vpc.vpc_id
}

output "eks_cluster_name" {
  value = module.eks.cluster_name
}

output "eks_cluster_endpoint" {
  value = module.eks.cluster_endpoint
}

output "ecr_repository_url" {
  value = module.ecr.repository_url
}

output "rds_endpoint" {
  value     = module.rds.endpoint
  sensitive = true
}

output "rds_secret_arn" {
  description = "Secrets Manager ARN holding the RDS master password"
  value       = module.rds.secret_arn
}

output "redis_endpoint" {
  value = module.redis.primary_endpoint
}

output "kafka_bootstrap_brokers_sasl_iam" {
  description = "Bootstrap broker string for IAM auth — put in SPRING_KAFKA_BOOTSTRAP_SERVERS"
  value       = module.kafka.bootstrap_brokers_sasl_iam
}

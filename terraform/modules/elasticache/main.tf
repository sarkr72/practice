terraform {
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.60" }
  }
}

variable "name"                    { type = string }
variable "vpc_id"                  { type = string }
variable "subnet_ids"              { type = list(string) }
variable "allowed_security_groups" { type = list(string) }
variable "node_type"               { type = string }
variable "num_cache_nodes"         { type = number }

resource "aws_elasticache_subnet_group" "this" {
  name       = "${var.name}-redis"
  subnet_ids = var.subnet_ids
}

resource "aws_security_group" "this" {
  name   = "${var.name}-redis"
  vpc_id = var.vpc_id
}

resource "aws_security_group_rule" "ingress_redis" {
  for_each                 = toset(var.allowed_security_groups)
  type                     = "ingress"
  from_port                = 6379
  to_port                  = 6379
  protocol                 = "tcp"
  source_security_group_id = each.value
  security_group_id        = aws_security_group.this.id
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id = "${var.name}-redis"
  description          = "EMS Redis (${var.name})"
  engine               = "redis"
  engine_version       = "7.1"
  node_type            = var.node_type
  num_cache_clusters   = var.num_cache_nodes
  port                 = 6379

  subnet_group_name    = aws_elasticache_subnet_group.this.name
  security_group_ids   = [aws_security_group.this.id]

  # Hard requirements for banking workloads — encrypt in flight + at rest
  transit_encryption_enabled = true
  at_rest_encryption_enabled = true
  automatic_failover_enabled = var.num_cache_nodes > 1

  snapshot_retention_limit = 5
}

output "primary_endpoint" { value = aws_elasticache_replication_group.this.primary_endpoint_address }

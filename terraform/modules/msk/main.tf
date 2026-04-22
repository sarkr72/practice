terraform {
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.60" }
  }
}

variable "name"                    { type = string }
variable "vpc_id"                  { type = string }
variable "subnet_ids"              { type = list(string) }
variable "allowed_security_groups" { type = list(string) }
variable "instance_type"           { type = string }
variable "number_of_broker_nodes"  { type = number }
variable "kafka_version"           { type = string }

resource "aws_security_group" "this" {
  name   = "${var.name}-msk"
  vpc_id = var.vpc_id
}

# IAM auth uses port 9098; TLS (cert-based) uses 9094. We enable IAM here.
resource "aws_security_group_rule" "ingress_iam" {
  for_each                 = toset(var.allowed_security_groups)
  type                     = "ingress"
  from_port                = 9098
  to_port                  = 9098
  protocol                 = "tcp"
  source_security_group_id = each.value
  security_group_id        = aws_security_group.this.id
}

resource "aws_msk_cluster" "this" {
  cluster_name           = var.name
  kafka_version          = var.kafka_version
  number_of_broker_nodes = var.number_of_broker_nodes

  broker_node_group_info {
    instance_type   = var.instance_type
    client_subnets  = var.subnet_ids
    security_groups = [aws_security_group.this.id]
    storage_info {
      ebs_storage_info { volume_size = 100 }
    }
  }

  client_authentication {
    sasl { iam = true }
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  # 2 = in-sync replicas required for acks=all writes to succeed.
  # Only safe when number_of_broker_nodes >= 3; dev clusters with 2 brokers should use 1.
  configuration_info {
    arn      = aws_msk_configuration.this.arn
    revision = aws_msk_configuration.this.latest_revision
  }

  open_monitoring {
    prometheus {
      jmx_exporter  { enabled_in_broker = true }
      node_exporter { enabled_in_broker = true }
    }
  }
}

resource "aws_msk_configuration" "this" {
  name              = "${var.name}-config"
  kafka_versions    = [var.kafka_version]
  server_properties = <<-EOT
    auto.create.topics.enable=false
    default.replication.factor=${var.number_of_broker_nodes >= 3 ? 3 : 2}
    min.insync.replicas=${var.number_of_broker_nodes >= 3 ? 2 : 1}
    num.partitions=3
    unclean.leader.election.enable=false
  EOT
}

output "bootstrap_brokers_sasl_iam" {
  value = aws_msk_cluster.this.bootstrap_brokers_sasl_iam
}

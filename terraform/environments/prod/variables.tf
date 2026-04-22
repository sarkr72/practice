variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "environment" {
  type    = string
  default = "prod"
}

variable "owner" {
  type = string
}

variable "vpc_cidr" {
  type    = string
  default = "10.30.0.0/16"
}

variable "availability_zones" {
  type    = list(string)
  default = ["us-east-1a", "us-east-1b", "us-east-1c"]
}

variable "eks_node_size" {
  type = object({
    desired = number
    min     = number
    max     = number
  })
  default = {
    desired = 4
    min     = 4
    max     = 12
  }
}

variable "rds_instance_class" {
  type    = string
  default = "db.r6g.large"
}

variable "redis_node_type" {
  type    = string
  default = "cache.r6g.large"
}

variable "msk_instance_type" {
  type    = string
  default = "kafka.m5.large"
}

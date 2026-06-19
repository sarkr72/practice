# Spinnaker on EKS

Minimal AWS-hosted Spinnaker for learning. Hosts the Spinnaker control plane
on a small EKS cluster, persists state to S3, and uses IRSA so clouddriver
can deploy into ECS Fargate without long-lived AWS keys.

```
                                    EKS (spinnaker)
                                  ┌─────────────────┐
   GitHub ──► Jenkins ──webhook──►│ gate ── deck    │
                                  │  │              │
                                  │  ▼              │
                                  │ orca ── front50 │──► S3 (persistence)
                                  │  │              │
                                  │  ▼              │
                                  │ clouddriver ────┼──► ECS Fargate (ems)
                                  └─────────────────┘    (IRSA role)
```

## Scope of this module

| In                                         | Out                            |
|--------------------------------------------|--------------------------------|
| EKS cluster (default VPC, public endpoint) | Custom VPC / private subnets   |
| S3 persistence bucket                      | TLS / Route53 / ACM cert       |
| IRSA role (S3 + ECS + ELB + ECR)           | OIDC SSO / Cognito             |
| Spinnaker Operator install manifests       | Kayenta canary analysis        |
| SpinnakerService CR (S3, ECS, Jenkins)     | Multi-account deploys          |

This module deploys Spinnaker in HTTP-only mode. Add TLS, an ingress
controller, and an auth provider before exposing it past your own IP.

## Apply

```bash
cd terraform/spinnaker

# One-time, if not already done for terraform/ems:
#   create rinku-tfstate-001 bucket + terraform-locks DynamoDB table
#   (see providers.tf for the exact commands)

cat > account.auto.tfvars <<EOF
aws_account_id = "<your-12-digit-account>"
EOF

terraform init
terraform apply

aws eks update-kubeconfig --name spinnaker --region us-east-1
```

## Install Spinnaker

Two steps, each in its own README:

1. Operator: `manifests/operator/README.md`
2. SpinnakerService: `manifests/spinnaker/README.md`

## Connecting EMS

After both Spinnaker and `terraform/ems` are applied:

```bash
spin pipeline save --file ../../spinnaker/pipelines/ems-deploy-cicd.json
```

The pipeline JSON's `aws-dev` / `aws-prod-ecs` account names match the
SpinnakerService config. Pipeline references the same ECS cluster, target
group, IAM, and security group names that `terraform/ems` creates.

## Cost note

EKS control plane is $0.10/hr (~$73/mo). 2x t3.large nodes are ~$120/mo.
Run `terraform destroy` between learning sessions if you're cost-sensitive.

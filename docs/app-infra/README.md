# App infra — README

This folder explains the EMS application's AWS platform: the ALB, ECS cluster
(empty box), RDS database, ECR registry, IAM roles, and secrets. It's the
**stage**; the running app comes via `app-deploy/`.

| File | Read it when |
|---|---|
| `INSTRUCTIONS.md` | Standing it up. PowerShell, top to bottom. |
| `DEBUG.md` | Something failed. Every error we hit live, with the why. |
| `README.md` | You want to understand what each file does. (You're here.) |

---

## What's in this layer

```
terraform/ems/
   ECR repo               ← image lives here
   ALB + stable + canary  ← public entry point
   target groups            (stable=live traffic; canary=Spinnaker uses)
   ECS cluster "ems-dev"  ← the box (the SERVICE that runs in it is a
                            SEPARATE layer: terraform/ems-app)
   IAM roles               (task + task-exec)
   security groups         (ALB ↔ tasks ↔ DB)
   RDS MySQL              ← persistence
   SSM params + Secrets   ← DB host/port/name/user (SSM) + password (SM)
   subnet tags            ← lets Spinnaker resolve subnetType (optional)
```

Two important boundaries:

1. **This is the platform layer; the ECS service is a separate layer.** The
   cluster, ALB, IAM, RDS, ECR and secrets live here (durable, expensive to
   lose). The ECS *service* lives in its own state at `terraform/ems-app` (see
   `terraform/README.md`) — so you can destroy the app on its own with
   `./scripts/deploy.sh <env> destroy-app` and the database here is never
   touched. The service carries `ignore_changes = [task_definition,
   desired_count]`; the deploy workflow owns the task-def revisions (see
   `docs/app-deploy/`). Apply order: this layer first, then `ems-app`. Destroy
   order: `ems-app` first, then this layer.
2. **The Spinnaker subnet tag is here**, in `app-infra`, even though it's
   *for* Spinnaker. That's because the tag goes on the default-VPC subnets
   the app uses — Terraform's `ems` root is what queries those subnets, so
   it's the natural owner.

---

## File-by-file: `terraform/ems/`

### Provider + state plumbing

| File | What it does |
|---|---|
| `providers.tf` | AWS provider declaration + S3 backend pointing at the same `rinku-tfstate-001` bucket the spinnaker root uses, but under key `ems/...` and with `workspace_key_prefix = "env"` (so dev / perf / prod live at different state keys). |
| `variables.tf` | Input knobs: `aws_account_id`, `env` (dev/perf/prod), `db_name`, `db_instance_class`, `db_allocated_storage`, `db_engine_version`, `create_read_replica`. Has a validation block forcing `env` to be one of `dev`, `perf`, `prod`. |
| `envs/dev.tfvars` | Concrete values for `dev`: small DB, no replica. |
| `envs/perf.tfvars` | Bigger DB for load tests. |
| `envs/prod.tfvars` | Production sizing, replica on. |
| `envs/local.tfvars` | Placeholder for local-only testing. |
| `main.tf` | Just defines `local.app = "ems"` — the name prefix every other resource uses. Change this line in one place and every resource gets renamed. |
| `data.tf` | Reads existing things: default VPC, subnets filtered to EKS-supported AZs (`1a, 1b, 1c, 1d, 1f` — excludes `1e`), and your AWS account ID. Doesn't create anything. |
| `outputs.tf` | Prints everything downstream needs: `alb_url`, `alb_dns_name`, `ecs_cluster`, target group ARNs/names, `tasks_security_group_id`, `subnet_ids`, IAM role ARNs/names, `log_group`, `ecr_repository_uri`, RDS endpoints, SSM parameter paths, secret ARNs. |

### Resource files (the actual AWS infrastructure)

| File | What it creates | Why |
|---|---|---|
| `network.tf` | 2 security groups: `ems-<env>-alb` (allows HTTP from internet) and `ems-<env>-tasks` (allows port 8080 from the ALB SG only, plus all egress). | Two-tier network model: nothing reaches your app except via the ALB. |
| `alb.tf` | The Application Load Balancer, **two target groups** (stable + canary), an HTTP listener defaulting to stable, and an optional host-header rule routing `canary.<app>-<env>.example.com` to the canary group. Health check: `GET /actuator/health` → 200, every 30s, deregistration delay 30s. | Two-tier deploy model: stable = real traffic; canary = synthetic / Spinnaker traffic. |
| `ecs.tf` | The ECS cluster (`ems-<env>`) with container insights, capacity providers FARGATE + FARGATE_SPOT, and the CloudWatch log group `/ecs/ems-<env>` with env-dependent retention (30 days in prod, 7 in dev). | Cluster = empty box. Log group = where every task's stdout/stderr ends up. |
| `iam.tf` | Two IAM roles: `ems-<env>-task-exec` (used by the ECS *agent* to pull the image and read secrets — attaches `AmazonECSTaskExecutionRolePolicy`) and `ems-<env>-task` (the app's runtime role — empty for now, add S3/SQS/etc. perms here as the app grows). | Separation: image-pulling and secret-reading happen as one identity; the app itself runs as a separate identity with no AWS access by default. |
| `ecr.tf` | The container image registry. `force_delete = var.env != "prod"` so `terraform destroy` doesn't fail on a repo that still holds images (non-prod); prod stays `false` to protect release images. Lifecycle policy: expire untagged after 7 days, keep last 10 tagged. | The image shelf the deploy workflow pushes to. |
| _(service moved)_ | The ECS Fargate **service** + **bootstrap task definition** are no longer in this layer — they live in `terraform/ems-app` and read this layer's outputs via `terraform_remote_state`. | The split lets you destroy the app on its own without touching the database here. Making the service a Terraform resource (in either layer) is what keeps `terraform destroy` clean: the provider drains tasks, waits out the Fargate ENIs (no `DependencyViolation`), then frees the cluster (no `ClusterContainsServicesException`). |
| `rds.tf` | RDS MySQL primary (`ems-<env>-primary`), optional read replica, DB subnet group, DB security group (allows MySQL :3306 from the tasks SG only), a `random_password` (32 chars) stored in the Secrets Manager secret from `secrets.tf`, env-dependent backup retention (7 days prod, 1 day non-prod), deletion protection on prod, performance insights on prod, a custom MySQL 8.0 parameter group with slow query log on. | The database. SG-locked-to-tasks means even if someone gets your AWS console creds, they can't connect to your DB from a laptop. |
| `secrets.tf` | SSM parameters for non-sensitive config (`/<env>/ems/DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`), Secrets Manager for sensitive (`DB_PASSWORD` always; `JWT_SECRET` prod-only). A read policy on the SSM params + secrets ARNs is attached to the `task-execution` role so the ECS agent can fetch them at task start. | Split: non-sensitive things change with infra and belong in SSM. Real secrets need KMS encryption + rotation + 30-day recovery in prod — Secrets Manager. |
| `spinnaker_subnet_tags.tf` | An `aws_ec2_tag` resource (for each default-VPC subnet) that adds an `immutable_metadata` tag with JSON value `{"purpose":"ecs-tasks-<env>","target":"ec2"}`. | This is what lets Spinnaker resolve `subnetType: "ecs-tasks-dev"` to the actual subnet IDs. Without it, Spinnaker's `SecurityGroupSelector` NPEs (see `docs/spinnaker-infra/DEBUG.md` for the full story). If you don't use Spinnaker, this tag is harmless — nothing else reads it. |

---

## Workflow architecture — what calls what, when

```
1. YOU (one-time, before any apply):
   aws s3api create-bucket / put-versioning / put-public-access-block
   aws dynamodb create-table     terraform-locks
   (already done if you set up spinnaker-infra first — same bucket + table)

2. YOU (laptop, per environment):
   cd terraform/ems
   "aws_account_id = ..." > account.auto.tfvars
   terraform init
   terraform workspace new dev   (first time)
   terraform apply -var-file=envs/dev.tfvars

3. TERRAFORM (during that apply):
   - reads providers.tf → loads AWS plugin, connects to S3 backend.
   - loads ALL *.tf files in the dir, merges into one config.
   - resolves variables: defaults from variables.tf, overrides from
     dev.tfvars + account.auto.tfvars.
   - resolves locals (main.tf → local.app = "ems").
   - looks up data sources (data.tf → default VPC + subnets).
   - builds resource dep graph and creates AWS things in order:
       network → alb / iam / ecr / ecs / rds / secrets
       → spinnaker_subnet_tags
   - writes new state to s3://rinku-tfstate-001/env/dev/ems/terraform.tfstate
   - runs outputs.tf → prints alb_url, ecs_cluster(+arn), role ARNs, etc.

4. AWS (platform layer):
   You now have: an ECS cluster (empty — the service is created by the
   ems-app layer), an ALB with two target groups, an RDS MySQL ready for
   connections, an ECR repo waiting for images, IAM roles ready to be assumed,
   secrets stored, subnets tagged for Spinnaker.

5. NEXT: apply the app layer to put the service in the cluster:
   cd terraform/ems-app
   terraform workspace select dev
   terraform apply -var-file=envs/dev.tfvars

5. NEXT: the deploy workflow (docs/app-deploy/) puts a running app on top.
```

---

## Boundary with other folders

```
terraform/spinnaker/        terraform/ems/             .github/workflows/deploy.yml
(spinnaker-infra)           (app-infra, this folder)   (app-deploy)
   │                           │                          │
   │ creates github-actions-ems│                          │
   │ IAM role + trust          │                          │
   │ scoped to ems-* + ecr/ems │                          │
   │                           │                          │
   │ ←———— role used by ————————————————————————————————→ │
   │                           │                          │
   │                           │ creates ems-dev-task,    │
   │                           │ ems-dev-task-exec roles  │
   │                           │ scoped by name           │
   │                           │                          │
   │                           │ ← roles referenced by ── │
   │                           │   ecs/taskdef.dev.json.tpl
```

The contract between layers is purely **string names**:
- `github-actions-ems` policy allows access to `ecs:*` on `ems-*` cluster +
  service arns, and `iam:PassRole` on `ems-*-task*` roles.
- `terraform/ems` creates roles matching those patterns.
- `deploy.yml` looks up the SG by name `ems-dev-tasks`, target group by name
  `ems-dev-stable`, cluster by name `ems-dev`.

Rename `local.app` → update every layer.

---

## Cost reality check

With everything in dev sizing running:
- RDS db.t4g.micro: ~$0.50/day
- ALB: ~$0.55/day
- ECS Fargate (2× tasks @ 512cpu/1GB): ~$1/day
- ECR storage: pennies
- Total: **~$2–3/day** for app-infra alone.

Plus spinnaker-infra (~$6/day if running). Tear down both at end of day —
see `INSTRUCTIONS.md`.

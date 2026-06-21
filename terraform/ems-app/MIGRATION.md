# Migrating from the old single `ems` state to two layers

Before, `terraform/ems` held **everything**, including `aws_ecs_service.app` and
`aws_ecs_task_definition.bootstrap`, in one state (`ems/terraform.tfstate`).
Now the service lives in this `ems-app` layer (`ems/app/terraform.tfstate`).

You only need this if you have a **live deployment** in the old state. If
nothing is deployed (or you're starting fresh), skip all of this and just
`terraform apply` the `ems` layer then the `ems-app` layer.

> ⚠️ Run this where your AWS credentials and the S3 backend are reachable
> (your laptop or CI). It cannot be done from a sandbox without AWS access.
> Do it **per environment / workspace** (dev, then perf, then prod).

## What we're doing

- The **platform layer keeps the existing state** (same backend key), so the
  cluster / ALB / RDS / IAM stay managed with zero changes. We just `state rm`
  the two service resources from it (they move to the app layer).
- The **app layer adopts the running service** via `terraform import`, so there
  is **no downtime** — the live `ems-<env>` service is never recreated.

## Steps (example: `dev`)

```bash
# 0. Make sure the new code is checked out and account.auto.tfvars exists in
#    both terraform/ems and terraform/ems-app (or export TF_VAR_aws_account_id).

# 1. Apply the platform layer first so it exposes the new `ecs_cluster_arn`
#    output the app layer needs. (No infra changes besides that output.)
cd terraform/ems
terraform init
terraform workspace select dev
terraform apply -var-file=envs/dev.tfvars

# 2. Drop the service + bootstrap task def from the platform state. They are
#    gone from this layer's config now, so this just forgets them (does NOT
#    delete anything in AWS).
terraform state rm aws_ecs_service.app
terraform state rm aws_ecs_task_definition.bootstrap

# 3. Adopt the running service into the app layer.
cd ../ems-app
terraform init
terraform workspace new dev   # or: terraform workspace select dev
terraform import -var-file=envs/dev.tfvars \
  aws_ecs_service.app ems-dev/ems-dev   # format: <cluster-name>/<service-name>

# 4. Apply the app layer. The bootstrap task def is registered fresh here; the
#    service has ignore_changes=[task_definition], so this does NOT trigger a
#    rollout — the live CD task def keeps serving traffic.
terraform apply -var-file=envs/dev.tfvars
```

Repeat steps 1–4 with `perf`, then `prod` (selecting the matching workspace and
`envs/<env>.tfvars` each time).

## Verify

```bash
# Platform no longer tracks the service:
cd terraform/ems && terraform state list | grep ecs_service   # → no output

# App layer now owns it, pointed at the live task def:
cd ../ems-app && terraform state show aws_ecs_service.app | grep -E 'task_definition|status'
```

## Rollback

Nothing in AWS was deleted, so rollback is just state bookkeeping: re-add the
old `service.tf` to `terraform/ems`, `terraform import` the service back there,
and `state rm` it from `ems-app`. In practice you won't need to.

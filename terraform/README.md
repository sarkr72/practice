# Terraform layers

Infrastructure is split into **layers**, each with its own state file, ordered
by *blast radius* and *how often it changes*. You apply bottom-up and destroy
top-down. A layer only ever reads the layer below it (via
`terraform_remote_state`); nothing reads upward. This is what makes teardown
safe: you can destroy the throwaway app layer on its own, and the database is
in a different state file that the app teardown never touches.

```
            apply  ▼                         ▲  destroy
  ┌───────────────────────────────────────────────────────┐
  │  spinnaker/      (independent platform — EKS/Spinnaker)│   own state
  ├───────────────────────────────────────────────────────┤
  │  ems-app/        ECS service + bootstrap task def      │   "30-app"
  │                  (reads ems/ outputs via remote state) │   key: ems/app/…
  ├───────────────────────────────────────────────────────┤
  │  ems/            cluster, ALB, IAM, RDS, ECR, secrets, │   "20-platform"
  │                  security groups, log group            │   key: ems/…
  └───────────────────────────────────────────────────────┘
  (state bucket `rinku-tfstate-001` + lock table `terraform-locks`
   are the "00-bootstrap" layer — created once by hand, never destroyed.
   No network layer: we use the default VPC via a data source.)
```

## Why the split

| Layer | Blast radius | Changes | Destroyed |
|---|---|---|---|
| `ems/` (platform) | **high** — holds the RDS database, ALB, IAM | rarely | rarely |
| `ems-app/` (app) | **low** — just the service; redeploy in minutes | often | often |

Keeping the database and the service in **separate state files** means a routine
app teardown can't reach the database. They were one state before; now they're
two floors.

## Order — always

| | Order | Why |
|---|---|---|
| **Apply** | `ems` → `ems-app` | the app reads the platform's outputs, so the platform must exist first |
| **Destroy** | `ems-app` → `ems` | you can't pull the cluster/SG/subnets out from under a running service |

The same workspace name (`dev`/`perf`/`prod`) is used in both layers and must
match — `ems-app` reads `ems`'s state for the *same* env.

## Commands

```bash
# Stand everything up (platform then app):
./scripts/deploy.sh dev

# Tear EVERYTHING down (app then platform):
./scripts/deploy.sh dev destroy

# Tear down ONLY the app — keep the cluster, ALB, and database running:
./scripts/deploy.sh dev destroy-app
```

Or per layer by hand:

```bash
cd terraform/ems       && terraform workspace select dev && terraform apply   -var-file=envs/dev.tfvars
cd terraform/ems-app   && terraform workspace select dev && terraform apply   -var-file=envs/dev.tfvars
# destroy is the reverse:
cd terraform/ems-app   && terraform workspace select dev && terraform destroy -var-file=envs/dev.tfvars
cd terraform/ems       && terraform workspace select dev && terraform destroy -var-file=envs/dev.tfvars
```

From CI: **Actions → infra → Run workflow**, pick the `ems` or `ems-app` stack.

## Prod is intentionally protected

In `prod`, RDS (`deletion_protection`) and the ALB (`enable_deletion_protection`)
block destroy. The app layer (`ems-app`) still tears down cleanly in prod; the
platform layer (`ems`) requires flipping those flags off and applying first.
That guardrail is deliberate.

## Migrating an existing single-state deployment

If you already had everything in the old single `ems` state, see
[`ems-app/MIGRATION.md`](ems-app/MIGRATION.md) for the one-time `state rm` +
`import` steps. Greenfield? Just apply in order — no migration needed.

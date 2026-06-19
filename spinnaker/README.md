# Spinnaker pipeline (ECS Fargate)

Pipeline definition for the EMS app. Imports into an existing Spinnaker
install — provisioning Spinnaker itself is in `terraform/spinnaker/`.

## Order of operations

1. **Provision Spinnaker (one-time)** — see `terraform/spinnaker/README.md`.
   Stands up EKS, IRSA role, S3 persistence, installs the Operator, applies
   the SpinnakerService CR with both `aws-dev` and `aws-prod` ECS accounts.
2. **Provision EMS platform (per env)** — `./scripts/deploy.sh dev`,
   `./scripts/deploy.sh prod`. Creates ALB, target groups, ECS cluster, IAM
   roles, RDS, ECR, secrets, log groups.
3. **Create the Spinnaker application** — UI → Applications → Create:
   - Name: `ems`
   - Cloud providers: Amazon Web Services, Amazon ECS
4. **Import this pipeline** — `spin pipeline save --file spinnaker/pipelines/ems-deploy-cicd.json`

## Pre-requisites in Spinnaker

These come from `terraform/spinnaker` if you used that module:

1. ECS accounts `aws-dev` and `aws-prod` registered in the SpinnakerService.
2. Subnet attribute name `ecs-tasks-dev` and `ecs-tasks-prod` registered in
   clouddriver. Check with:
   ```
   spin clouddriver list-aws-subnet-types
   ```
3. IAM roles, target groups, security groups referenced by name — created by
   `terraform/ems`.

## Import the pipeline

```bash
spin pipeline save --file spinnaker/pipelines/ems-deploy-cicd.json
```

Or paste it into the UI: Applications → ems → Pipelines → Configure → Edit as JSON.

## Required edits before importing

| Item                       | Where in JSON                    | What to change to                              |
|----------------------------|----------------------------------|------------------------------------------------|
| `aws-dev` / `aws-prod`     | `credentials` fields             | Your ECS account names in Spinnaker            |
| `ecs-tasks-dev` / `-prod`  | `subnetType` fields              | Your registered subnet attribute names         |
| `#ems-releases`            | `notifications` blocks           | Your real Slack channel                        |
| `ems-dev.example.internal` | Smoke Test Dev URL               | Your real dev hostname                         |
| `ems.example.com`          | Smoke Test Prod URL              | Your real prod hostname                        |
| `ecrRegistry` default      | Set in pipeline UI after import  | `<account>.dkr.ecr.<region>.amazonaws.com`     |

The IAM role names, target group names, security group names, ECS cluster
names, and log group names already match what `terraform/ems` creates.
Don't rename one without the other.

## Webhook trigger

Jenkins POSTs to:
```
$SPINNAKER_BASE_URL/webhooks/webhook/ems-build-complete
```

Header: `X-Spinnaker-Token: <token>` (configured in `echo.yml` →
`webhooks.defaultSecret` or per-webhook auth).

Body parameters: `imageTag`, `branch`, `appId`, `buildUrl`, `ecrRegistry`, `ecrRepo`.

## Pipeline shape

```
Webhook (from Jenkins)
   │
   ▼
Deploy to Dev (createServerGroup, redblack)
   │     ↑ creates ems-dev-vNNN; old version disabled when new is healthy
   ▼
Smoke Test Dev
   │
   ▼
Manual Judgment ◄── only when branch == main
   │
   ▼
Deploy Prod Canary (createServerGroup, 1 task → canary target group)
   │
   ▼
Bake 10 min  (replace with Kayenta if available)
   │
   ▼
Deploy Prod Stable (createServerGroup, redblack → stable target group)
   │     ↑ 4 tasks; previous stable disabled
   ├─► Destroy Canary
   └─► Smoke Test Prod
```

`develop` branch builds stop after "Smoke Test Dev". Only `main` branch builds
run prod stages.

### How red/black works in ECS Spinnaker

When you re-deploy to dev, Spinnaker:
1. Creates a new ECS service `ems-dev-vNNN+1` with the new task definition.
2. Registers its tasks to the same target group (`ems-dev-stable`).
3. Once the new tasks are healthy, the ALB starts routing traffic to them.
4. Spinnaker disables the previous server group (deregisters its tasks).
5. Once `maxRemainingAsgs=2` is exceeded, oldest disabled groups are destroyed.

Rollback = re-enable the previous server group. Spinnaker UI has a one-click
button for this; nothing for you to script.

### How canary works

Canary tasks register to a separate target group (`ems-prod-canary`).
The default ALB listener routes 100% to stable, so canary takes no real
traffic. A listener rule for host header `canary.ems-prod.example.com`
routes to the canary target group, so synthetic probes (curl, your monitoring)
can hit canary directly during the bake.

If you want true traffic-shifting canary (e.g., 5% real traffic to canary for
10 minutes), edit the listener's `default_action` in `terraform/main.tf` to use
weighted forward across both target groups.

## Replacing the bake stage with Kayenta

If your Spinnaker has Kayenta installed, replace the "Bake Canary (10 min)"
wait stage with a Kayenta `canaryAnalysis` stage that compares canary vs
stable CloudWatch metrics. The wait stage is a placeholder so the pipeline
runs without Kayenta.

# Spinnaker setup (ECS Fargate)

## One-time application creation

In Spinnaker UI:
1. Applications → Create Application
2. Name: `ems`, Owner: rinku@example.com
3. Cloud providers: **Amazon Web Services, Amazon ECS**

## Pre-requisites in Spinnaker

These are clouddriver / halyard concerns; ask your platform team if you don't
own the Spinnaker install.

1. ECS accounts registered: `aws-dev` and `aws-prod`. Each must have IAM
   permissions to manage ECS services + task definitions in the account.
2. Subnet attribute name `ecs-tasks-dev` and `ecs-tasks-prod` registered (this
   is what `subnetType` in the pipeline JSON refers to). You can check with:
   ```
   spin clouddriver list-aws-subnet-types
   ```
3. The IAM roles, target groups, and security groups that the pipeline
   references by name must exist — Terraform creates them.

## Apply terraform first

```bash
./scripts/deploy.sh dev
./scripts/deploy.sh prod
```

This creates: ALB, target groups (stable + canary), ECS cluster, IAM roles,
RDS MySQL, ECR repo, secrets, log groups. Spinnaker creates the ECS services
and task definitions on its first pipeline run.

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
names, and log group names already match what `terraform/main.tf` creates.
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
2. Registers its tasks to the same target group (`image-uploader-dev-stable`).
3. Once the new tasks are healthy, the ALB starts routing traffic to them.
4. Spinnaker disables the previous server group (deregisters its tasks).
5. Once `maxRemainingAsgs=2` is exceeded, oldest disabled groups are destroyed.

Rollback = re-enable the previous server group. Spinnaker UI has a one-click
button for this; nothing for you to script.

### How canary works

Canary tasks register to a separate target group (`image-uploader-prod-canary`).
The default ALB listener routes 100% to stable, so canary takes no real
traffic. A listener rule for host header `canary.image-uploader-prod.example.com`
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

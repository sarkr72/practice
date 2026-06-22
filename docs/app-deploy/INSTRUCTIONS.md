# App deploy — instructions

Wire up GitHub Actions so a push to `main` deploys your app to ECS Fargate.

> Prerequisites:
> 1. `docs/spinnaker-infra/INSTRUCTIONS.md` Phase 1–2 (creates the
>    `github-actions-ems` IAM role).
> 2. `docs/app-infra/INSTRUCTIONS.md` Phase 2 (platform: ECS cluster, ALB,
>    RDS, ECR, IAM, secrets), then apply the `terraform/ems-app` layer — it
>    creates the ECS service the workflow rolls task defs onto.

---

## Phase 1 — Set GitHub variables

Get the values from Terraform:

```powershell
cd E:\projects\practice\terraform\spinnaker
$ROLE_ARN = (terraform output -raw github_actions_role_arn)

"AWS_ROLE_ARN  = $ROLE_ARN"
"AWS_REGION    = us-east-1"
```

> **Which ARN is "the" ARN?** Terraform's spinnaker outputs include several
> IAM role ARNs. The one for **GitHub Actions deploying the app** is
> `github_actions_role_arn` (no `_infra`). The others:
> - `github_actions_infra_role_arn` → for the manual infra workflow, not
>   the deploy workflow.
> - `spinnaker_role_arn` → for Spinnaker pods, not GitHub.
> - ECS task role ARNs → for the running container, not GitHub.

In GitHub, go to **Settings → Secrets and variables → Actions → Variables
tab → New repository variable**. Add:

| Name           | Value           |
|----------------|-----------------|
| `AWS_ROLE_ARN` | `$ROLE_ARN`     |
| `AWS_REGION`   | `us-east-1`     |

That's it for required config. Two variables, no secrets.

---

## Phase 2 — Trigger the first deploy

```powershell
cd E:\projects\practice
git pull origin main          # make sure you have the latest workflow

# either make a real change, or trigger an empty commit:
git commit --allow-empty -m "first deploy"
git push origin main
```

Watch in **GitHub → Actions tab**. The `deploy` workflow runs:

1. `Checkout` (~2s)
2. `Set up JDK 21` (~30s the first time, ~5s cached)
3. `Configure AWS credentials` (~3s — the OIDC handshake)
4. `Log in to Amazon ECR` (~2s)
5. `Run tests` (~1m)
6. `Build and push image with Jib` (~2–3m the first time, ~30s cached)
7. `Discover AWS account, VPC, subnets, security group, target group` (~5s)
8. `Render task definition` (~1s)
9. `Register new task definition revision` (~3s)
10. `Create or update the ECS service` (~5s — first push says "creating")
11. `Wait for service to stabilize` (~2–3m — ECS rolls the tasks)
12. (Optional) `Trigger Spinnaker` — only runs if `SPINNAKER_GATE_URL` set
13. `Summary` (~1s)

Total: ~5–8 minutes first push, ~3–5 minutes subsequent.

When the workflow finishes green, verify:

```powershell
cd E:\projects\practice\terraform\ems
$ALB = (terraform output -raw alb_url)
curl "$ALB/actuator/health"
```

Should return `{"status":"UP",...}`. The app is live.

---

## Phase 3 — Verify the deploy under the hood

```powershell
# What service was created?
aws ecs list-services --cluster ems-dev
# expect:  "serviceArns": [".../service/ems-dev/ems-dev"]

# How many tasks running vs desired?
aws ecs describe-services --cluster ems-dev --services ems-dev `
  --query "services[0].{Desired:desiredCount,Running:runningCount,TaskDef:taskDefinition}"

# Latest task definition revision
aws ecs describe-task-definition --task-definition ems-dev `
  --query "taskDefinition.revision"

# What does ECS think happened?
aws ecs describe-services --cluster ems-dev --services ems-dev `
  --query "services[0].events[0:5]"

# App's own logs
aws logs tail /ecs/ems-dev --since 5m --format short
```

---

## Phase 4 — Rollback

ECS keeps every task definition revision. Rollback is one CLI call:

```powershell
# list recent revisions
aws ecs list-task-definitions --family-prefix ems-dev --sort DESC --max-items 5

# roll back to a known-good revision (paste an ARN from the list)
aws ecs update-service --cluster ems-dev --service ems-dev `
  --task-definition arn:aws:ecs:us-east-1:<account>:task-definition/ems-dev:<n> `
  --force-new-deployment

# wait for it to be stable
aws ecs wait services-stable --cluster ems-dev --services ems-dev
```

ECS rolls back the same way it rolls forward: deploy controller, ALB
health check, no downtime. ~2–3 min.

---

## Phase 5 (optional) — Add Spinnaker parallel learning

Only if you set up `spinnaker-infra` and want the Spinnaker UI to show
deploys. The real deploy stays via `aws ecs` — this just adds a parallel
Spinnaker execution.

In `terraform/spinnaker`:
```powershell
$GATE = kubectl -n spinnaker get svc spin-gate -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
$WEBHOOK_TOKEN = [guid]::NewGuid().ToString()
"SPINNAKER_GATE_URL      = http://$GATE"
"SPINNAKER_WEBHOOK_TOKEN = $WEBHOOK_TOKEN"
```

In GitHub:
- **Variables tab**: add `SPINNAKER_GATE_URL = http://<gate-host>` (no port,
  no trailing slash — the NLB exposes Gate on port 80).
- **Secrets tab**: add `SPINNAKER_WEBHOOK_TOKEN = <whatever>`.

Open the Gate NLB to GitHub runners — see `docs/spinnaker-infra/DEBUG.md →
Locking down access` (you'll edit the EKS node SG, not the NLB itself).

Then re-import the Spinnaker pipeline:
```powershell
spin pipeline save --file E:\projects\practice\spinnaker\pipelines\ems-deploy-dev-only.json
```

Refresh clouddriver's cache so it picks up the subnet `immutable_metadata`
tag added by `terraform/ems` (skip only if you're certain clouddriver
started **after** that terraform apply):

```powershell
kubectl -n spinnaker rollout restart deploy/spin-clouddriver
kubectl -n spinnaker rollout status deploy/spin-clouddriver
Start-Sleep -Seconds 120
```

Without this the first Spinnaker run NPEs in Monitor Deploy with
`Cannot invoke "java.util.Collection.size()" because "c" is null`. ECS
itself still deploys fine — only the Spinnaker stage fails.

Next `git push origin main`:
- GitHub Actions deploys to `ems-dev` (the real deploy).
- Spinnaker runs in parallel, creates `ems-spinnaker-vNNN` on the canary
  target group (cosmetic — doesn't affect live traffic).

---

## Phase 6 — Tear down / pause the running app

This workflow doesn't own any persistent AWS resources of its own — it only
registers *task-definition revisions* and rolls them onto the `ems-dev`
service. The service itself (and the cluster, ALB, RDS, ECR) is owned by
`terraform/ems` (`docs/app-infra`). So the right "teardown" depends on how cold
you want to go:

### Pause without losing anything (cheapest while keeping infra)

Stop the tasks but keep the service, ALB target group bindings, RDS, ECR,
and the cluster itself:

```powershell
aws ecs update-service --cluster ems-dev --service ems-dev --desired-count 0
```

Bill drops by the Fargate task cost (~$1/day). ALB + RDS keep ticking.
Set `--desired-count 2` to resume — no rebuild, no redeploy needed. This is
safe even though the service is Terraform-managed: `service.tf` has
`ignore_changes = [desired_count]`, so Terraform won't fight you or reset the
count on the next apply.

### Don't `aws ecs delete-service` by hand

The service is now a Terraform resource. Deleting it out-of-band makes
Terraform state drift (the next `plan` wants to recreate it) and breaks the
next deploy's `update-service`. To remove the service, destroy the infra layer
below — Terraform takes the service down in graph order as part of it.

### Full teardown

`terraform destroy` on `app-infra` removes the service cleanly (tasks drained,
ENIs detached, then cluster) along with the rest of the platform. See
`docs/app-infra/INSTRUCTIONS.md` Phase 5.

> Task definition revisions left behind by past deploys go INACTIVE after a
> destroy but stay listed in the ECS console. They cost $0 and are useful
> rollback archaeology — leave them.

---


1. `docs/spinnaker-infra/INSTRUCTIONS.md` Phase 1–2 (S3 bucket + EKS or
   just the GitHub OIDC role).
2. `docs/app-infra/INSTRUCTIONS.md` Phase 2 (ECS cluster, ALB, RDS, ECR).
3. *(here)* Phase 1 — set `AWS_ROLE_ARN` and `AWS_REGION` GitHub Variables.
4. *(here)* Phase 2 — push to `main`.

Terraform creates the service; each push rolls a new task def onto it.

---

## What can't go wrong

- **No AWS credentials in GitHub.** Compromised secrets can't deploy.
- **Old task defs preserved.** ECS keeps all revisions; rollback is one CLI
  call away.
- **Branch-scoped.** OIDC trust only honors `main` of `sarkr72/practice`.
  Pushes elsewhere can't deploy.
- **Tests gate the deploy.** Maven test failures stop the workflow at step 5
  — no broken image goes to ECR.
- **Health-checked rollout.** `aws ecs wait services-stable` blocks until
  ALB marks tasks healthy. If startup hangs, the workflow exits non-zero.

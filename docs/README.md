# EMS — documentation index

This project deploys a Spring Boot app (EMS) to AWS ECS Fargate via GitHub
Actions, with a Spinnaker control plane on EKS for learning.

The docs are split into **three layers**, each in its own folder with the
same three files: `INSTRUCTIONS.md` (how to set it up), `DEBUG.md` (what goes
wrong + why), and `README.md` (what every file/section does).

| Layer | Folder | What it covers |
|---|---|---|
| **1. Spinnaker infra** | [`spinnaker-infra/`](spinnaker-infra/) | The EKS cluster, IAM/OIDC, and Spinnaker install. Also creates the GitHub OIDC role the app deploy uses. |
| **2. App infra** | [`app-infra/`](app-infra/) | The EMS platform: ALB, ECS cluster (empty), RDS, ECR, IAM, secrets, subnet tags. |
| **3. App deploy** | [`app-deploy/`](app-deploy/) | The GitHub Actions workflow that builds the image and deploys it to ECS on every push to `main`. |

---

## The big picture

```
                  ┌─────────────────────────────────────────────────┐
   git push main  │  GitHub Actions (.github/workflows/deploy.yml)   │
        ─────────►│  test → Jib → ECR → aws ecs create/update-service│
                  └───────────────┬─────────────────────────────────┘
                                  │ deploys to
                                  ▼
   ┌──────────────────────────── AWS ────────────────────────────┐
   │                                                              │
   │  LAYER 2 (app-infra, terraform/ems):                         │
   │    ALB → ECS cluster "ems-dev" → tasks → RDS MySQL           │
   │    ECR, IAM roles, secrets, subnet tags                      │
   │                                                              │
   │  LAYER 1 (spinnaker-infra, terraform/spinnaker):             │
   │    EKS cluster running Spinnaker (optional, learning)        │
   │    GitHub OIDC trust + the github-actions-ems role           │
   │                                                              │
   └──────────────────────────────────────────────────────────────┘
```

---

## Set-up order (from a clean clone + clean AWS account)

Run these in order, top to bottom:

| # | Where | What | Output you'll need next |
|---|---|---|---|
| 1 | `spinnaker-infra/INSTRUCTIONS.md` Phase 1 | One-time: create the S3 state bucket + DynamoDB lock table. | — |
| 2 | `spinnaker-infra/INSTRUCTIONS.md` Phase 2 | `terraform apply` — creates EKS, IAM, and the GitHub OIDC role. | `github_actions_role_arn` output |
| 3 | `spinnaker-infra/INSTRUCTIONS.md` Phase 3 | Install the Spinnaker control plane. **Skip if you don't want the Spinnaker UI** — Phase 2 alone is enough for the app deploy to work. | Deck/Gate NLB hostnames |
| 4 | `app-infra/INSTRUCTIONS.md` Phase 2 | `terraform apply -var-file=envs/dev.tfvars` — ALB, ECS cluster, RDS, ECR, secrets, subnet tags. | role/SG/TG names, ECR URI |
| 5 | `app-deploy/INSTRUCTIONS.md` Phase 1 | Set `AWS_ROLE_ARN` + `AWS_REGION` as GitHub repo variables. | — |
| 6 | `app-deploy/INSTRUCTIONS.md` Phase 2 | `git push origin main` — first deploy. | Live app on the ALB URL |

---

## I just want to…

| Question | Go to |
|---|---|
| Push code and see it deployed | `app-deploy/INSTRUCTIONS.md` |
| My deploy failed, where do I look? | `app-deploy/DEBUG.md` |
| Add a new AWS resource the app needs (queue, bucket, etc.) | `app-infra/README.md` → `terraform/ems/` file-by-file |
| Change DB sizing or env-specific values | `app-infra/INSTRUCTIONS.md` + `terraform/ems/envs/*.tfvars` |
| Spinnaker UI won't load or pods aren't healthy | `spinnaker-infra/DEBUG.md` |
| Tear it all down for the night | `spinnaker-infra/INSTRUCTIONS.md` → "Tearing it all down" + `app-infra/INSTRUCTIONS.md` teardown |
| Understand how `git push` becomes a running container | `app-deploy/README.md` → "Workflow architecture" |
| Understand who can assume which IAM role | `app-deploy/README.md` → "Trust chain summary" |

---

## Which deployer actually runs?

**GitHub Actions → `aws ecs` directly.** That's the real path.

Spinnaker is installed for learning and (optionally) runs a parallel
pipeline that deploys to an isolated stack on the canary target group. It is
**not** what your users hit. Spinnaker's ECS integration has long-standing
bugs (documented in `spinnaker-infra/DEBUG.md`); this repo includes the fix
(subnet `immutable_metadata` tags) but still keeps direct-ECS as the
production path — which is what most teams running Spinnaker on AWS do.

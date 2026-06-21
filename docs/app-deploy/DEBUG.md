# App deploy — debug guide

Every error class you can hit when a push to `main` doesn't end with a
running app, with the **why** and the fix.

---

## CI — Maven test stage

### `InvalidConfigDataPropertyException: Property 'spring.profiles.active' imported from location 'class path resource [application-test.properties]' is invalid in a profile specific resource`
**Why:** Spring Boot 2.4+ forbids setting `spring.profiles.active` inside a
profile-specific config file. `application-test.properties` is *already* the
test profile — activating itself is circular.

**Fix (already in repo):** the line was removed. Test profile is activated
by `@ActiveProfiles("test")` on test classes or `SPRING_PROFILES_ACTIVE` in
CI. Don't add `spring.profiles.active=...` back to that file.

---

### `JaCoCo coverage check failed: ratio is XX.YY but expected minimum is 0.70`
**Why:** JaCoCo's `check` execution in `pom.xml` enforces ≥70% line coverage.
A change dropped coverage below that.

**Fix:** add tests for the changed code, or (if you really need to ship)
lower the threshold in `pom.xml` AND mirror it in `jules.yml` —
both must match.

---

### `Node.js 20 is deprecated. The following actions target Node.js 20 but are being forced to run on Node.js 24`
**Why:** GitHub auto-runs old Node-20 actions on Node 24. The actions
themselves still work; GitHub just warns until they release Node-24
versions.

**Fix:** ignore — this is a warning, not a failure. To silence later, bump
`actions/checkout`, `actions/setup-java`, and
`aws-actions/configure-aws-credentials` to their `@v5` when available.

---

## CI — Jib build / ECR push

### `denied: not authorized to perform: ecr:PutImage` from Jib
**Why:** The `github-actions-ems` role doesn't have ECR push perms. Either
`terraform apply` of `terraform/spinnaker` didn't complete cleanly, or you
edited `github_oidc.tf` and removed the `EcrPushPull` statement.

**Fix:**
```powershell
aws iam get-role-policy --role-name github-actions-ems --policy-name github-actions-ems-ecr `
  --query "PolicyDocument.Statement[?Sid=='EcrPushPull']"
```
Should return the ECR push actions on the `ems` repo ARN. If empty, re-apply:
```powershell
cd terraform\spinnaker
terraform apply
```

---

### `Failed to authenticate with registry ... <account>.dkr.ecr.us-east-1.amazonaws.com`
**Why:** The ECR login step's auth wrote to a path Jib doesn't read, OR the
login expired (1 hour limit) before Jib pushed.

**Fix:** check the order in `deploy.yml` — ECR login must be **before** the
Jib step, and the Jib step shouldn't be more than ~50 minutes after login.
The repo's `deploy.yml` already does this; if you modified it, restore the
order.

---

## OIDC / AWS credentials

### `Not authorized to perform: sts:AssumeRoleWithWebIdentity` on the Configure AWS credentials step
**Why:** One of:
- `vars.AWS_ROLE_ARN` is wrong or missing.
- You pushed from a branch other than `main` — the OIDC trust in
  `github_oidc.tf` is scoped to `main`.
- The OIDC provider got deleted (rare; would only happen if you destroyed
  `terraform/spinnaker`).

**Fix:**
1. Confirm `AWS_ROLE_ARN` is set in **Variables tab**, not Secrets tab.
2. Confirm the value matches `terraform output -raw github_actions_role_arn`.
3. Confirm you're pushing to `main`. Branch-scoped trust is intentional —
   you do NOT want feature branches to deploy.

```powershell
# Sanity-check the trust still exists in AWS:
aws iam get-role --role-name github-actions-ems --query "Role.AssumeRolePolicyDocument.Statement[*].Action" --output json
```
You should see `"sts:AssumeRoleWithWebIdentity"`. If empty, re-apply
`terraform/spinnaker`.

---

### Workflow prints `Resolved SG = ` (blank) in the discovery step
**Why:** The SG `ems-dev-tasks` doesn't exist — `terraform/ems` wasn't
applied for the workspace + env you expect.

**Fix:**
```powershell
aws ec2 describe-security-groups --filters "Name=group-name,Values=ems-dev-tasks" --query "SecurityGroups[].GroupId" --output text
```
If empty, `terraform apply -var-file=envs/dev.tfvars` in `terraform/ems`.

---

## ECS — Register task definition

### `ResourceInitializationError: unable to pull secrets ... AccessDeniedException`
**Why:** The `task-execution` role can't read one of the SSM params or
Secrets Manager secrets referenced in `taskdef.json`. Either the secret
doesn't exist, or the read-policy attachment in `terraform/ems/secrets.tf`
broke.

**Fix:**
```powershell
# Check each one exists:
aws ssm get-parameter --name /dev/ems/DB_HOST
aws ssm get-parameter --name /dev/ems/DB_PORT
aws ssm get-parameter --name /dev/ems/DB_NAME
aws ssm get-parameter --name /dev/ems/DB_USERNAME
aws secretsmanager describe-secret --secret-id /dev/ems/DB_PASSWORD
```
If any returns ParameterNotFound, re-apply `terraform/ems`.

---

### Workflow's `register-task-definition` returns InvalidParameterException
**Why:** The rendered `taskdef.json` has a syntax issue — usually a
placeholder didn't substitute (`__ACCOUNT__` still literal).

**Fix:** check the workflow log for the "Rendered task definition:" output.
Confirm `executionRoleArn` and `taskRoleArn` start with `arn:aws:iam::`
followed by 12 digits, not `__ACCOUNT__`. If the literal is still there,
the AWS account discovery step failed.

---

## ECS — Roll out task definition

### `ServiceNotFoundException` / `update-service` says the service doesn't exist
**Why:** The `ems-dev` service is created by Terraform (`terraform/ems-app/service.tf`), not by this workflow — the workflow only rolls a new task def
onto it. If the service is missing, the `ems-app` layer hasn't been applied for
this environment yet.

**Fix:** apply the platform then app layers first (Actions → "infra" → ems /
apply / dev, then ems-app / apply / dev), then re-run the deploy. Confirm:
```powershell
aws ecs describe-services --cluster ems-dev --services ems-dev --query "services[0].status" --output text
# should print ACTIVE
```

---

### Service exists, `update-service` succeeds, but `services-stable` hangs
**Why:** ECS is trying to roll the new task in but the new tasks aren't
becoming healthy. Usually startup is failing or the ALB health check is
flapping.

**Fix:** check ECS service events and task logs in parallel:
```powershell
aws ecs describe-services --cluster ems-dev --services ems-dev `
  --query "services[0].events[0:10]"

aws logs tail /ecs/ems-dev --since 5m --format short --follow
```
Most common: DB connection failure (HikariPool errors) — RDS SG isn't
allowing connections from the tasks SG, or `DB_PASSWORD` is wrong.

---

### `services-stable` times out after 10 min
**Why:** Same as above — new tasks never went healthy and ECS gave up
trying.

**Fix:** roll back to the last known-good revision while you investigate:
```powershell
# find the previous revision number
aws ecs describe-services --cluster ems-dev --services ems-dev `
  --query "services[0].deployments[?status=='ACTIVE'].taskDefinition" --output text

# roll back
aws ecs update-service --cluster ems-dev --service ems-dev `
  --task-definition arn:aws:ecs:us-east-1:<account>:task-definition/ems-dev:<n-1> `
  --force-new-deployment
```

---

## Application — task starts, fails, retries

### ECS task `STOPPED` with `Essential container in task exited`
**Why:** The container's main process exited. The exit code tells you
roughly why (1 = generic, 137 = OOM kill, 143 = SIGTERM).

**Fix:** read the actual app logs:
```powershell
aws logs tail /ecs/ems-dev --since 15m --format short
```
Look for the last few lines before the stack trace ends — that's usually
the cause.

---

### Spring Boot crashes on startup with `Unable to obtain connection from database`
**Why:** Either `DB_PASSWORD` doesn't match what RDS has, or the tasks SG
can't reach the RDS SG.

**Fix:**
```powershell
# verify the secrets the task is reading
aws secretsmanager get-secret-value --secret-id /dev/ems/DB_PASSWORD --query "SecretString" --output text

# verify network path - does tasks SG ingress allow into RDS?
aws ec2 describe-security-groups --filters "Name=group-name,Values=ems-dev-db" `
  --query "SecurityGroups[0].IpPermissions"
# should show port 3306 from the tasks SG ID
```

---

### Spring Boot stuck at "Started EmsApplication in X seconds" but ALB health 503
**Why:** App is up but failing the ALB health check on `/actuator/health`.
Health check probes from ALB get blocked by network or fail validation
inside the app.

**Fix:** test the health endpoint from inside the cluster:
```powershell
# get a task IP
aws ecs list-tasks --cluster ems-dev --service-name ems-dev --query "taskArns[0]" --output text
aws ecs describe-tasks --cluster ems-dev --tasks <task-arn> --query "tasks[0].attachments[].details[?name=='privateIPv4Address'].value" --output text

# the tasks SG might not allow self-traffic; this is ALB → task, which uses ALB SG → tasks SG
# verify the rule exists:
aws ec2 describe-security-groups --filters "Name=group-name,Values=ems-dev-tasks" `
  --query "SecurityGroups[0].IpPermissions"
# should allow port 8080 from the ALB SG
```

---

## Spinnaker (optional, parallel learning)

### Spinnaker pipeline fails with `Failed to evaluate parameters.securityGroupId not found`
**Why:** You upgraded the pipeline to use parameterized subnet/SG IDs but
imported an old version that doesn't have those params declared. Or vice
versa.

**Fix:** the current `spinnaker/pipelines/ems-deploy-dev-only.json` uses
name-based resolution (`subnetType: "ecs-tasks-dev"`,
`securityGroups: ["ems-dev-tasks"]`). Re-import to ensure Spinnaker has
the current version:
```powershell
spin pipeline save --file E:\projects\practice\spinnaker\pipelines\ems-deploy-dev-only.json
```

---

### Spinnaker pipeline fails with the `Cannot invoke "java.util.Collection.size()" because "c" is null` NPE
**Why:** The `immutable_metadata` tag on subnets is missing or the
clouddriver cache hasn't refreshed since you added it.

**Fix:**
```powershell
# 1. confirm the tags exist on the subnets
$VPC_ID = (aws ec2 describe-vpcs --filters Name=isDefault,Values=true --query 'Vpcs[0].VpcId' --output text)
aws ec2 describe-subnets `
  --filters "Name=vpc-id,Values=$VPC_ID" "Name=tag-key,Values=immutable_metadata" `
  --query "Subnets[].{Id:SubnetId,Tag:Tags[?Key=='immutable_metadata']|[0].Value}" --output table

# if empty, terraform/ems didn't apply with spinnaker_subnet_tags.tf - re-apply.

# 2. force clouddriver to re-cache
kubectl -n spinnaker rollout restart deploy/spin-clouddriver
kubectl -n spinnaker rollout status deploy/spin-clouddriver
Start-Sleep -Seconds 120

# 3. retrigger
git commit --allow-empty -m "retry spinnaker"
git push origin main
```

For the deep version of this story see
`docs/spinnaker-infra/DEBUG.md → Phase 4 / IAM-related deploy errors`.

---

## Workflow itself

### Workflow shows "skipped" instead of running
**Why:** You pushed to a non-`main` branch, or the workflow file has a
syntax error and GitHub silently skipped it.

**Fix:**
- Check the branch you pushed to.
- Open the workflow file in the GitHub Actions tab — if the YAML is broken,
  GitHub shows the parse error there.

---

### Concurrent push triggered a second deploy mid-rollout
**Why:** Two pushes to `main` in quick succession. The
`concurrency.group: deploy-main` config queues them sequentially rather
than canceling — the second waits for the first to finish.

**Fix:** none needed. ECS handles overlapping deployments via its own
deployment controller. The second deploy will run with the latest commit's
image; previous in-flight tasks may be drained slightly faster.

---

## "Did the deploy actually do anything?" diagnostic

```powershell
# Show the latest task def revision, when it was registered, and what image it uses:
aws ecs describe-task-definition --task-definition ems-dev `
  --query "taskDefinition.{Revision:revision,Registered:registeredAt,Image:containerDefinitions[0].image}"

# Show what's actually running:
aws ecs describe-services --cluster ems-dev --services ems-dev `
  --query "services[0].{TaskDef:taskDefinition,Running:runningCount,Desired:desiredCount,Status:status,Events:events[0:3]}"

# Did the app see traffic?
aws logs tail /ecs/ems-dev --since 5m --format short
```

If revision is current, running == desired, and logs show recent HTTP
requests → it's working.

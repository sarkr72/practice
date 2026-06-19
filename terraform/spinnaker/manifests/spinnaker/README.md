# SpinnakerService install

Templated install. Substitute the placeholders from `terraform output`, then
apply.

## 1. Render the manifest

Run from this directory after `terraform apply` in `terraform/spinnaker`:

```bash
# Pull every value the manifest needs
export AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
export AWS_REGION=$(terraform -chdir=../../ output -raw -json 2>/dev/null \
  | jq -r '.cluster_endpoint.value' | awk -F'[/.]' '{print $5}')
export SPINNAKER_ROLE_ARN=$(terraform -chdir=../../ output -raw spinnaker_role_arn)
export PERSISTENCE_BUCKET=$(terraform -chdir=../../ output -raw persistence_bucket)
export JENKINS_BASE_URL="${JENKINS_BASE_URL:-http://jenkins.example.internal}"
export JENKINS_WEBHOOK_TOKEN="${JENKINS_WEBHOOK_TOKEN:-changeme}"

envsubst < spinnakerservice.yaml > /tmp/spinnakerservice.rendered.yaml
```

## 2. Apply

```bash
kubectl apply -f /tmp/spinnakerservice.rendered.yaml
```

## 3. Wait for rollout

Spinnaker takes 5-10 minutes on first install:

```bash
kubectl -n spinnaker get spinnakerservice spinnaker -w
kubectl -n spinnaker get pods
```

When `spin-deck` and `spin-gate` show as `Running`, find the UI:

```bash
kubectl -n spinnaker get svc spin-deck \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
```

Visit `http://<that-hostname>:9000`. No login — see the security warning in
`spinnakerservice.yaml` before exposing this beyond your IP.

## 4. Import the EMS pipeline

```bash
spin pipeline save --file ../../../../spinnaker/pipelines/ems-deploy-cicd.json
```

The pipeline references account names `aws-dev` and `aws-prod-ecs` defined
in the SpinnakerService above. Reconcile any name changes before importing.

# EMS Performance Testing

Load tests for the EMS service, runnable with **JMeter** locally and **BlazeMeter** in the cloud.
Both consume the same `jmeter/ems-load-test.jmx` plan, so the test surface is identical.

## Layout

```
performance/
  jmeter/
    ems-load-test.jmx     # JMeter test plan (parameterized)
  blazemeter.yml          # Taurus / BlazeMeter wrapper
  README.md
```

## Targeting the perf environment

The Spring profile `perf` is defined in `src/main/resources/application-perf.yml`.
Terraform variables for the env live in `terraform/ems/envs/perf.tfvars`.

The default target host is `ems-perf.internal:8080`. Override per-run.

## Run locally with JMeter

```sh
mvn -Pperf-test verify \
  -Dperf.host=ems-perf.internal \
  -Dperf.port=8080 \
  -Dperf.users=50 \
  -Dperf.duration=300
```

Or directly with the JMeter CLI:

```sh
jmeter -n -t performance/jmeter/ems-load-test.jmx \
       -Jhost=ems-perf.internal -Jport=8080 \
       -Jusers=50 -JrampUp=30 -Jduration=300 \
       -l build/perf/results.jtl -e -o build/perf/html
```

## Run in BlazeMeter cloud

```sh
pip install bzt
export BLAZEMETER_API_KEY=...
export BLAZEMETER_API_SECRET=...
bzt performance/blazemeter.yml -cloud
```

## Pass/Fail SLOs

Configured in `blazemeter.yml`:

- avg response time < 500ms
- p95 response time < 1500ms
- error rate < 1%

Breaching any SLO fails the run (and the CI stage).

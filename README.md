# qsim-service

A small, stateless HTTP/JSON service that runs one discrete-event simulation of a
queueing network using the headless [JMT](https://jmt.sourceforge.net/) engine and
returns per-station / per-class performance measures with confidence intervals.

**License: GPL v2 or later.** This service links JMT (GPL) in-process, so it is a GPL
derivative. It is consumed over HTTP as a plain JSON API — callers (e.g. an Apache-2.0
optimizer) never touch JMT code. See `NOTICE`.

## Build

```bash
mvn package            # runs the full test suite (requires the JMT engine, headless)
```

The unmodified `JMT-singlejar-1.4.0.jar` is bundled in `lib/` and referenced as a
system-scoped Maven dependency.

## Run

```bash
docker build -t qsim-service:0.1.0 .
docker run --rm -p 8080:8080 qsim-service:0.1.0
```

Configuration via env vars: `QSIM_PORT` (default 8080), `QSIM_DEFAULT_ALPHA`,
`QSIM_DEFAULT_PRECISION`, `QSIM_DEFAULT_MIN_SAMPLES`, `QSIM_DEFAULT_MAX_SAMPLES`,
`QSIM_DEFAULT_MAX_WALLCLOCK_SECONDS`, `QSIM_TEMP_DIR`.

## API

### `GET /health` → `{"status":"ok"}`

### `POST /simulate`

Request and response contract: see the design spec at
`docs/superpowers/specs/2026-07-25-qsim-service-design.md` §5. Minimal M/M/1 example:

```bash
curl -X POST localhost:8080/simulate -H 'Content-Type: application/json' -d '{
  "model": {
    "name": "mm1",
    "classes": [{"name":"web","type":"open"}],
    "nodes": [
      {"name":"src","type":"source","arrivals":{"web":{"distribution":{"type":"exponential","rate":1.0}}}},
      {"name":"q","type":"queue","servers":1,"scheduling":"fcfs","service":{"web":{"distribution":{"type":"exponential","rate":2.0}}}},
      {"name":"snk","type":"sink"}
    ],
    "routing": {"web":[{"from":"src","to":"q"},{"from":"q","to":"snk"}]}
  },
  "seed": 12345,
  "measures": ["utilization","response-time","throughput"]
}'
```

Each response measure carries `mean`, CI (`lower`/`upper`), `alpha`, `precision`,
`success`, `samplesAnalyzed`, `samplesDiscarded`, `variance`, `stdDev`. `completed:false`
means a cap fired before all CIs converged — the caller decides whether to trust or re-run.

**Fork-join measures:** on a `fork-join` node, `response-time` is the whole fork-to-join sojourn —
the time from the job splitting to all required branches having rejoined — not any one branch's or
the join's own residence time. Per-branch numbers are not reported separately; a fork-join's
measures come back under its own node name.

> **Caveat:** `response-time` is currently the *only* measure with fork-join-region semantics.
> `queue-length`, `residence-time`, `queue-time`, `utilization`, `throughput` and `drop-rate` on a
> fork-join node are measured at its internal join station, so e.g. `queue-length` is the join's
> synchronization backlog rather than the fork-join's in-flight population. Do not read those as
> region figures — see [#8](https://github.com/atantawi/qsim-service/issues/8).

**Distributions:** named (`{"type":"exponential","rate":r}`, `{"type":"deterministic","value":v}`)
or moment form (`{"mean":m,"scv":c}` → Exponential/Deterministic/Gamma). v1 implements these
three forms; the remaining JMT named distributions are a mechanical registry extension.

**Replication:** one request = one seed = one run. Run many seeds (e.g. many containers) and
aggregate per the independent-replications method (design spec §9).

## Examples

Worked, runnable examples live in [`examples/`](examples/). The
[machine-repairmen](examples/machine-repairmen/) example drives a closed network — an
infinite-server delay station feeding a single-server FCFS repair queue — three
equivalent ways: [bash/curl](examples/machine-repairmen/bash/),
[python](examples/machine-repairmen/python/) (with a plot), and
[go](examples/machine-repairmen/go/). It also demonstrates the independent-replications
workflow above (each point averaged over independent seeds). See
[`expected-output.md`](examples/machine-repairmen/expected-output.md) for a sample run.

## Error responses

| Status | Meaning |
|--------|---------|
| 400 | Malformed JSON, or unsupported measure type |
| 422 | Semantic model error (dangling routing, open class without a source, probabilities not summing to 1) or JSIMG schema failure |
| 500 | Simulation engine failure |
| 200 + `completed:false` | Watchdog fired before convergence — partial measures |

# Machine-Repairmen Examples — Design

**Date:** 2026-07-27
**Status:** Approved (revised after empirical validation)
**Repo:** `qsim-service` (branch `feat/machine-repairmen-examples`)

## 1. Purpose

Demonstrate the `qsim-service` HTTP/JSON API end-to-end with a single, well-known
queueing model — the **machine-repairmen problem** (a.k.a. the machine-interference
or Palm model) — driven three ways:

1. **bash / curl** — "it's just HTTP": loop the sweep, print a table.
2. **python** — scripting + presentation: same sweep, table **and** a two-panel plot.
3. **go** — a structured, typed client: same sweep, formatted tabular output.

The examples exercise a corner of the service the existing M/M/1-style fixtures do
not: a **closed chain** with an **infinite-server (delay)** station, a **single-server
FCFS** station with **general (non-exponential)** service via the moment form, and the
**independent-replications** aggregation the service README prescribes.

Non-goals: managing the service lifecycle (examples assume it is already running),
wiring examples into `mvn test`.

## 2. The model

Closed queueing network, one closed class, population **N machines** (swept — see §3).

| Station | Node type | Role | Service |
|---------|-----------|------|---------|
| `running` | `delay` (infinite server) | machines up and running | time-between-failures, mean **MTBF = 1000**, **exponential (`scv: 1`)** |
| `repair` | `queue`, `scheduling: "fcfs"`, `servers: 1` | the single repairman fixing failed machines | repair time, mean **= 1**, **CoV swept** (`scv` swept — the curve family) |

Routing is a closed cycle (no source/sink): `running → repair → running`.
The closed class's `referenceStation` is the delay node (`running`); the service
pre-loads the full population there at t=0.

**Modeling note (goes in the example README) — why exponential failures:** the
metric of interest (repair-time variability) is only legible when the *failure*
process is not itself the dominant source of variability. Queue waiting scales with
`(Ca² + Cs²)/2`, where `Ca²` is the arrival-stream (failure-process) squared CoV and
`Cs²` is the repair-time squared CoV. With bursty failures (e.g. CoV = 5 → `Ca² = 25`)
the repair variance `Cs² ∈ {0.25, 1, 4, 16}` is a minority term for every repair CoV
except the largest — so the repair-CoV lesson is invisible. With **exponential**
failures (`Ca² ≈ 1`) the repair variance becomes the dominant term and the effect
separates cleanly. Exponential time-between-failures is also the canonical
machine-repairman / Palm model, so this is the textbook-standard choice.

**Why simulate:** a single-server (and generally multiserver) FCFS station with
general, non-exponential service is **not product-form**. For repair CoV ≠ 1 there is
no exact closed-form MVA — this is the concrete reason to reach for a simulator.

## 3. Parameter sweep

- **Repair CoV** ∈ {0.5, 1, 2, 4}  →  repair `scv` ∈ {0.25, 1, 4, 16} — the **curve family**.
- **Machine population N** ∈ {700, 800, 900, 950, 1000, 1050, 1100} — the **x-axis (load)**.

Offered repair load ρ ≈ N × (repair_mean / MTBF_mean) = N × (1/1000) ≈ **N/1000**, so
the sweep runs from a lightly-loaded regime (N=700, ρ≈0.7) through the knee (N≈1000,
ρ≈1.0) into overload (N=1100, ρ≈1.1). Repairmen are **fixed at R = 1** — load, not
provisioning, is the independent variable.

**Replication (independent seeds):** each (N, CoV) point is the **mean over K = 10
independent seeds** (seeds 1…10). This is the aggregation the service README
prescribes, and the reason it is required here: tightening the per-run CI `precision`
does **not** reduce the estimate's Monte-Carlo error (a fixed seed reproduces the same
trajectory; precision only changes when the run stops), so near the knee the only way
to get clean, monotonic curves is to average independent replications. The examples
therefore also demonstrate the replication workflow.

7 load points × 4 CoV × 10 seeds = **280 `POST /simulate` calls** per driver.
Runtime is dominated by the near-knee cases; expect roughly 8–15 minutes for a full
run. `N`, the CoV list, and `K` are defined once at the top of each driver so a reader
can shrink the sweep for a quick look.

## 4. Measures & the two reported metrics

Request `measures: ["queue-length"]` (both metrics derive from queue-length; the
misleading multiserver "utilization" measure is **not** requested — for a multiserver
station JMT reports fraction-of-time-nonempty, not mean busy servers).

Per (N, CoV) point, averaged over the K seeds:

- **Availability (%)** = `100 × mean(queue-length @ "running") / N` — the time-average
  fraction of machines that are up. **Top panel / primary column.**
- **Mean machines-down** = `mean(queue-length @ "repair")` — the average number of
  machines out of service (waiting + in repair). **Bottom panel / secondary column.**

Each per-run JMT value also carries a confidence interval (`lower`/`upper`,
`success`); the drivers key off the `mean`. Availability changes only a few percentage
points across CoV, but machines-down fans out ~3–5× — so the two panels together make
the variability lesson land where availability alone would look nearly flat.

## 5. Canonical request template

`machine-repairmen/request-template.json` — one body with `{{placeholders}}` the
language drivers substitute per call: `{{population}}`, `{{repair_scv}}`, `{{seed}}`.
Repairmen are fixed at 1 and the MTBF distribution is fixed (exponential), so those
are literals, not placeholders.

```json
{
  "model": {
    "name": "machine-repairmen",
    "classes": [
      { "name": "machines", "type": "closed", "population": {{population}}, "referenceStation": "running" }
    ],
    "nodes": [
      { "name": "running", "type": "delay",
        "service": { "machines": { "distribution": { "mean": 1000, "scv": 1 } } } },
      { "name": "repair", "type": "queue", "servers": 1, "scheduling": "fcfs", "capacity": null,
        "service": { "machines": { "distribution": { "mean": 1, "scv": {{repair_scv}} } } } }
    ],
    "routing": {
      "machines": [
        { "from": "running", "to": "repair" },
        { "from": "repair",  "to": "running" }
      ]
    }
  },
  "seed": {{seed}},
  "stopping": { "alpha": 0.05, "precision": 0.05, "minSamples": 20000, "maxSamples": 1000000,
                "maxWallClockSeconds": 60 },
  "measures": ["queue-length"]
}
```

## 6. Directory layout

```
examples/
  README.md                      # index: what the examples show + how to start the service
  machine-repairmen/
    README.md                    # model, the two metrics, sweep, replication, "why simulate" story
    request-template.json        # the body in §5, with {{placeholders}}
    expected-output.md           # captured real tables/plot so readers see the payoff first
    bash/run.sh                  # sweep N × CoV × seeds → curl → jq → averaged table
    python/
      requirements.txt           # requests, matplotlib
      repairmen.py               # build requests, POST, average seeds, print table + save 2-panel plot
    go/
      go.mod
      main.go                    # typed request/response structs, averaged table
```

**Shared components (deferred / YAGNI):** with one example today, each language's code
stays self-contained under `machine-repairmen/<lang>/`. `examples/README.md` documents
the promotion path: when a second example lands, common per-language request/response
types move up to `examples/<lang>/common/`. We do **not** build that layer now.

## 7. The three drivers

All three define `N` list, CoV list, and seed count `K` at the top; read `QSIM_URL`
(default `http://localhost:8080`); make one `POST /simulate` per (N, CoV, seed);
average the K seeds per point; and fail loudly on non-200 or `completed:false`.

- **bash** (`bash/run.sh`): substitutes into `request-template.json`, `curl`s, parses
  with `jq`, prints an aligned table with columns **N, ρ, CoV, Avail(%), Down**. Deps:
  `curl`, `jq`.
- **python** (`python/repairmen.py`): `requests` + `matplotlib`. Prints the same table
  and saves a **two-panel plot** — top: availability (%) vs N; bottom: mean
  machines-down vs N; one line per CoV in each. Deps pinned in `requirements.txt`.
- **go** (`go/main.go`): `net/http` + `encoding/json`, typed request/response structs,
  prints the same formatted table (no plot).

Same sweep, same two metric definitions, same env-var convention — so their tables
agree and a reader can diff them.

## 8. Service assumption & testing

- Examples assume a running service; `examples/README.md` shows the two-line
  `docker run` (or jar) to start it. Examples do not start/stop it.
- **Not** wired into `mvn test` — these are demos, not CI.
- During implementation each driver is smoke-run against the **live service**, and the
  real tables/plot are captured into `expected-output.md`. Committed numbers are
  genuine, not invented. If the service isn't reachable at build time, that is called
  out rather than faking output.

## 9. Success criteria

- All three drivers run against a live service and produce the same averaged table
  (N × CoV) for both metrics.
- Within each panel the curves are **monotonic in repair CoV** across the sweep:
  higher CoV → lower availability and more machines-down. The separation widens toward
  the knee (N≈1000–1100).
- The python two-panel plot shows availability declining and machines-down fanning out
  with load, cleanly ordered by CoV.
- READMEs let a newcomer start the service and run any one example without prior
  knowledge of JMT or the internal contract, and explain why exponential failures and
  seed-averaging are used.

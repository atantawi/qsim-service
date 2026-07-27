# Machine-Repairmen Examples Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an `examples/machine-repairmen/` directory demonstrating `qsim-service` three ways — bash/curl, python (with a two-panel plot), and a typed go client — all sweeping machine population against a fixed single repairman and reporting availability (%) and mean machines-down for a family of repair-time CoV values, averaged over independent seeds.

**Architecture:** One shared `request-template.json` describes a closed-chain machine-repairmen model with `{{placeholders}}` for `population`, `repair_scv`, and `seed`. Time-between-failures is exponential and repairmen are fixed at 1 (both baked into the template). Three self-contained language drivers substitute the placeholders, `POST /simulate` once per (population, CoV, seed), average the seeds per point, and report availability = `100 × queue-length@running / N` and machines-down = `queue-length@repair`. Examples assume a running service and are not wired into `mvn test`.

**Tech Stack:** bash + curl + jq; python3 + requests + matplotlib; go (stdlib only). The service under test is the existing Java/JMT `qsim-service` (`qsim.http.App`, default port 8080).

## Global Constraints

- **License header:** every new script/source file starts with a short GPL header comment (repo is GPL v2-or-later). Use the language's comment syntax:
  `qsim-service example — Copyright (C) 2026 qsim-service contributors. GPL v2 or later; see LICENSE.`
- **Service endpoint:** every driver reads `QSIM_URL` from the environment, defaulting to `http://localhost:8080`. Never hard-code a different URL.
- **Model constants (identical across all three drivers):**
  - Machine population `N` swept over `{700, 800, 900, 950, 1000, 1050, 1100}` (the x-axis).
  - Repair CoV family `{0.5, 1, 2, 4}` (→ `repair_scv = CoV²` = `{0.25, 1, 4, 16}`) — the curve family.
  - Repairmen fixed at **1** (template literal, not swept).
  - Time-between-failures **exponential** (`scv = 1`), mean `1000` (template literal).
  - Replication: each (N, CoV) point is the mean over **seeds 1…K with K = 10**.
- **Two reported metrics (identical across all three):**
  - `availability_pct = 100 × mean(queue-length @ station "running") / N`.
  - `machines_down = mean(queue-length @ station "repair")`.
  - The misleading multiserver "utilization" measure is **not** requested or reported.
- **Table columns (identical across all three):** `N | rho | CoV | Avail(%) | Down`, where `rho = N/1000`. 7 × 4 = **28 data rows**.
- **Template location:** `examples/machine-repairmen/request-template.json`; each driver lives in `examples/machine-repairmen/<lang>/` and reads the template at `../request-template.json` (run from inside the lang dir).
- **Fail loud:** a non-200 response, a `completed:false` body, or a missing `running`/`repair` `queue-length` measure must abort with a clear message — never silently print `0.00`.

## Starting the service (prerequisite for every task's test step)

Two options; either works. Docker matches the README and is the portable path:

```bash
# Option A — Docker (portable)
docker build -t qsim-service:dev .
docker run --rm -d -p 8080:8080 --name qsim qsim-service:dev
# ... run examples ...
docker stop qsim

# Option B — local jar (faster iteration)
mvn -q -DskipTests package
java -Djava.awt.headless=true \
  -cp "target/qsim-service.jar:target/dependency/*:lib/JMT-singlejar-1.4.0.jar" \
  qsim.http.App &
```

Verify readiness before running any driver: `curl -sf localhost:8080/health` → `{"status":"ok"}`.

## A note on verification runtime

A full sweep is 7 × 4 × 10 = **280 POST calls** per driver (~8–15 min, dominated by near-knee cases). For the per-driver verify steps (Tasks 2–4) a **reduced smoke** is enough to prove mechanics and cross-driver agreement: temporarily set the seed count to `2` and the population list to `(900 1000)`, run, confirm structure and that the numbers match the other drivers, then **restore the full constants before committing**. The single full-sweep capture happens once, in Task 5.

---

### Task 1: Scaffold, shared request template, and model validation

Creates the directory tree, the shared request template, and the two READMEs, then **validates the model against a live service** — de-risking the contract before any driver is built.

**Files:**
- Create: `examples/README.md`
- Create: `examples/machine-repairmen/README.md`
- Create: `examples/machine-repairmen/request-template.json`

**Interfaces:**
- Produces: `request-template.json` with exactly three placeholder tokens — `{{population}}` (integer), `{{repair_scv}}` (number), `{{seed}}` (integer) — substituted verbatim by every driver. Repairmen (`1`) and the MTBF distribution (`scv:1`, mean `1000`) are literals. The response contract every driver consumes: top-level `.completed` (bool) and `.measures[]` where each element has `.station`, `.class`, `.type`, `.mean`, `.lower`, `.upper`, `.success`. Availability reads `select(.station=="running" and .type=="queue-length").mean`; machines-down reads `select(.station=="repair" and .type=="queue-length").mean`.

- [ ] **Step 1: Create the request template**

Create `examples/machine-repairmen/request-template.json`:

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

- [ ] **Step 2: Validate the filled template against a live service (the failing "test")**

Start the service (see "Starting the service" above). Then substitute one heaviest case (N=1050, CoV=4 → scv=16, seed=1) and POST it:

```bash
cd examples/machine-repairmen
sed -e 's/{{population}}/1050/g' -e 's/{{repair_scv}}/16/g' -e 's/{{seed}}/1/g' \
  request-template.json > /tmp/mr-case.json
curl -sS -X POST "${QSIM_URL:-http://localhost:8080}/simulate" -H 'Content-Type: application/json' \
  -d @/tmp/mr-case.json | tee /tmp/mr-resp.json | jq '.completed, (.measures[] | {station, type, mean})'
```

Expected FIRST run (before template is correct): if any field name is wrong you get HTTP 400/422 with a message, or a missing measure. Fix the template until you get a 200 with both a `running` and a `repair` `queue-length` measure present and `.completed == true`.

- [ ] **Step 3: Confirm both metrics are well-formed**

```bash
jq -r '.measures[] | select(.type=="queue-length") | "\(.station) \(.mean)"' /tmp/mr-resp.json
```

Expected PASS: two lines — `running <num>` with the number in `(0, 1050)`, and `repair <num>` with a positive number. Compute `100 × running/1050` (availability, expect low-ish ~90% for this heaviest single-seed case) and note `repair` (machines-down, expect tens). Confirm `.completed == true`; if runs routinely hit the 60s cap without converging, raise `maxWallClockSeconds` in the template and re-validate. Record the observed numbers — later drivers will reproduce the seed-1 value.

- [ ] **Step 4: Write `examples/machine-repairmen/README.md`**

Must contain, in prose an outsider can follow:
- The model: closed chain, `N` machines (swept); `running` = infinite-server delay station, time-between-failures **exponential**, mean 1000; `repair` = single-server (`R=1`) FCFS queue, repair time mean 1, repair CoV swept.
- The two metrics: availability (%) = fraction of machines up = `100 × queue-length@running / N`; mean machines-down = `queue-length@repair`.
- The sweep: population `N ∈ {700…1100}` on the x-axis (offered load ρ ≈ N/1000, knee near N≈1000); repair CoV family `{0.5,1,2,4}`; a single repairman throughout.
- **Why exponential failures:** queue waiting scales with `(Ca² + Cs²)/2`; with bursty failures (high `Ca²`) the repair-time variance `Cs²` is swamped and the CoV lesson disappears, so time-between-failures is exponential (`Ca²≈1`) — also the canonical Palm / machine-interference model — which lets the repair-CoV effect separate cleanly.
- **Why seed-averaging:** tightening the per-run CI `precision` does not reduce the estimate's Monte-Carlo error (a fixed seed reproduces the same trajectory); near the knee the curves are only clean when averaged over independent seeds, so each point is the mean of K=10 seeds — the independent-replications workflow the service prescribes.
- **Why simulate:** a single-server FCFS station with general (non-exponential) service is not product-form, so there is no exact closed form for CoV≠1 — which is exactly why a simulator earns its keep here.
- How to run each of the three drivers (point at each subdir), and that the service must already be running (link the top-level `examples/README.md`).

- [ ] **Step 5: Write `examples/README.md`**

Index page: one-paragraph purpose; the two ways to start the service (docker / local jar, copied from this plan); the `QSIM_URL` convention; a link to `machine-repairmen/`; and the shared-components note: "same-language helpers are kept inside each example for now; when a second example is added, common request/response types move up to `examples/<lang>/common/`."

- [ ] **Step 6: Commit**

```bash
git add examples/README.md examples/machine-repairmen/README.md examples/machine-repairmen/request-template.json
git commit -m "examples: machine-repairmen scaffold, request template, READMEs"
```

---

### Task 2: bash / curl driver

**Files:**
- Create (overwrite if present): `examples/machine-repairmen/bash/run.sh`

**Interfaces:**
- Consumes: `../request-template.json` (Task 1) and the response contract in Task 1's Interfaces block.
- Produces: an aligned table on stdout with columns `N | rho | CoV | Avail(%) | Down`, 28 data rows, each averaged over seeds 1…10.

- [ ] **Step 1: Write `run.sh`**

```bash
#!/usr/bin/env bash
# qsim-service example — Copyright (C) 2026 qsim-service contributors.
# GPL v2 or later; see LICENSE.
set -euo pipefail

QSIM_URL="${QSIM_URL:-http://localhost:8080}"
TEMPLATE="$(cd "$(dirname "$0")/.." && pwd)/request-template.json"
POPS=(700 800 900 950 1000 1050 1100)
COVS=(0.5 1 2 4)
SEEDS=10   # average over independent seeds 1..SEEDS

printf '%-6s %-6s %-6s %-10s %-10s\n' "N" "rho" "CoV" "Avail(%)" "Down"
printf '%-6s %-6s %-6s %-10s %-10s\n' "-----" "-----" "-----" "--------" "--------"

for n in "${POPS[@]}"; do
  rho=$(awk -v n="$n" 'BEGIN{printf "%.2f", n/1000}')
  for cov in "${COVS[@]}"; do
    scv=$(awk -v c="$cov" 'BEGIN{printf "%g", c*c}')
    sum_avail=0; sum_down=0
    for ((seed=1; seed<=SEEDS; seed++)); do
      body=$(sed -e "s/{{population}}/$n/g" -e "s/{{repair_scv}}/$scv/g" -e "s/{{seed}}/$seed/g" "$TEMPLATE")
      resp=$(curl -sS -X POST "$QSIM_URL/simulate" -H 'Content-Type: application/json' -d "$body")
      completed=$(echo "$resp" | jq -r '.completed')
      running=$(echo "$resp" | jq -r '.measures[]? | select(.station=="running" and .type=="queue-length") | .mean')
      down=$(echo "$resp" | jq -r '.measures[]? | select(.station=="repair" and .type=="queue-length") | .mean')
      if [[ "$completed" != "true" || -z "$running" || "$running" == "null" || -z "$down" || "$down" == "null" ]]; then
        echo "ERROR for N=$n CoV=$cov seed=$seed (completed=$completed): $resp" >&2
        exit 1
      fi
      sum_avail=$(awk -v s="$sum_avail" -v r="$running" -v n="$n" 'BEGIN{printf "%.6f", s + 100*r/n}')
      sum_down=$(awk -v s="$sum_down" -v d="$down" 'BEGIN{printf "%.6f", s + d}')
    done
    avail=$(awk -v s="$sum_avail" -v k="$SEEDS" 'BEGIN{printf "%.2f", s/k}')
    downavg=$(awk -v s="$sum_down" -v k="$SEEDS" 'BEGIN{printf "%.2f", s/k}')
    printf '%-6s %-6s %-6s %-10s %-10s\n' "$n" "$rho" "$cov" "$avail" "$downavg"
  done
done
```

- [ ] **Step 2: Make executable and smoke-run against the live service (verify)**

```bash
chmod +x examples/machine-repairmen/bash/run.sh
cd examples/machine-repairmen/bash
# Reduced smoke: temporarily edit SEEDS=2 and POPS=(900 1000) in run.sh, then:
./run.sh
```

Expected: a header plus data rows (for the reduced smoke: 2 pops × 4 CoV = 8 rows), no error exit, all `Avail(%)` in `(0, 100]`, all `Down` > 0. Structural check on the full script (with full constants restored):

```bash
./run.sh | tail -n +3 | wc -l          # → 28 (full sweep)
./run.sh | tail -n +3 | awk '{ if ($4+0 <= 0 || $4+0 > 100) { print "BAD:", $0; bad=1 } } END{ exit bad }'
```

Expected PASS: 28 rows (full), no `BAD:` lines. **Restore SEEDS=10 and the full POPS before committing.**

- [ ] **Step 3: Sanity-check the trend**

Within each fixed `N`, availability must be **non-increasing** as CoV rises and `Down` **non-decreasing** (higher repair variability → more waiting). The separation should widen toward the knee (N=1000–1100). Minor single-row inversions far from the knee are acceptable sampling noise; a systematic reversal is a defect.

- [ ] **Step 4: Commit**

```bash
git add examples/machine-repairmen/bash/run.sh
git commit -m "examples: bash/curl driver for machine-repairmen sweep"
```

---

### Task 3: python driver with two-panel plot

**Files:**
- Create: `examples/machine-repairmen/python/repairmen.py`
- Create: `examples/machine-repairmen/python/requirements.txt`

**Interfaces:**
- Consumes: `../request-template.json` (Task 1) and the Task 1 response contract.
- Produces: the same 28-row table on stdout, plus `examples/machine-repairmen/python/availability.png` — a two-panel figure (top: availability % vs N; bottom: mean machines-down vs N; one line per CoV in each).

- [ ] **Step 1: Write `requirements.txt`**

```
requests>=2.28
matplotlib>=3.6
```

- [ ] **Step 2: Write `repairmen.py`**

```python
#!/usr/bin/env python3
# qsim-service example — Copyright (C) 2026 qsim-service contributors.
# GPL v2 or later; see LICENSE.
import json
import os
from pathlib import Path

import requests
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

QSIM_URL = os.environ.get("QSIM_URL", "http://localhost:8080")
POPS = [700, 800, 900, 950, 1000, 1050, 1100]
COVS = [0.5, 1, 2, 4]
SEEDS = 10  # average over independent seeds 1..SEEDS
TEMPLATE = Path(__file__).resolve().parent.parent / "request-template.json"


def build_body(population, scv, seed):
    text = TEMPLATE.read_text()
    text = (text.replace("{{population}}", str(population))
                .replace("{{repair_scv}}", repr(scv))
                .replace("{{seed}}", str(seed)))
    return json.loads(text)


def measure(data, station, mtype):
    for m in data["measures"]:
        if m["station"] == station and m["type"] == mtype:
            return m
    raise KeyError(f"missing measure {station}/{mtype} in response")


def run_point(population, cov):
    scv = cov * cov
    tot_avail = 0.0
    tot_down = 0.0
    for seed in range(1, SEEDS + 1):
        resp = requests.post(f"{QSIM_URL}/simulate", json=build_body(population, scv, seed), timeout=300)
        resp.raise_for_status()
        data = resp.json()
        if not data.get("completed", True):
            raise RuntimeError(f"run did not converge: N={population} CoV={cov} seed={seed}")
        tot_avail += 100.0 * measure(data, "running", "queue-length")["mean"] / population
        tot_down += measure(data, "repair", "queue-length")["mean"]
    return tot_avail / SEEDS, tot_down / SEEDS


def main():
    avail = {cov: [] for cov in COVS}
    down = {cov: [] for cov in COVS}
    print(f"{'N':>6} {'rho':>6} {'CoV':>6} {'Avail(%)':>10} {'Down':>10}")
    for n in POPS:
        rho = n / 1000.0
        for cov in COVS:
            a, d = run_point(n, cov)
            avail[cov].append(a)
            down[cov].append(d)
            print(f"{n:>6} {rho:>6.2f} {cov:>6} {a:>10.2f} {d:>10.2f}")

    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(7, 8), sharex=True)
    for cov in COVS:
        ax1.plot(POPS, avail[cov], marker="o", label=f"CoV={cov}")
        ax2.plot(POPS, down[cov], marker="o", label=f"CoV={cov}")
    ax1.set_ylabel("Availability (%)")
    ax1.set_title("Machine availability vs population (R=1 repairman)")
    ax1.grid(True, alpha=0.3)
    ax1.legend(title="repair CoV")
    ax2.set_xlabel("Machine population N  (offered load ρ ≈ N/1000)")
    ax2.set_ylabel("Mean machines down")
    ax2.set_title("Machines out of service vs population")
    ax2.grid(True, alpha=0.3)
    ax2.legend(title="repair CoV")
    out = Path(__file__).resolve().parent / "availability.png"
    fig.savefig(out, dpi=120, bbox_inches="tight")
    print(f"\nplot saved to {out}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 3: Install deps and smoke-run against the live service (verify)**

```bash
cd examples/machine-repairmen/python
python3 -m pip install -r requirements.txt
# Reduced smoke: temporarily set SEEDS=2 and POPS=[900, 1000], then:
python3 repairmen.py
```

Expected PASS: data rows printed (8 for the reduced smoke), then `plot saved to .../availability.png`; the file exists (`test -f availability.png`). For any (N, CoV) point present in both, the availability and down numbers must match the bash driver's table within rounding (same seeds, same model → identical means). **Restore SEEDS=10 and the full POPS before committing.**

- [ ] **Step 4: Commit**

```bash
git add examples/machine-repairmen/python/repairmen.py examples/machine-repairmen/python/requirements.txt
git commit -m "examples: python driver + two-panel plot for machine-repairmen"
```

---

### Task 4: go driver

**Files:**
- Create: `examples/machine-repairmen/go/go.mod`
- Create: `examples/machine-repairmen/go/main.go`

**Interfaces:**
- Consumes: `../request-template.json` (Task 1) and the Task 1 response contract.
- Produces: the same 28-row table on stdout (no plot).

- [ ] **Step 1: Write `go.mod`**

```
module qsim-example/machine-repairmen

go 1.21
```

- [ ] **Step 2: Write `main.go`**

```go
// qsim-service example — Copyright (C) 2026 qsim-service contributors.
// GPL v2 or later; see LICENSE.
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

var (
	pops  = []int{700, 800, 900, 950, 1000, 1050, 1100}
	covs  = []float64{0.5, 1, 2, 4}
	seeds = 10 // average over independent seeds 1..seeds
)

type measure struct {
	Station string  `json:"station"`
	Class   string  `json:"class"`
	Type    string  `json:"type"`
	Mean    float64 `json:"mean"`
	Lower   float64 `json:"lower"`
	Upper   float64 `json:"upper"`
	Success bool    `json:"success"`
}

type simResponse struct {
	Completed bool      `json:"completed"`
	Measures  []measure `json:"measures"`
}

func findMeasure(r simResponse, station, mtype string) (measure, bool) {
	for _, m := range r.Measures {
		if m.Station == station && m.Type == mtype {
			return m, true
		}
	}
	return measure{}, false
}

func fatal(format string, args ...any) {
	fmt.Fprintf(os.Stderr, format+"\n", args...)
	os.Exit(1)
}

func main() {
	url := os.Getenv("QSIM_URL")
	if url == "" {
		url = "http://localhost:8080"
	}
	tmplBytes, err := os.ReadFile(filepath.Join("..", "request-template.json"))
	if err != nil {
		fatal("read template: %v", err)
	}
	tmpl := string(tmplBytes)
	client := &http.Client{Timeout: 300 * time.Second}

	fmt.Printf("%-6s %-6s %-6s %-10s %-10s\n", "N", "rho", "CoV", "Avail(%)", "Down")
	for _, n := range pops {
		rho := float64(n) / 1000.0
		for _, cov := range covs {
			scv := cov * cov
			var sumAvail, sumDown float64
			for seed := 1; seed <= seeds; seed++ {
				body := strings.NewReplacer(
					"{{population}}", fmt.Sprintf("%d", n),
					"{{repair_scv}}", fmt.Sprintf("%g", scv),
					"{{seed}}", fmt.Sprintf("%d", seed),
				).Replace(tmpl)

				resp, err := client.Post(url+"/simulate", "application/json", bytes.NewBufferString(body))
				if err != nil {
					fatal("POST N=%d cov=%g seed=%d: %v", n, cov, seed, err)
				}
				var sr simResponse
				err = json.NewDecoder(resp.Body).Decode(&sr)
				resp.Body.Close()
				if err != nil {
					fatal("decode N=%d cov=%g seed=%d: %v", n, cov, seed, err)
				}
				if !sr.Completed {
					fatal("run did not converge: N=%d cov=%g seed=%d", n, cov, seed)
				}
				running, ok := findMeasure(sr, "running", "queue-length")
				if !ok {
					fatal("missing running/queue-length for N=%d cov=%g seed=%d", n, cov, seed)
				}
				down, ok := findMeasure(sr, "repair", "queue-length")
				if !ok {
					fatal("missing repair/queue-length for N=%d cov=%g seed=%d", n, cov, seed)
				}
				sumAvail += 100.0 * running.Mean / float64(n)
				sumDown += down.Mean
			}
			avail := sumAvail / float64(seeds)
			downAvg := sumDown / float64(seeds)
			fmt.Printf("%-6d %-6.2f %-6g %-10.2f %-10.2f\n", n, rho, cov, avail, downAvg)
		}
	}
}
```

- [ ] **Step 3: Smoke-run against the live service (verify)**

```bash
cd examples/machine-repairmen/go
# Reduced smoke: temporarily set pops=[]int{900, 1000} and seeds=2, then:
go run .
go vet ./...
```

Expected PASS: data rows (8 for the reduced smoke); availability and down values match the bash and python tables for shared points (same seeds/model). `go vet` clean. **Restore seeds=10 and the full pops before committing.**

- [ ] **Step 4: Commit**

```bash
git add examples/machine-repairmen/go/go.mod examples/machine-repairmen/go/main.go
git commit -m "examples: go driver for machine-repairmen sweep"
```

---

### Task 5: Capture real output and finalize

**Files:**
- Create: `examples/machine-repairmen/expected-output.md`
- Modify: `examples/machine-repairmen/README.md` (link the captured output)

**Interfaces:**
- Consumes: all three drivers (Tasks 2–4) run against a live service, with **full constants** (SEEDS=10, all 7 populations).

- [ ] **Step 1: Capture the real bash table and the plot (full sweep)**

With the service running and full constants restored in every driver:

```bash
cd examples/machine-repairmen/bash && ./run.sh | tee /tmp/mr-bash.txt
cd ../python && python3 repairmen.py | tee /tmp/mr-python.txt   # regenerates availability.png
```

Confirm the bash and python tables agree (same numbers). This is the ~8–15 min full run.

- [ ] **Step 2: Write `expected-output.md`**

Paste the actual captured `run.sh` table (from `/tmp/mr-bash.txt` — do not hand-edit the numbers), embed the plot (`![availability and machines-down](python/availability.png)`), and add 2–3 sentences reading the result: availability declines and machines-down fans out with load, cleanly ordered by repair CoV, with the separation widening through the knee (N≈1000–1100). If any case returned `completed:false` the driver would have aborted; note that all runs converged.

- [ ] **Step 3: Link it from the example README**

Add a line near the top of `examples/machine-repairmen/README.md`: `See [expected-output.md](expected-output.md) for a sample run (table + plot).`

- [ ] **Step 4: Commit the captured output (including the PNG)**

```bash
git add examples/machine-repairmen/expected-output.md examples/machine-repairmen/python/availability.png examples/machine-repairmen/README.md
git commit -m "examples: capture real machine-repairmen output (table + plot)"
```

- [ ] **Step 5: Open the PR**

```bash
git push -u origin feat/machine-repairmen-examples
gh pr create --title "Machine-repairmen examples (bash/python/go)" --body "<summary + how to run>"
```

---

## Self-Review

**Spec coverage (against `2026-07-27-machine-repairmen-examples-design.md`):**
- §1 purpose / three drivers → Tasks 2, 3, 4. ✓
- §2 model (closed, delay w/ exponential MTBF, single-server FCFS, moment form; why-exponential note) → Task 1 template + README Step 4. ✓
- §3 sweep (7 pops × 4 CoV, R=1, K=10 seeds, replication rationale) → Global Constraints + every driver loop + README. ✓
- §4 two metrics (availability + machines-down), utilization dropped → Global Constraints + every driver. ✓
- §5 request template with `{{population}}`/`{{repair_scv}}`/`{{seed}}` placeholders → Task 1 Step 1. ✓
- §6 directory layout → Tasks 1–4 file paths. ✓
- §7 three drivers' behavior (env var, fail-loud on completed:false, two-panel plot) → Tasks 2–4. ✓
- §8 service assumption, not in `mvn test`, capture real output → "Starting the service" section + Task 5. ✓
- §9 success criteria (tables agree, monotonic-in-CoV curves widening at knee, two-panel plot, newcomer-runnable) → Task 2 Step 3, Task 3/4 verify steps, Task 1 READMEs, Task 5. ✓

**Placeholder scan:** No TBD/TODO. All code blocks are complete and runnable. The `{{...}}` tokens in the template are intentional substitution points, defined in Task 1's Interfaces.

**Type/name consistency:** measure lookup uses `station`/`type`/`mean` and stations `"running"`/`"repair"`, measure type `"queue-length"`, identically in bash (jq), python (`measure()`), and go (`findMeasure`). Constants `POPS`/`pops` = `{700,800,900,950,1000,1050,1100}`, `COVS`/`covs` = `{0.5,1,2,4}`, `SEEDS`/`seeds` = 10 match across all three. Availability = `100 × running/N`, machines-down = `repair` queue-length, and `rho = N/1000` are identical across drivers. Template path `../request-template.json` (run from lang dir) is consistent.

# Machine-repairmen example

A classic closed queueing-network model, run through `qsim-service` across a
population sweep, computing two machine-fleet metrics: **availability** and
**mean machines-down**.

See [expected-output.md](expected-output.md) for a sample run (table + plot).

## The model

A population of `N` machines (swept — see below) cycles between two states:

- **`running`** — an infinite-server ("delay") station modeling machines that are up
  and working. Each machine's time-between-failures is **exponentially** distributed
  with mean 1000 (time units), i.e. `scv = 1`.
- **`repair`** — a single-server (`R = 1`) FCFS queueing station modeling the repair
  shop. One repairman works throughout; each broken machine's repair time has mean 1
  and the repair-time coefficient of variation (CoV) is swept (see below).

Machines flow `running → repair → running` in a closed loop — the total population
`N` never changes, only the split between "up" and "in repair" does.

## The two metrics

- **Availability** (%) — the fraction of the fleet that is up, on average:

  ```
  availability_pct = 100 × mean(queue-length @ "running") / N
  ```

  ("queue-length" at an infinite-server/delay station is just its mean occupancy —
  the expected number of machines currently in the `running` state.)

- **Mean machines-down** — the expected number of machines waiting for or undergoing
  repair, on average:

  ```
  machines_down = mean(queue-length @ "repair")
  ```

## The sweep

Each driver sweeps population `N` on the x-axis, `N ∈ {700 … 1100}` (offered load
`ρ ≈ N/1000`, with the interesting knee near `N ≈ 1000`), against a repair-CoV family
`{0.5, 1, 2, 4}` (`scv = CoV²` = `{0.25, 1, 4, 16}`). A single repairman (`R = 1`) is
used throughout — repairman count is not swept in this example.

## Why exponential failures

Queueing delay scales (approximately, via the diffusion/decomposition approximation)
with `(Ca² + Cs²)/2`, where `Ca²` is the squared CoV of the arrival process into
`repair` and `Cs²` is the squared CoV of the repair-time distribution. If
time-between-failures were bursty (high `Ca²`), that arrival-side variance would
swamp the repair-time variance `Cs²`, and the CoV lesson this example is built to show
would disappear into the noise. Keeping time-between-failures **exponential**
(`Ca² ≈ 1`) — which is also the canonical Palm / machine-interference model — lets the
repair-CoV effect on the two metrics separate out cleanly.

## Why seed-averaging

Tightening the per-run confidence-interval `precision` is not a substitute for averaging
over seeds. It does buy a better single-run estimate — a tighter target makes the engine
extend that seed's trajectory until it is met, and a longer run does reduce that run's
Monte-Carlo error — but it buys it the expensive way: cost grows roughly as `1/precision²`,
so each halving is ~4x the samples, and what you get is still one realization of one
trajectory, with a within-run batch-means interval around it. Near the knee (`N ≈ 1000`)
the sweep curves are only clean when averaged over independent seeds, which also yields an
across-replication error estimate that doesn't rely on the batching being well-behaved. So
each plotted point is the mean over `K = 10` independent seeds — the independent-replications
workflow the service is designed to support.

> **As built** (correction). This section previously claimed that tightening `precision` does
> **not** reduce the estimate's Monte-Carlo error, on the grounds that a fixed seed reproduces the
> same trajectory regardless. That was accidentally true when written: the CI stopping rule was
> disabled by [#12](https://github.com/atantawi/qsim-service/issues/12), so `precision` was ignored
> entirely and tightening it genuinely changed nothing. With that fixed, `precision` binds and does
> extend the run. The choice of seed-averaging here is unaffected — the reasoning above is.

## Why simulate this at all

A single-server FCFS station with **general** (non-exponential) service time is not
product-form — there is no exact closed-form solution once `CoV ≠ 1` (only the
`CoV = 1`, M/M/1-style case is exactly solvable). That gap between "easy to state" and
"no formula available" is exactly why a discrete-event simulator earns its keep here:
it's the only way to get a number (with a confidence interval) for `CoV ∈ {0.5, 2, 4}`.

## Running the examples

The service must already be running before any driver is invoked — see the top-level
[`examples/README.md`](../README.md) for how to start it and for the `QSIM_URL`
convention.

Three equivalent drivers, each self-contained in its own subdirectory:

- [`bash/`](bash/) — curl + jq, shell-script driver.
- [`python/`](python/) — `requests` + `matplotlib`, produces a plot.
- [`go/`](go/) — typed Go client, stdlib only.

Each driver substitutes `{{population}}`, `{{repair_scv}}`, and `{{seed}}` into
[`request-template.json`](request-template.json), `POST`s each case to
`$QSIM_URL/simulate`, and reports availability and mean machines-down per case,
averaged over 10 seeds.

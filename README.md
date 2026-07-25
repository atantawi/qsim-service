# qsim-service

A small, stateless HTTP/JSON service that runs a single discrete-event simulation of a
queueing network using the headless simulation engine of
[JMT (Java Modelling Tools)](https://jmt.sourceforge.net/) and returns per-station /
per-class performance measures with confidence intervals.

**Status:** design phase. See the authoritative design spec:
[`docs/superpowers/specs/2026-07-25-qsim-service-design.md`](docs/superpowers/specs/2026-07-25-qsim-service-design.md).

## Why

It exists to let optimizers and analyses run against a **close-to-system** (simulated)
queueing environment instead of only closed-form analytic approximations. Its first
consumer is `quantum-optimizer` (`qopt`), which uses it as a black-box optimization backend
through the JSON contract below; the analytic models are kept alongside as a
validity / robustness cross-check.

## Interface (summary)

A single synchronous, blocking endpoint (plus `GET /health`):

```
POST /simulate
```

- **Request:** a JMT-agnostic, domain-level queueing-network model — mixed open/closed
  classes; multiple sources/sinks; `queue` / `fork-join` / `delay` / `source` / `sink`
  nodes; probabilistic routing; named or moment-based (mean + SCV) distributions — plus a
  `seed` and a `stopping` object (per-measure CI target, sample/time/event caps, wall-clock
  watchdog).
- **Response:** per-(station × class) measures, each with `mean`, confidence interval,
  sample counts, and variance.

One request is **one simulation run (one seed)**. Running many replications and aggregating
them is the caller's responsibility (e.g. multiple containers with different seeds); the
returned per-run detail is sufficient for correct independent-replications aggregation.

See the design spec for the full request/response schemas, contract invariants, the
domain → JSIMG translation, and the moment → distribution mapping.

## Build & run

_To be defined by the implementation plan._ Target stack: Java 17, Maven, JDK
`com.sun.net.httpserver` + Jackson, packaged as a headless container
(`eclipse-temurin:17-jre`, `-Djava.awt.headless=true`), bundling `JMT-singlejar-1.4.0.jar`.

## License

**GNU General Public License v2 (or later)** — see [`LICENSE`](LICENSE).

This service links JMT engine classes in-process and therefore is a GPL derivative work.
JMT is © its authors and distributed under GPL v2-or-later. Consumers interact with this
service only over HTTP/JSON and are unaffected by its license.

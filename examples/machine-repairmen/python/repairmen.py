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
TEMPLATE_TEXT = TEMPLATE.read_text()  # static; read once, not per request


def build_body(population, scv, seed):
    # "%.17g" matches the bash/go drivers so all three send byte-identical scv.
    text = (TEMPLATE_TEXT.replace("{{population}}", str(population))
                         .replace("{{repair_scv}}", format(scv, ".17g"))
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
        if not data.get("completed", False):
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

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
    scv=$(awk -v c="$cov" 'BEGIN{printf "%.17g", c*c}')  # %.17g matches the go/python drivers
    sum_avail=0; sum_down=0
    for ((seed=1; seed<=SEEDS; seed++)); do
      body=$(sed -e "s/{{population}}/$n/g" -e "s/{{repair_scv}}/$scv/g" -e "s/{{seed}}/$seed/g" "$TEMPLATE")
      resp=$(curl -sS -w $'\n%{http_code}' -X POST "$QSIM_URL/simulate" -H 'Content-Type: application/json' -d "$body")
      http_code="${resp##*$'\n'}"   # last line is the status code
      resp="${resp%$'\n'*}"          # strip the trailing status line
      if [[ "$http_code" != "200" ]]; then
        echo "ERROR HTTP $http_code for N=$n CoV=$cov seed=$seed: $resp" >&2
        exit 1
      fi
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

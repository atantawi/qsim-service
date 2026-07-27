// qsim-service example — Copyright (C) 2026 qsim-service contributors.
// GPL v2 or later; see LICENSE.
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
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
	// Resolve the template relative to this source file (like the bash/python
	// drivers), so the program works regardless of the caller's directory.
	_, thisFile, _, ok := runtime.Caller(0)
	if !ok {
		fatal("cannot determine source file location")
	}
	tmplPath := filepath.Join(filepath.Dir(thisFile), "..", "request-template.json")
	tmplBytes, err := os.ReadFile(tmplPath)
	if err != nil {
		fatal("read template %s: %v", tmplPath, err)
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
					"{{repair_scv}}", fmt.Sprintf("%.17g", scv),
					"{{seed}}", fmt.Sprintf("%d", seed),
				).Replace(tmpl)

				resp, err := client.Post(url+"/simulate", "application/json", bytes.NewBufferString(body))
				if err != nil {
					fatal("POST N=%d cov=%g seed=%d: %v", n, cov, seed, err)
				}
				if resp.StatusCode != http.StatusOK {
					errBody, _ := io.ReadAll(resp.Body)
					resp.Body.Close()
					fatal("HTTP %d for N=%d cov=%g seed=%d: %s", resp.StatusCode, n, cov, seed, strings.TrimSpace(string(errBody)))
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

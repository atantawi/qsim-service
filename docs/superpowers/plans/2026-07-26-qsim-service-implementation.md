# qsim-service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a small, stateless HTTP/JSON service that runs one JMT discrete-event simulation of a queueing network and returns per-station/per-class performance measures with confidence intervals.

**Architecture:** A single long-lived, headless JVM per container, stateless, concurrency-1. Three layers: (1) an HTTP frontend on the JDK's `com.sun.net.httpserver` with `POST /simulate` + `GET /health`; (2) a contract layer that validates domain queueing-network JSON and applies defaults; (3) a translation+execution layer that converts the domain model to JMT JSIMG (`<sim>`) XML, runs `jmt.engine.simDispatcher.DispatcherJSIMschema` once, and parses JMT's `<solutions>` results XML back to domain JSON. JMT is fully quarantined in layer 3.

**Tech Stack:** Java 17, Maven, JUnit 5 (Jupiter), Jackson (databind), JDK `com.sun.net.httpserver`, bundled `JMT-singlejar-1.4.0.jar`. Packaged as a headless `eclipse-temurin:17-jre` container.

## Global Constraints

- **Language/runtime:** Java 17 LTS. Everything must run under `-Djava.awt.headless=true` with no display.
- **Host build environment (REQUIRED on this machine):** the default JDK is Java 26, but the target is Java 17. Temurin 17 is installed at `/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home` and Maven 3.9.16 is on `PATH`. **Every Maven command must be prefixed so it runs under Java 17**, e.g. `JAVA_HOME="$(/usr/libexec/java_home -v 17)" mvn -q test`. Running `mvn` without this prefix uses Java 26 and invalidates the JMT-on-Java-17 verification. (Inside the Docker build in Task 14 the base image is already Java 17, so no prefix is needed there.)
- **License:** GPL v2 (or later). This service links JMT engine classes in-process and is a GPL derivative. Every new source file carries a GPL v2+ header. The unmodified `JMT-singlejar-1.4.0.jar` is bundled; GPL notices/offer-of-source honored.
- **JMT is quarantined:** only the `qsim.engine` package may import `jmt.*` classes. No other package references JMT types. The domain JSON contract is the public interface.
- **JMT jar location:** `lib/JMT-singlejar-1.4.0.jar` (already allow-listed in `.gitignore` via `!lib/JMT-singlejar-*.jar`). Copy it from `/Users/tantawi/Projects/modeling/jmt-jars/JMT-singlejar-1.4.0.jar` during Task 1.
- **Engine input root element MUST be `<sim>`** — never the GUI `<archive>` wrapper (JMT's `SimLoader` rejects any other root).
- **One request = one simulation run (one seed).** No replication/aggregation in this service.
- **Trustworthy numbers or a clear failure — never a silent bad answer.** Every error path returns a structured error with the right HTTP status (see spec §7).
- **Root Java package:** `qsim`. Group id `dev.qsim`, artifact `qsim-service`, version `0.1.0`.
- **Reference material:** design spec `docs/superpowers/specs/2026-07-25-qsim-service-design.md` (authoritative). Verified JMT facts (JSIMG grammar, dispatcher API, distribution registry, measure strings) are summarized inline in each task; the JMT source checkout is at `/Users/tantawi/Projects/modeling/jmt-code-git` and example templates at `.../examples/jsim/qn_models/`.

---

## File Structure

All Java under `src/main/java/qsim/…`, tests under `src/test/java/qsim/…`, test resources under `src/test/resources/…`.

**`qsim.model`** — immutable domain DTOs, Jackson-bound (records + a sealed `Node` hierarchy):
- `SimulationRequest.java` — top-level request (`model`, `seed`, `stopping`, `measures`).
- `NetworkModel.java` — `name`, `classes`, `nodes`, `routing`.
- `JobClass.java` — `name`, `type` (`open`/`closed`), `population`, `referenceStation`.
- `Node.java` — sealed interface; `SourceNode`, `QueueNode`, `ForkJoinNode`, `DelayNode`, `SinkNode`.
- `Distribution.java` — raw JSON distribution (named form `type`+params, or moment form `mean`+`scv`).
- `RoutingEdge.java` — `from`, `to`, `probability`.
- `Stopping.java` — CI target + caps + wall-clock watchdog + `disableStatisticStop`.
- `SimulationResponse.java`, `MeasureResult.java` — response DTOs.

**`qsim.distribution`** — distribution resolution (JMT-agnostic):
- `CanonicalDistribution.java` / `DistParam.java` — resolved engine-ready distribution (class names + ordered params) with no JMT imports (plain strings).
- `DistributionResolver.java` — moment→canonical + named→canonical via a static registry.

**`qsim.contract`** — validation:
- `ContractValidator.java` — enforces spec §5.3 invariants, applies defaults.
- `ValidationException.java` — carries field-level messages + a `kind` (BAD_REQUEST vs UNPROCESSABLE).

**`qsim.translate`** — domain→JSIMG XML (no JMT imports; pure DOM/string):
- `JsimgWriter.java` — builds the `<sim>` XML document.
- `MeasureMapper.java` — domain measure type → JMT measure `type` string + which (station,class) rows to emit.

**`qsim.engine`** — the ONLY package importing `jmt.*`:
- `JmtRunner.java` — writes the model to a temp file, runs `DispatcherJSIMschema`, returns the results file + wall-clock + a `completed` flag.
- `EngineException.java` — wraps JMT load/runtime failures.

**`qsim.result`** — JMT `<solutions>` XML → domain measures (no JMT imports):
- `SolutionsParser.java` — parses `<measure>` rows into `MeasureResult`.

**`qsim.http`** — HTTP frontend:
- `App.java` — `main`, sets headless, reads `Config`, starts `HttpServer`, registers handlers.
- `SimulationHandler.java` — `POST /simulate`: deserialize → orchestrate → serialize; maps exceptions to status codes.
- `HealthHandler.java` — `GET /health`.
- `SimulationService.java` — orchestration: validate → resolve distributions → translate → run → parse → assemble response.
- `Config.java` — env-var config (port, default stopping params, temp dir).
- `ErrorResponse.java`, `Json.java` — error DTO + shared Jackson `ObjectMapper`.

**Resources:**
- `src/test/resources/xsd/SIMmodeldefinition.xsd` (+ any includes) — for XSD-validating emitted XML.
- `src/test/resources/jmt/*.jsimg` — copied golden templates for engine smoke/golden tests.
- `src/test/resources/fixtures/*.json` — domain-JSON fixtures (e.g. the qopt 3-station mixed network).

**Build/deploy:** `pom.xml`, `Dockerfile`, `.dockerignore`, `NOTICE` (GPL/JMT attribution), updated `README.md`.

---

### Task 1: Maven skeleton, JMT wiring, headless build

Establishes the buildable project, bundles the JMT jar, and proves JMT classes load under Java 17 in a headless test JVM.

**Files:**
- Create: `pom.xml`
- Create: `lib/JMT-singlejar-1.4.0.jar` (copied binary — see Step 1)
- Create: `src/main/java/qsim/package-info.java`
- Test: `src/test/java/qsim/HeadlessSmokeTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: a Maven build where `mvn -q test` compiles `qsim.*`, JMT is on the compile+test classpath as a system-scope dependency, and Surefire runs with `-Djava.awt.headless=true`.

- [ ] **Step 1: Bundle the JMT jar**

```bash
mkdir -p lib
cp /Users/tantawi/Projects/modeling/jmt-jars/JMT-singlejar-1.4.0.jar lib/JMT-singlejar-1.4.0.jar
ls -l lib/JMT-singlejar-1.4.0.jar   # expect a ~15MB file
```

- [ ] **Step 2: Write `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>dev.qsim</groupId>
  <artifactId>qsim-service</artifactId>
  <version>0.1.0</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.release>17</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <jackson.version>2.17.1</jackson.version>
    <junit.version>5.10.2</junit.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
      <version>${jackson.version}</version>
    </dependency>
    <dependency>
      <groupId>jmt</groupId>
      <artifactId>jmt-singlejar</artifactId>
      <version>1.4.0</version>
      <scope>system</scope>
      <systemPath>${project.basedir}/lib/JMT-singlejar-1.4.0.jar</systemPath>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>${junit.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <finalName>qsim-service</finalName>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.2.5</version>
        <configuration>
          <argLine>-Djava.awt.headless=true</argLine>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-dependency-plugin</artifactId>
        <version>3.6.1</version>
        <executions>
          <execution>
            <id>copy-runtime-deps</id>
            <phase>package</phase>
            <goals><goal>copy-dependencies</goal></goals>
            <configuration>
              <includeScope>runtime</includeScope>
              <outputDirectory>${project.build.directory}/dependency</outputDirectory>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

Note: `copy-dependencies` with `includeScope=runtime` copies Jackson (and its transitive jars) but NOT the system-scope JMT jar — that ships separately from `lib/` in the Dockerfile (Task 12).

- [ ] **Step 3: Add a package marker with the GPL header**

`src/main/java/qsim/package-info.java`:

```java
/*
 * qsim-service — a JMT-backed queueing-network simulation service.
 * Copyright (C) 2026 qsim-service contributors.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */
package qsim;
```

(Every subsequent `.java` file starts with this same header block above its `package` line.)

- [ ] **Step 4: Write the failing headless/JMT smoke test**

`src/test/java/qsim/HeadlessSmokeTest.java`:

```java
package qsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class HeadlessSmokeTest {

  @Test
  void runsHeadless() {
    assertEquals("true", System.getProperty("java.awt.headless"));
  }

  @Test
  void jmtDispatcherClassLoadsUnderJava17() throws Exception {
    Class<?> dispatcher = Class.forName("jmt.engine.simDispatcher.DispatcherJSIMschema");
    assertNotNull(dispatcher);
  }
}
```

- [ ] **Step 5: Run the test — expect it to pass once wiring is correct**

Run: `mvn -q test`
Expected: both tests PASS. If `jmtDispatcherClassLoadsUnderJava17` fails with `ClassNotFoundException`, the systemPath is wrong; if it fails with `UnsupportedClassVersionError`, JMT 1.4.0 is not Java-17 compatible — record this and fall back to Java 11 in `maven.compiler.release` and the Docker base image (design spec §10 sanctions this fallback).

- [ ] **Step 6: Commit**

```bash
git add pom.xml lib/JMT-singlejar-1.4.0.jar src/main/java/qsim/package-info.java src/test/java/qsim/HeadlessSmokeTest.java
git commit -m "build: Maven skeleton with bundled JMT jar and headless smoke test"
```

---

### Task 2: Run a bare `<sim>` model through the JMT engine end-to-end

Proves the headless engine actually solves a model and writes a results file — resolving design-spec open items §11.1 (JVM compat) and §11.2 (engine runs). Uses a real, known-good model extracted to the bare `<sim>` root the engine requires. This is a throwaway harness test that later tasks replace with the real `JmtRunner`; keep it as a regression anchor.

**Files:**
- Create: `src/test/resources/jmt/mm1.sim.xml` (extracted — see Step 1)
- Test: `src/test/java/qsim/engine/EngineEndToEndTest.java`

**Interfaces:**
- Consumes: the `DispatcherJSIMschema` API (verified): `new DispatcherJSIMschema(File)`, `setSimulationSeed(long)`, `setTerminalSimulation(boolean)`, `solveModel():boolean`, `getOutputFile():File`.
- Produces: confidence that a bare-`<sim>` file loads and solves headless and yields a `<solutions>` output file. No new production code.

- [ ] **Step 1: Extract a bare `<sim>` model from a verified template**

The example `.jsimg` files wrap `<sim>` in `<archive>`; the engine's `SimLoader` requires `<sim>` as the document root. Extract the `<sim>` subtree from the known-good fork example:

```bash
mkdir -p src/test/resources/jmt
xmllint --xpath '/*[local-name()="archive"]/*[local-name()="sim"]' \
  /Users/tantawi/Projects/modeling/jmt-code-git/examples/jsim/qn_models/open_1class_1stat_mm1fcfs.jsimg \
  > src/test/resources/jmt/mm1.sim.xml
head -3 src/test/resources/jmt/mm1.sim.xml   # first element must be <sim ...>
```

If `open_1class_1stat_mm1fcfs.jsimg` is absent, use `open_1class_3stat_fork.jsimg` instead (also verified). The point is a real model whose full `Server`/`Queue` section parameter blocks are guaranteed loadable by this JMT build.

- [ ] **Step 2: Write the failing end-to-end test**

`src/test/java/qsim/engine/EngineEndToEndTest.java`:

```java
package qsim.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import jmt.engine.simDispatcher.DispatcherJSIMschema;
import org.junit.jupiter.api.Test;

class EngineEndToEndTest {

  @Test
  void solvesBareSimModelAndWritesSolutions() throws Exception {
    Path model = Files.createTempFile("mm1", ".xml");
    Files.copy(getClass().getResourceAsStream("/jmt/mm1.sim.xml"), model,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);

    DispatcherJSIMschema dispatcher = new DispatcherJSIMschema(model.toFile());
    dispatcher.setSimulationSeed(12345L);
    dispatcher.setTerminalSimulation(true);

    boolean ok = dispatcher.solveModel();
    assertTrue(ok, "solveModel() should return true");

    File out = dispatcher.getOutputFile();
    assertTrue(out != null && out.exists() && out.length() > 0,
        "engine should write a non-empty solutions file");
    String xml = Files.readString(out.toPath());
    assertTrue(xml.contains("<solutions") || xml.contains("<measure"),
        "output should be JMT solutions XML");
  }
}
```

- [ ] **Step 3: Run it**

Run: `mvn -q test -Dtest=EngineEndToEndTest`
Expected: PASS. If `solveModel()` throws a `LoadException` about the root element, the extraction in Step 1 kept the `<archive>` wrapper — re-extract so the root is `<sim>`. If it hangs, the model lacks a statistic-stop cap; that is fine here because the extracted model carries `maxSamples`.

- [ ] **Step 4: Commit**

```bash
git add src/test/resources/jmt/mm1.sim.xml src/test/java/qsim/engine/EngineEndToEndTest.java
git commit -m "test: end-to-end JMT engine run over a bare <sim> model"
```

---

### Task 3: Domain model records + Jackson binding

Immutable Jackson-bound DTOs for the request/response contract (spec §5.1, §5.2). No behavior beyond (de)serialization. The `Node` hierarchy is a sealed interface with a `type` discriminator.

**Scope note (distributions):** v1 fully supports three distribution JSON forms — `{"type":"exponential","rate":r}`, `{"type":"deterministic","value":v}`, and the moment form `{"mean":m,"scv":c}`. These cover the golden analytic checks and the qopt (mean+SCV) use case. The remaining named distributions listed in spec §5.1 (erlang, hyperexp, lognormal, …) are a mechanical follow-up: extend `Distribution` + `DistributionResolver`'s registry (Task 4). Record this as a v1 scope decision.

**Files:**
- Create: `src/main/java/qsim/model/SimulationRequest.java`, `NetworkModel.java`, `JobClass.java`, `Node.java`, `SourceNode.java`, `QueueNode.java`, `ForkJoinNode.java`, `DelayNode.java`, `SinkNode.java`, `ArrivalSpec.java`, `ServiceSpec.java`, `Branch.java`, `Distribution.java`, `RoutingEdge.java`, `Stopping.java`, `SimulationResponse.java`, `MeasureResult.java`
- Create: `src/main/java/qsim/http/Json.java` (shared `ObjectMapper`)
- Test: `src/test/java/qsim/model/RequestBindingTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces (used by every later task):
  - `SimulationRequest(NetworkModel model, Long seed, Stopping stopping, List<String> measures)`
  - `NetworkModel(String name, List<JobClass> classes, List<Node> nodes, Map<String,List<RoutingEdge>> routing)`
  - `JobClass(String name, String type, Integer population, String referenceStation)`
  - sealed `Node { String name(); String type(); }` with records `SourceNode(String name, String type, Map<String,ArrivalSpec> arrivals)`, `QueueNode(String name, String type, Integer servers, String scheduling, Integer capacity, Map<String,ServiceSpec> service)`, `ForkJoinNode(String name, String type, List<Branch> branches, String join)`, `DelayNode(String name, String type, Map<String,ServiceSpec> service)`, `SinkNode(String name, String type)`
  - `ArrivalSpec(Distribution distribution)`, `ServiceSpec(Distribution distribution)`, `Branch(Map<String,ServiceSpec> service)`
  - `Distribution(String type, Double rate, Double value, Double mean, Double scv)`
  - `RoutingEdge(String from, String to, Double probability)`
  - `Stopping(Double alpha, Double precision, Integer minSamples, Integer maxSamples, Double maxSimulatedTime, Long maxEvents, Integer maxWallClockSeconds, Boolean disableStatisticStop)`
  - `SimulationResponse(String modelName, String solutionMethod, Long seed, double wallClockSeconds, boolean completed, List<MeasureResult> measures)`
  - `MeasureResult(String station, String jobClass, String type, Double mean, Double lower, Double upper, Double alpha, Double precision, boolean success, Integer samplesAnalyzed, Integer samplesDiscarded, Double variance, Double stdDev)` — `jobClass` serializes as JSON key `class`.
  - `Json.MAPPER` — a configured `com.fasterxml.jackson.databind.ObjectMapper`.

- [ ] **Step 1: Write the failing binding test**

`src/test/java/qsim/model/RequestBindingTest.java`:

```java
package qsim.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import qsim.http.Json;
import org.junit.jupiter.api.Test;

class RequestBindingTest {

  @Test
  void deserializesNodesByTypeDiscriminator() throws Exception {
    String json = """
        {
          "model": {
            "name": "mixed",
            "classes": [
              {"name": "web", "type": "open"},
              {"name": "batch", "type": "closed", "population": 20, "referenceStation": "think"}
            ],
            "nodes": [
              {"name": "src1", "type": "source",
               "arrivals": {"web": {"distribution": {"type": "exponential", "rate": 10.0}}}},
              {"name": "q1", "type": "queue", "servers": 1, "scheduling": "fcfs", "capacity": null,
               "service": {"web": {"distribution": {"type": "exponential", "rate": 12.0}},
                           "batch": {"distribution": {"mean": 0.5, "scv": 2.0}}}},
              {"name": "think", "type": "delay",
               "service": {"batch": {"distribution": {"type": "exponential", "rate": 0.2}}}},
              {"name": "sink", "type": "sink"}
            ],
            "routing": {
              "web": [{"from": "src1", "to": "q1"}, {"from": "q1", "to": "sink"}]
            }
          },
          "seed": 12345,
          "stopping": {"alpha": 0.05, "precision": 0.05, "maxSamples": 1000000, "maxWallClockSeconds": 120},
          "measures": ["response-time", "utilization"]
        }
        """;

    SimulationRequest req = Json.MAPPER.readValue(json, SimulationRequest.class);

    assertEquals("mixed", req.model().name());
    assertEquals(12345L, req.seed());
    assertEquals(2, req.model().classes().size());
    assertInstanceOf(SourceNode.class, req.model().nodes().get(0));
    assertInstanceOf(QueueNode.class, req.model().nodes().get(1));
    assertInstanceOf(DelayNode.class, req.model().nodes().get(2));
    assertInstanceOf(SinkNode.class, req.model().nodes().get(3));

    QueueNode q1 = (QueueNode) req.model().nodes().get(1);
    assertEquals(2.0, q1.service().get("batch").distribution().scv());
    assertEquals(10.0, ((SourceNode) req.model().nodes().get(0))
        .arrivals().get("web").distribution().rate());
    assertEquals(1, req.model().routing().get("web").size() - 1);
  }

  @Test
  void serializesResponseWithClassKey() throws Exception {
    MeasureResult m = new MeasureResult("q1", "web", "response-time",
        0.42, 0.40, 0.44, 0.05, 0.048, true, 45000, 1200, 0.011, 0.105);
    SimulationResponse resp = new SimulationResponse(
        "mixed", "simulation", 12345L, 8.3, true, java.util.List.of(m));

    String out = Json.MAPPER.writeValueAsString(resp);
    assertTrue(out.contains("\"class\":\"web\""), "measure job class must serialize as key 'class'");
    assertTrue(out.contains("\"solutionMethod\":\"simulation\""));
    assertTrue(out.contains("\"completed\":true"));
  }
}
```

- [ ] **Step 2: Run it — expect compile failure**

Run: `mvn -q test -Dtest=RequestBindingTest`
Expected: FAIL (classes `SimulationRequest`, `Json`, etc. do not exist).

- [ ] **Step 3: Create the shared ObjectMapper**

`src/main/java/qsim/http/Json.java` (with GPL header):

```java
package qsim.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

public final class Json {
  private Json() {}

  public static final ObjectMapper MAPPER = JsonMapper.builder()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
      .build();
}
```

- [ ] **Step 4: Create the model records**

Create each record listed under **Interfaces** above (all in package `qsim.model`, each with the GPL header). Key details:

- `Node.java`:

```java
package qsim.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "type", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = SourceNode.class, name = "source"),
    @JsonSubTypes.Type(value = QueueNode.class, name = "queue"),
    @JsonSubTypes.Type(value = ForkJoinNode.class, name = "fork-join"),
    @JsonSubTypes.Type(value = DelayNode.class, name = "delay"),
    @JsonSubTypes.Type(value = SinkNode.class, name = "sink")
})
public sealed interface Node
    permits SourceNode, QueueNode, ForkJoinNode, DelayNode, SinkNode {
  String name();
  String type();
}
```

- The five node records implement `Node`, e.g. `public record QueueNode(String name, String type, Integer servers, String scheduling, Integer capacity, java.util.Map<String, ServiceSpec> service) implements Node {}`.
- `MeasureResult.java` annotates the job-class component: `@com.fasterxml.jackson.annotation.JsonProperty("class") String jobClass`. On a record, place the annotation on the record component parameter.
- All other records are plain records with the components listed under **Interfaces**.

- [ ] **Step 5: Run the test — expect pass**

Run: `mvn -q test -Dtest=RequestBindingTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/qsim/model/ src/main/java/qsim/http/Json.java src/test/java/qsim/model/RequestBindingTest.java
git commit -m "feat: domain model records and Jackson binding for the request/response contract"
```

---

### Task 4: Distribution resolution & moment matching

Convert a domain `Distribution` (named or moment form) into a `CanonicalDistribution` — engine-ready class names + ordered parameters — with **no JMT imports** (plain strings, so this stays outside the quarantine and is trivially unit-testable). Implements spec §6.2.

**Verified moment map** (JMT `Gamma` uses scale semantics; the empirical moment test in Task 13 is the safety net):
- `scv == 1` → Exponential, param `lambda = 1/mean`
- `scv == 0` → Deterministic, param `t = mean`
- otherwise → Gamma, params `alpha = 1/scv`, `beta = mean*scv`

**Verified JMT distribution classes / ordered param names** (GUI `Parameter` names, emitted as child subParameters in constructor order): Exponential → `jmt.engine.random.Exponential` / `ExponentialPar` / label `"Exponential"` / `[lambda:Double]`; Deterministic → `DeterministicDistr` / `DeterministicDistrPar` / `"Deterministic"` / `[t:Double]`; Gamma → `GammaDistr` / `GammaDistrPar` / `"Gamma"` / `[alpha:Double, beta:Double]`.

**Files:**
- Create: `src/main/java/qsim/distribution/CanonicalDistribution.java`, `DistParam.java`, `DistributionResolver.java`
- Test: `src/test/java/qsim/distribution/DistributionResolverTest.java`

**Interfaces:**
- Consumes: `qsim.model.Distribution`.
- Produces:
  - `DistParam(String name, String javaType, String value)` — one child subParameter (`javaType` e.g. `"java.lang.Double"`).
  - `CanonicalDistribution(String distributionClass, String parameterClass, String label, java.util.List<DistParam> params)`.
  - `DistributionResolver.resolve(Distribution d): CanonicalDistribution` — throws `IllegalArgumentException` with a clear message on invalid input (e.g. moment form with `mean <= 0`, negative `scv`, exponential with missing/`<= 0` rate).

- [ ] **Step 1: Write the failing tests**

`src/test/java/qsim/distribution/DistributionResolverTest.java`:

```java
package qsim.distribution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import qsim.model.Distribution;
import org.junit.jupiter.api.Test;

class DistributionResolverTest {

  private final DistributionResolver resolver = new DistributionResolver();

  @Test
  void namedExponentialMapsRateToLambda() {
    CanonicalDistribution c = resolver.resolve(new Distribution("exponential", 10.0, null, null, null));
    assertEquals("jmt.engine.random.Exponential", c.distributionClass());
    assertEquals("jmt.engine.random.ExponentialPar", c.parameterClass());
    assertEquals(1, c.params().size());
    assertEquals("lambda", c.params().get(0).name());
    assertEquals("10.0", c.params().get(0).value());
  }

  @Test
  void namedDeterministicMapsValueToT() {
    CanonicalDistribution c = resolver.resolve(new Distribution("deterministic", null, 0.25, null, null));
    assertEquals("jmt.engine.random.DeterministicDistr", c.distributionClass());
    assertEquals("t", c.params().get(0).name());
    assertEquals("0.25", c.params().get(0).value());
  }

  @Test
  void momentScvOneIsExponential() {
    CanonicalDistribution c = resolver.resolve(new Distribution(null, null, null, 0.5, 1.0));
    assertEquals("jmt.engine.random.Exponential", c.distributionClass());
    assertEquals("lambda", c.params().get(0).name());
    assertEquals("2.0", c.params().get(0).value()); // 1/mean
  }

  @Test
  void momentScvZeroIsDeterministic() {
    CanonicalDistribution c = resolver.resolve(new Distribution(null, null, null, 0.5, 0.0));
    assertEquals("jmt.engine.random.DeterministicDistr", c.distributionClass());
    assertEquals("0.5", c.params().get(0).value());
  }

  @Test
  void momentGeneralScvIsGamma() {
    CanonicalDistribution c = resolver.resolve(new Distribution(null, null, null, 0.5, 2.0));
    assertEquals("jmt.engine.random.GammaDistr", c.distributionClass());
    assertEquals("alpha", c.params().get(0).name());
    assertEquals("0.5", c.params().get(0).value());  // 1/scv
    assertEquals("beta", c.params().get(1).name());
    assertEquals("1.0", c.params().get(1).value());  // mean*scv
  }

  @Test
  void invalidInputsThrow() {
    assertThrows(IllegalArgumentException.class,
        () -> resolver.resolve(new Distribution("exponential", -1.0, null, null, null)));
    assertThrows(IllegalArgumentException.class,
        () -> resolver.resolve(new Distribution(null, null, null, 0.0, 1.0)));   // mean must be > 0
    assertThrows(IllegalArgumentException.class,
        () -> resolver.resolve(new Distribution(null, null, null, 1.0, -0.5))); // scv >= 0
    assertThrows(IllegalArgumentException.class,
        () -> resolver.resolve(new Distribution(null, null, null, null, null))); // neither form
  }
}
```

- [ ] **Step 2: Run — expect compile failure**

Run: `mvn -q test -Dtest=DistributionResolverTest`
Expected: FAIL (classes missing).

- [ ] **Step 3: Implement the value objects**

`CanonicalDistribution.java` and `DistParam.java` (package `qsim.distribution`, GPL headers):

```java
package qsim.distribution;
import java.util.List;
public record CanonicalDistribution(String distributionClass, String parameterClass,
                                    String label, List<DistParam> params) {}
```
```java
package qsim.distribution;
public record DistParam(String name, String javaType, String value) {}
```

- [ ] **Step 4: Implement the resolver**

`DistributionResolver.java`:

```java
package qsim.distribution;

import java.util.List;
import qsim.model.Distribution;

public class DistributionResolver {

  private static final String D = "java.lang.Double";

  public CanonicalDistribution resolve(Distribution d) {
    if (d.type() != null) {
      return resolveNamed(d);
    }
    return resolveMoment(d);
  }

  private CanonicalDistribution resolveNamed(Distribution d) {
    switch (d.type()) {
      case "exponential": {
        double rate = require(d.rate(), "exponential.rate");
        positive(rate, "exponential.rate");
        return exponential(rate);
      }
      case "deterministic": {
        double v = require(d.value() != null ? d.value() : d.mean(), "deterministic.value");
        positive(v, "deterministic.value");
        return deterministic(v);
      }
      default:
        throw new IllegalArgumentException("unsupported distribution type: " + d.type()
            + " (v1 supports exponential, deterministic, or moment form {mean, scv})");
    }
  }

  private CanonicalDistribution resolveMoment(Distribution d) {
    double mean = require(d.mean(), "distribution.mean");
    double scv = require(d.scv(), "distribution.scv");
    positive(mean, "distribution.mean");
    if (scv < 0) {
      throw new IllegalArgumentException("distribution.scv must be >= 0");
    }
    if (scv == 1.0) {
      return exponential(1.0 / mean);
    }
    if (scv == 0.0) {
      return deterministic(mean);
    }
    double alpha = 1.0 / scv;
    double beta = mean * scv;
    return new CanonicalDistribution("jmt.engine.random.GammaDistr",
        "jmt.engine.random.GammaDistrPar", "Gamma",
        List.of(new DistParam("alpha", D, str(alpha)), new DistParam("beta", D, str(beta))));
  }

  private CanonicalDistribution exponential(double lambda) {
    return new CanonicalDistribution("jmt.engine.random.Exponential",
        "jmt.engine.random.ExponentialPar", "Exponential",
        List.of(new DistParam("lambda", D, str(lambda))));
  }

  private CanonicalDistribution deterministic(double t) {
    return new CanonicalDistribution("jmt.engine.random.DeterministicDistr",
        "jmt.engine.random.DeterministicDistrPar", "Deterministic",
        List.of(new DistParam("t", D, str(t))));
  }

  private static double require(Double v, String field) {
    if (v == null) {
      throw new IllegalArgumentException("missing required field: " + field);
    }
    return v;
  }

  private static void positive(double v, String field) {
    if (v <= 0) {
      throw new IllegalArgumentException(field + " must be > 0");
    }
  }

  // Render doubles without locale/exponent surprises; integers stay clean (2.0 -> "2.0").
  private static String str(double v) {
    return Double.toString(v);
  }
}
```

- [ ] **Step 5: Run — expect pass**

Run: `mvn -q test -Dtest=DistributionResolverTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/qsim/distribution/ src/test/java/qsim/distribution/DistributionResolverTest.java
git commit -m "feat: distribution resolution and moment matching (exp/det/gamma)"
```

---

### Task 5: Contract validation & invariants

Enforce spec §5.3 invariants before any translation, collecting all violations and throwing a single structured error. Malformed JSON (unknown node `type`, wrong value types) is already rejected by Jackson at the HTTP layer (→ 400); this validator handles the *semantic* model errors (→ 422).

**Deferred (documented) check:** spec §7 lists "open station arrival rate ≥ total service capacity" as a 422. A general per-station arrival-rate precheck requires solving the network's traffic equations (visit ratios), which is out of scope for v1. Instability instead surfaces non-silently as non-convergence (`completed:false`, `success:false`) from the run itself. Note this in the task and self-review.

**Files:**
- Create: `src/main/java/qsim/contract/ValidationException.java`, `ContractValidator.java`
- Test: `src/test/java/qsim/contract/ContractValidatorTest.java`

**Interfaces:**
- Consumes: `qsim.model.*`.
- Produces:
  - `ValidationException extends RuntimeException` with `enum Kind { BAD_REQUEST, UNPROCESSABLE }`, `Kind kind()`, `List<String> details()`.
  - `ContractValidator.validate(SimulationRequest req): void` — throws `ValidationException(UNPROCESSABLE)` listing every violated invariant; returns normally if valid.
  - `ContractValidator.effectiveProbability(...)` semantics: a single outgoing edge for a (node,class) defaults to probability 1.0; multiple edges must each carry a probability and sum to 1.0 ± 1e-6.

- [ ] **Step 1: Write the failing tests**

`src/test/java/qsim/contract/ContractValidatorTest.java`:

```java
package qsim.contract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import qsim.model.*;
import org.junit.jupiter.api.Test;

class ContractValidatorTest {

  private final ContractValidator validator = new ContractValidator();

  private SimulationRequest req(NetworkModel model) {
    return new SimulationRequest(model, 1L, null, null);
  }

  private Distribution exp(double r) { return new Distribution("exponential", r, null, null, null); }

  @Test
  void acceptsAValidOpenModel() {
    NetworkModel m = new NetworkModel("ok",
        List.of(new JobClass("web", "open", null, null)),
        List.of(
            new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
            new QueueNode("q", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(2.0)))),
            new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));
    assertDoesNotThrow(() -> validator.validate(req(m)));
  }

  @Test
  void rejectsOpenClassNotAnchoredToExactlyOneSource() {
    NetworkModel m = new NetworkModel("bad",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SinkNode("snk", "sink")),
        Map.of("web", List.of()));
    ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(req(m)));
    assertEquals(ValidationException.Kind.UNPROCESSABLE, ex.kind());
    assertTrue(ex.details().stream().anyMatch(s -> s.contains("web") && s.contains("source")));
  }

  @Test
  void rejectsClosedClassWithoutPopulation() {
    NetworkModel m = new NetworkModel("bad",
        List.of(new JobClass("batch", "closed", null, "think")),
        List.of(new DelayNode("think", "delay", Map.of("batch", new ServiceSpec(exp(0.2))))),
        Map.of("batch", List.of(new RoutingEdge("think", "think", 1.0))));
    ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(req(m)));
    assertTrue(ex.details().stream().anyMatch(s -> s.contains("population")));
  }

  @Test
  void rejectsDanglingRoutingTarget() {
    NetworkModel m = new NetworkModel("bad",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "ghost", null))));
    ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(req(m)));
    assertTrue(ex.details().stream().anyMatch(s -> s.contains("ghost")));
  }

  @Test
  void rejectsProbabilitiesNotSummingToOne() {
    NetworkModel m = new NetworkModel("bad",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
                new QueueNode("a", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(2.0)))),
                new QueueNode("b", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(2.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(
            new RoutingEdge("src", "a", 0.5), new RoutingEdge("src", "b", 0.3),
            new RoutingEdge("a", "snk", 1.0), new RoutingEdge("b", "snk", 1.0))));
    ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(req(m)));
    assertTrue(ex.details().stream().anyMatch(s -> s.contains("probab")));
  }

  @Test
  void rejectsQueueWithZeroServersOrNegativeCapacity() {
    NetworkModel m = new NetworkModel("bad",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
                new QueueNode("q", "queue", 0, "fcfs", -5, Map.of("web", new ServiceSpec(exp(2.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));
    ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(req(m)));
    assertTrue(ex.details().stream().anyMatch(s -> s.contains("servers")));
    assertTrue(ex.details().stream().anyMatch(s -> s.contains("capacity")));
  }
}
```

(Add `import static org.junit.jupiter.api.Assertions.assertEquals;`.)

- [ ] **Step 2: Run — expect compile failure**

Run: `mvn -q test -Dtest=ContractValidatorTest`
Expected: FAIL.

- [ ] **Step 3: Implement `ValidationException`**

```java
package qsim.contract;
import java.util.List;
public class ValidationException extends RuntimeException {
  public enum Kind { BAD_REQUEST, UNPROCESSABLE }
  private final transient Kind kind;
  private final transient List<String> details;
  public ValidationException(Kind kind, List<String> details) {
    super(String.join("; ", details));
    this.kind = kind;
    this.details = List.copyOf(details);
  }
  public Kind kind() { return kind; }
  public List<String> details() { return details; }
}
```

- [ ] **Step 4: Implement `ContractValidator`**

```java
package qsim.contract;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import qsim.model.*;

public class ContractValidator {

  private static final double EPS = 1e-6;

  public void validate(SimulationRequest req) {
    List<String> errors = new ArrayList<>();
    NetworkModel model = req.model();
    if (model == null || model.nodes() == null || model.classes() == null) {
      throw new ValidationException(ValidationException.Kind.UNPROCESSABLE,
          List.of("model, model.nodes and model.classes are required"));
    }

    Set<String> nodeNames = model.nodes().stream().map(Node::name).collect(Collectors.toSet());

    // Queue invariants: servers >= 1; capacity null or positive.
    for (Node n : model.nodes()) {
      if (n instanceof QueueNode q) {
        if (q.servers() == null || q.servers() < 1) {
          errors.add("queue '" + q.name() + "': servers must be >= 1");
        }
        if (q.capacity() != null && q.capacity() <= 0) {
          errors.add("queue '" + q.name() + "': capacity must be null (infinite) or a positive integer");
        }
      }
    }

    // Open class anchored to exactly one source.
    for (JobClass c : model.classes()) {
      if ("open".equals(c.type())) {
        long sources = model.nodes().stream()
            .filter(n -> n instanceof SourceNode)
            .map(n -> (SourceNode) n)
            .filter(s -> s.arrivals() != null && s.arrivals().containsKey(c.name()))
            .count();
        if (sources != 1) {
          errors.add("open class '" + c.name() + "' must be listed in exactly one source's arrivals (found "
              + sources + ")");
        }
      } else if ("closed".equals(c.type())) {
        if (c.population() == null || c.population() < 1) {
          errors.add("closed class '" + c.name() + "' must have a population >= 1");
        }
      } else {
        errors.add("class '" + c.name() + "': type must be 'open' or 'closed'");
      }
    }

    // Routing: targets exist; probabilities sum to 1 per (node, class).
    Map<String, List<RoutingEdge>> routing = model.routing() == null ? Map.of() : model.routing();
    for (Map.Entry<String, List<RoutingEdge>> e : routing.entrySet()) {
      String clazz = e.getKey();
      Map<String, List<RoutingEdge>> byFrom = new HashMap<>();
      for (RoutingEdge edge : e.getValue()) {
        if (!nodeNames.contains(edge.from())) {
          errors.add("routing[" + clazz + "]: 'from' node '" + edge.from() + "' does not exist");
        }
        if (!nodeNames.contains(edge.to())) {
          errors.add("routing[" + clazz + "]: 'to' node '" + edge.to() + "' does not exist");
        }
        byFrom.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge);
      }
      for (Map.Entry<String, List<RoutingEdge>> g : byFrom.entrySet()) {
        List<RoutingEdge> edges = g.getValue();
        if (edges.size() == 1) {
          continue; // single edge defaults to probability 1.0
        }
        double sum = 0;
        for (RoutingEdge edge : edges) {
          if (edge.probability() == null) {
            errors.add("routing[" + clazz + "] from '" + g.getKey()
                + "': each of multiple edges must set a probability");
            sum = Double.NaN;
            break;
          }
          sum += edge.probability();
        }
        if (!Double.isNaN(sum) && Math.abs(sum - 1.0) > EPS) {
          errors.add("routing[" + clazz + "] from '" + g.getKey()
              + "': probabilities must sum to 1.0 (got " + sum + ")");
        }
      }
    }

    if (!errors.isEmpty()) {
      throw new ValidationException(ValidationException.Kind.UNPROCESSABLE, errors);
    }
  }
}
```

- [ ] **Step 5: Run — expect pass**

Run: `mvn -q test -Dtest=ContractValidatorTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/qsim/contract/ src/test/java/qsim/contract/ContractValidatorTest.java
git commit -m "feat: contract validation of queueing-network model invariants"
```

---

### Task 6: Measure mapping

Translate the requested domain measure types into concrete JMT `<measure>` specifications, expanded over the relevant (station × class) pairs, plus system-level measures. Applies the default measure set when the request omits `measures`.

**Verified JMT measure `type` strings** (station-level, `nodeType="station"`): `response-time`→"Response Time", `residence-time`→"Residence Time", `queue-time`→"Queue Time", `queue-length`→"Number of Customers", `utilization`→"Utilization", `throughput`→"Throughput", `drop-rate`→"Drop Rate". System-level (`nodeType=""`, `referenceNode=""`): `system-response-time`→"System Response Time".

**Deferred (documented) measure types:** `arrival-rate`, `system-throughput`, and `fork-join-response-time` require JMT measure-type strings not yet verified against this build; v1's `SUPPORTED` set excludes them and the validator rejects them explicitly (loud failure, never silent). Adding one is a one-line registry entry once its exact string is confirmed against `jmt.gui.common.definitions.SimulationDefinition`.

**Files:**
- Create: `src/main/java/qsim/translate/MeasureSpec.java`, `MeasureMapper.java`
- Test: `src/test/java/qsim/translate/MeasureMapperTest.java`

**Interfaces:**
- Consumes: `qsim.model.*`, `qsim.contract.ValidationException`.
- Produces:
  - `MeasureSpec(String name, String jmtType, String referenceNode, String referenceUserClass, String nodeType)`.
  - `MeasureMapper.map(NetworkModel model, List<String> requested): List<MeasureSpec>` — `requested==null` ⇒ default set `["response-time","utilization","throughput","queue-length"]`. Station-level measures expand over each (queue/delay/fork-join node, class served there). System-level measures expand over each class. Unknown/unsupported measure names throw `ValidationException(BAD_REQUEST)`.
  - Static `MeasureMapper.SUPPORTED: Set<String>` for the validator/tests.

- [ ] **Step 1: Write the failing test**

`src/test/java/qsim/translate/MeasureMapperTest.java`:

```java
package qsim.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import qsim.contract.ValidationException;
import qsim.model.*;
import org.junit.jupiter.api.Test;

class MeasureMapperTest {

  private final MeasureMapper mapper = new MeasureMapper();
  private Distribution exp(double r) { return new Distribution("exponential", r, null, null, null); }

  private NetworkModel model() {
    return new NetworkModel("m",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
                new QueueNode("q", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(2.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));
  }

  @Test
  void mapsStationMeasureOverServedClasses() {
    List<MeasureSpec> specs = mapper.map(model(), List.of("utilization"));
    assertEquals(1, specs.size());
    MeasureSpec s = specs.get(0);
    assertEquals("Utilization", s.jmtType());
    assertEquals("q", s.referenceNode());
    assertEquals("web", s.referenceUserClass());
    assertEquals("station", s.nodeType());
  }

  @Test
  void mapsSystemResponseTimePerClass() {
    List<MeasureSpec> specs = mapper.map(model(), List.of("system-response-time"));
    assertEquals(1, specs.size());
    assertEquals("System Response Time", specs.get(0).jmtType());
    assertEquals("", specs.get(0).referenceNode());
    assertEquals("web", specs.get(0).referenceUserClass());
  }

  @Test
  void defaultSetWhenNull() {
    List<MeasureSpec> specs = mapper.map(model(), null);
    // 4 default types x 1 served station x 1 class = 4
    assertEquals(4, specs.size());
    assertTrue(specs.stream().anyMatch(s -> s.jmtType().equals("Number of Customers")));
  }

  @Test
  void unknownMeasureRejected() {
    ValidationException ex = assertThrows(ValidationException.class,
        () -> mapper.map(model(), List.of("teleportation-latency")));
    assertEquals(ValidationException.Kind.BAD_REQUEST, ex.kind());
  }
}
```

- [ ] **Step 2: Run — expect compile failure**

Run: `mvn -q test -Dtest=MeasureMapperTest`
Expected: FAIL.

- [ ] **Step 3: Implement `MeasureSpec`**

```java
package qsim.translate;
public record MeasureSpec(String name, String jmtType, String referenceNode,
                          String referenceUserClass, String nodeType) {}
```

- [ ] **Step 4: Implement `MeasureMapper`**

```java
package qsim.translate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import qsim.contract.ValidationException;
import qsim.model.*;

public class MeasureMapper {

  private static final List<String> DEFAULTS =
      List.of("response-time", "utilization", "throughput", "queue-length");

  // domain type -> JMT measure "type" string (station-level)
  private static final Map<String, String> STATION = new LinkedHashMap<>();
  // domain type -> JMT measure "type" string (system-level)
  private static final Map<String, String> SYSTEM = new LinkedHashMap<>();
  static {
    STATION.put("response-time", "Response Time");
    STATION.put("residence-time", "Residence Time");
    STATION.put("queue-time", "Queue Time");
    STATION.put("queue-length", "Number of Customers");
    STATION.put("utilization", "Utilization");
    STATION.put("throughput", "Throughput");
    STATION.put("drop-rate", "Drop Rate");
    SYSTEM.put("system-response-time", "System Response Time");
  }

  public static final Set<String> SUPPORTED;
  static {
    var all = new java.util.HashSet<String>();
    all.addAll(STATION.keySet());
    all.addAll(SYSTEM.keySet());
    SUPPORTED = Set.copyOf(all);
  }

  public List<MeasureSpec> map(NetworkModel model, List<String> requested) {
    List<String> types = (requested == null || requested.isEmpty()) ? DEFAULTS : requested;
    for (String t : types) {
      if (!SUPPORTED.contains(t)) {
        throw new ValidationException(ValidationException.Kind.BAD_REQUEST,
            List.of("unsupported measure type: '" + t + "'; supported: " + SUPPORTED));
      }
    }
    List<MeasureSpec> specs = new ArrayList<>();
    for (String t : types) {
      if (STATION.containsKey(t)) {
        String jmt = STATION.get(t);
        for (Node n : model.nodes()) {
          for (String clazz : servedClasses(n)) {
            specs.add(new MeasureSpec(n.name() + "_" + clazz + "_" + t, jmt,
                n.name(), clazz, "station"));
          }
        }
      } else { // system-level
        String jmt = SYSTEM.get(t);
        for (JobClass c : model.classes()) {
          specs.add(new MeasureSpec("system_" + c.name() + "_" + t, jmt, "", c.name(), ""));
        }
      }
    }
    return specs;
  }

  /** Classes with service defined at this node (queue/delay/fork-join). Sources/sinks yield none. */
  private static List<String> servedClasses(Node n) {
    if (n instanceof QueueNode q) {
      return new ArrayList<>(q.service().keySet());
    }
    if (n instanceof DelayNode d) {
      return new ArrayList<>(d.service().keySet());
    }
    if (n instanceof ForkJoinNode fj) {
      var set = new java.util.LinkedHashSet<String>();
      for (Branch b : fj.branches()) {
        set.addAll(b.service().keySet());
      }
      return new ArrayList<>(set);
    }
    return List.of();
  }
}
```

- [ ] **Step 5: Run — expect pass**

Run: `mvn -q test -Dtest=MeasureMapperTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/qsim/translate/MeasureSpec.java src/main/java/qsim/translate/MeasureMapper.java src/test/java/qsim/translate/MeasureMapperTest.java
git commit -m "feat: map domain measure types to JMT measure specs"
```

---

### Task 7: JSIMG writer — core infrastructure + open network

Emit the bare `<sim>` DOM the headless engine requires (see [[jmt-engine-facts]]: `SimLoader` throws unless the document element is `sim`). This task builds the shared DOM machinery and covers the **open** path: `<sim>` attributes from `Stopping`, open `userClass` with `referenceSource`, `source`/`queue`/`sink` nodes with their section blocks, per-class routing (`EmpiricalStrategy` / `RandomStrategy`), `<measure>` elements, and `<connection>` edges. The output is validated against `SIMmodeldefinition.xsd` (bundled in the JMT jar at `jmt/common/xml/`). Delay, fork-join, and closed classes are added in Task 8.

**This class is NOT in the `qsim.engine` quarantine package** — it emits *strings* (JMT class names as text) and touches no `jmt.*` types, so it lives in `qsim.translate` and is unit-testable without loading JMT.

**Files:**
- Create: `src/main/java/qsim/translate/Xml.java` (tiny DOM helper), `src/main/java/qsim/translate/JsimgWriter.java`
- Test: `src/test/java/qsim/translate/JsimgWriterOpenTest.java`
- Test resource (already unpacked in Task 1): the XSD is read from the JMT jar on the test classpath at `/jmt/common/xml/SIMmodeldefinition.xsd`.

**Interfaces:**
- Consumes: `qsim.model.*`, `qsim.distribution.{DistributionResolver, CanonicalDistribution, DistParam}`, `qsim.translate.{MeasureSpec, MeasureMapper}`.
- Produces:
  - `Xml` helper: `static Document newDocument()`, `static Element child(Node parent, String tag, String... attrPairs)` (attrPairs are alternating name,value; skips a pair whose value is null), `static String serialize(Document doc)`.
  - `JsimgWriter.toDocument(NetworkModel model, Stopping stopping, long seed, List<MeasureSpec> measures): org.w3c.dom.Document` — root element `sim`.
  - `JsimgWriter.toXmlString(NetworkModel, Stopping, long, List<MeasureSpec>): String`.
  - `JsimgWriter.validate(Document doc): void` — validates against `SIMmodeldefinition.xsd`; throws `qsim.contract.ValidationException(UNPROCESSABLE)` on schema failure (this is the §7 "JMT load / XSD-validation failure → 422" gate).
  - `JsimgWriter` holds a `DistributionResolver` (constructed internally).

- [ ] **Step 1: Write the failing test**

`src/test/java/qsim/translate/JsimgWriterOpenTest.java`:

```java
package qsim.translate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import qsim.model.*;

class JsimgWriterOpenTest {

  private final JsimgWriter writer = new JsimgWriter();
  private Distribution exp(double r) { return new Distribution("exponential", r, null, null, null); }

  /** M/M/1: source -> queue -> sink, one open class. */
  private NetworkModel mm1() {
    return new NetworkModel("mm1",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
                new QueueNode("q", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(2.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));
  }

  private Stopping stopping() {
    return new Stopping(0.05, 0.05, 10000, 1_000_000, null, null, 120, false);
  }

  @Test
  void rootElementIsBareSim() {
    Document doc = writer.toDocument(mm1(), stopping(), 42L,
        List.of(new MeasureSpec("q_web_u", "Utilization", "q", "web", "station")));
    assertEquals("sim", doc.getDocumentElement().getNodeName());
    assertEquals("42", doc.getDocumentElement().getAttribute("seed"));
    assertEquals("1000000", doc.getDocumentElement().getAttribute("maxSamples"));
  }

  @Test
  void emitsOpenUserClassWithReferenceSource() throws Exception {
    Document doc = writer.toDocument(mm1(), stopping(), 42L, List.of());
    XPath xp = XPathFactory.newInstance().newXPath();
    NodeList classes = (NodeList) xp.evaluate("/sim/userClass", doc,
        javax.xml.xpath.XPathConstants.NODESET);
    assertEquals(1, classes.getLength());
    org.w3c.dom.Element uc = (org.w3c.dom.Element) classes.item(0);
    assertEquals("open", uc.getAttribute("type"));
    assertEquals("web", uc.getAttribute("name"));
    assertEquals("src", uc.getAttribute("referenceSource"));
  }

  @Test
  void emitsThreeNodesWithExpectedSectionClasses() throws Exception {
    Document doc = writer.toDocument(mm1(), stopping(), 42L, List.of());
    XPath xp = XPathFactory.newInstance().newXPath();
    assertEquals("3", xp.evaluate("count(/sim/node)", doc));
    // queue node has Queue + Server + Router sections
    assertEquals("1", xp.evaluate(
        "count(/sim/node[@name='q']/section[@className='Server'])", doc));
    assertEquals("1", xp.evaluate(
        "count(/sim/node[@name='src']/section[@className='RandomSource'])", doc));
    assertEquals("1", xp.evaluate(
        "count(/sim/node[@name='snk']/section[@className='JobSink'])", doc));
  }

  @Test
  void emitsExponentialServiceStrategyWithLambda() throws Exception {
    Document doc = writer.toDocument(mm1(), stopping(), 42L, List.of());
    XPath xp = XPathFactory.newInstance().newXPath();
    String parClass = xp.evaluate(
        "//node[@name='q']//subParameter[@name='distrPar']/@classPath", doc);
    assertEquals("jmt.engine.random.ExponentialPar", parClass);
    String lambda = xp.evaluate(
        "//node[@name='q']//subParameter[@name='distrPar']/subParameter[@name='lambda']/value",
        doc);
    assertEquals("2.0", lambda);
  }

  @Test
  void emitsConnectionsAndMeasures() throws Exception {
    Document doc = writer.toDocument(mm1(), stopping(), 42L,
        List.of(new MeasureSpec("q_web_u", "Utilization", "q", "web", "station")));
    XPath xp = XPathFactory.newInstance().newXPath();
    assertEquals("2", xp.evaluate("count(/sim/connection)", doc));
    assertEquals("1", xp.evaluate("count(/sim/measure)", doc));
    assertEquals("Utilization", xp.evaluate("/sim/measure/@type", doc));
  }

  @Test
  void validatesAgainstBundledXsd() {
    Document doc = writer.toDocument(mm1(), stopping(), 42L,
        List.of(new MeasureSpec("q_web_u", "Utilization", "q", "web", "station")));
    assertDoesNotThrow(() -> writer.validate(doc));
  }
}
```

- [ ] **Step 2: Run — expect compile failure**

Run: `mvn -q test -Dtest=JsimgWriterOpenTest`
Expected: FAIL.

- [ ] **Step 3: Implement the `Xml` DOM helper**

`src/main/java/qsim/translate/Xml.java`:

```java
package qsim.translate;

import java.io.StringWriter;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public final class Xml {
  private Xml() {}

  public static Document newDocument() {
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(false);
      return f.newDocumentBuilder().newDocument();
    } catch (Exception e) {
      throw new IllegalStateException("cannot create XML document", e);
    }
  }

  /** Append a child element; attrPairs are alternating name,value. A pair with null value is skipped. */
  public static Element child(Node parent, String tag, String... attrPairs) {
    Document doc = parent instanceof Document d ? d : parent.getOwnerDocument();
    Element el = doc.createElement(tag);
    for (int i = 0; i + 1 < attrPairs.length; i += 2) {
      String name = attrPairs[i];
      String value = attrPairs[i + 1];
      if (value != null) {
        el.setAttribute(name, value);
      }
    }
    parent.appendChild(el);
    return el;
  }

  /** Element containing a single text node (used for <value>x</value>). */
  public static Element textEl(Node parent, String tag, String text) {
    Element el = child(parent, tag);
    el.appendChild(el.getOwnerDocument().createTextNode(text));
    return el;
  }

  public static String serialize(Document doc) {
    try {
      var t = TransformerFactory.newInstance().newTransformer();
      t.setOutputProperty(OutputKeys.INDENT, "yes");
      t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
      StringWriter sw = new StringWriter();
      t.transform(new DOMSource(doc), new StreamResult(sw));
      return sw.toString();
    } catch (Exception e) {
      throw new IllegalStateException("cannot serialize XML document", e);
    }
  }
}
```

- [ ] **Step 4: Implement `JsimgWriter` (open path)**

`src/main/java/qsim/translate/JsimgWriter.java`:

```java
package qsim.translate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.transform.dom.DOMSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import qsim.contract.ValidationException;
import qsim.distribution.CanonicalDistribution;
import qsim.distribution.DistParam;
import qsim.distribution.DistributionResolver;
import qsim.model.*;

public class JsimgWriter {

  private final DistributionResolver resolver = new DistributionResolver();

  public String toXmlString(NetworkModel model, Stopping stopping, long seed, List<MeasureSpec> measures) {
    return Xml.serialize(toDocument(model, stopping, seed, measures));
  }

  public Document toDocument(NetworkModel model, Stopping stopping, long seed, List<MeasureSpec> measures) {
    Document doc = Xml.newDocument();
    Element sim = Xml.child(doc, "sim",
        "xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance",
        "name", model.name(),
        "seed", Long.toString(seed),
        "maxSamples", stopping == null || stopping.maxSamples() == null ? "1000000" : stopping.maxSamples().toString(),
        "maxEvents", stopping == null || stopping.maxEvents() == null ? "-1" : stopping.maxEvents().toString(),
        "maxSimulated", stopping == null || stopping.maxSimulatedTime() == null ? "-1.0" : stopping.maxSimulatedTime().toString(),
        "disableStatisticStop", stopping != null && Boolean.TRUE.equals(stopping.disableStatisticStop()) ? "true" : "false",
        "polling", "1.0");

    writeUserClasses(sim, model);
    for (Node n : model.nodes()) {
      writeNode(sim, model, n);
    }
    if (measures != null) {
      for (MeasureSpec m : measures) {
        writeMeasure(sim, m, stopping);
      }
    }
    writeConnections(sim, model);
    return doc;
  }

  // ---- user classes --------------------------------------------------------

  private void writeUserClasses(Element sim, NetworkModel model) {
    for (JobClass c : model.classes()) {
      if ("open".equals(c.type())) {
        Xml.child(sim, "userClass",
            "name", c.name(), "type", "open", "priority", "0",
            "referenceSource", sourceOf(model, c.name()));
      } else {
        // closed: filled in Task 8 (customers + reference station)
        writeClosedUserClass(sim, model, c);
      }
    }
  }

  /** Name of the single source whose arrivals include this open class. */
  private String sourceOf(NetworkModel model, String className) {
    return model.nodes().stream()
        .filter(n -> n instanceof SourceNode)
        .map(n -> (SourceNode) n)
        .filter(s -> s.arrivals() != null && s.arrivals().containsKey(className))
        .map(SourceNode::name)
        .findFirst()
        .orElseThrow(() -> new ValidationException(ValidationException.Kind.UNPROCESSABLE,
            List.of("open class '" + className + "' has no source")));
  }

  /** Overridden behavior lives in Task 8; open-only build never reaches a closed class. */
  protected void writeClosedUserClass(Element sim, NetworkModel model, JobClass c) {
    throw new ValidationException(ValidationException.Kind.UNPROCESSABLE,
        List.of("closed classes not yet supported"));
  }

  // ---- nodes ---------------------------------------------------------------

  private void writeNode(Element sim, NetworkModel model, Node n) {
    Element node = Xml.child(sim, "node", "name", n.name());
    if (n instanceof SourceNode src) {
      writeRandomSource(node, model, src);
      writeServiceTunnel(node);
      writeRouter(node, model, n.name());
    } else if (n instanceof QueueNode q) {
      writeQueueSection(node, q);
      writeServer(node, q);
      writeRouter(node, model, n.name());
    } else if (n instanceof SinkNode) {
      Xml.child(node, "section", "className", "JobSink");
    } else {
      writeNonOpenNode(sim, node, model, n); // delay / fork-join → Task 8
    }
  }

  /** Delay and fork-join sections are added in Task 8. */
  protected void writeNonOpenNode(Element sim, Element node, NetworkModel model, Node n) {
    throw new ValidationException(ValidationException.Kind.UNPROCESSABLE,
        List.of("node type '" + n.type() + "' not yet supported"));
  }

  private void writeRandomSource(Element node, NetworkModel model, SourceNode src) {
    Element section = Xml.child(node, "section", "className", "RandomSource");
    // ServiceStrategy parameter: one entry per open class served here.
    Element param = Xml.child(section, "parameter",
        "classPath", "jmt.engine.NetStrategies.ServiceStrategy",
        "name", "ServiceStrategy", "array", "true");
    Element refClasses = Xml.child(section, "parameter",
        "classPath", "java.lang.String", "name", "IsPerClass"); // placeholder guard, see note
    node.getFirstChild().removeChild(refClasses); // not needed; keep section minimal
    for (Map.Entry<String, ArrivalSpec> e : src.arrivals().entrySet()) {
      writeServiceStrategyEntry(param, e.getKey(), e.getValue().distribution());
    }
  }

  private void writeServiceTunnel(Element node) {
    Xml.child(node, "section", "className", "ServiceTunnel");
  }

  private void writeQueueSection(Element node, QueueNode q) {
    Element section = Xml.child(node, "section", "className", "Queue");
    Xml.textEl(Xml.child(section, "parameter", "classPath", "java.lang.Integer", "name", "size"),
        "value", q.capacity() == null ? "-1" : q.capacity().toString());
    Xml.textEl(Xml.child(section, "parameter", "classPath", "java.lang.String", "name", "dropStrategy"),
        "value", q.capacity() == null ? "waiting queue" : "drop");
    // FCFS get/put strategies are the JMT defaults for the schema; scheduling honored on Server.
  }

  private void writeServer(Element node, QueueNode q) {
    Element section = Xml.child(node, "section", "className", "Server");
    Xml.textEl(Xml.child(section, "parameter", "classPath", "java.lang.Integer", "name", "maxJobs"),
        "value", Integer.toString(q.servers()));
    Xml.textEl(Xml.child(section, "parameter", "classPath", "java.lang.Integer", "name", "numberOfVisits"),
        "value", "0");
    Element svc = Xml.child(section, "parameter",
        "classPath", "jmt.engine.NetStrategies.ServiceStrategy",
        "name", "ServiceStrategy", "array", "true");
    for (Map.Entry<String, ServiceSpec> e : q.service().entrySet()) {
      writeServiceStrategyEntry(svc, e.getKey(), e.getValue().distribution());
    }
  }

  // ---- shared: a distribution as a ServiceStrategy array entry --------------

  /** Emits one refClass subParameter + the ServiceTimeStrategy holding the distribution. */
  void writeServiceStrategyEntry(Element serviceStrategyParam, String className, Distribution dist) {
    CanonicalDistribution c = resolver.resolve(dist);
    Element refClass = Xml.child(serviceStrategyParam, "refClass");
    refClass.appendChild(refClass.getOwnerDocument().createTextNode(className));
    Element strat = Xml.child(serviceStrategyParam, "subParameter",
        "classPath", "jmt.engine.NetStrategies.ServiceStrategies.ServiceTimeStrategy",
        "name", "ServiceTimeStrategy");
    // distribution wrapper (empty) + its Par block
    Xml.child(strat, "subParameter", "classPath", c.distributionClass(), "name", c.label());
    Element par = Xml.child(strat, "subParameter",
        "classPath", c.parameterClass(), "name", "distrPar");
    for (DistParam p : c.params()) {
      Element sp = Xml.child(par, "subParameter", "classPath", p.javaType(), "name", p.name());
      Xml.textEl(sp, "value", p.value());
    }
  }

  // ---- routing -------------------------------------------------------------

  private void writeRouter(Element node, NetworkModel model, String fromNode) {
    Element section = Xml.child(node, "section", "className", "Router");
    Element param = Xml.child(section, "parameter",
        "classPath", "jmt.engine.NetStrategies.RoutingStrategy",
        "name", "RoutingStrategy", "array", "true");
    for (JobClass c : model.classes()) {
      List<RoutingEdge> edges = outEdges(model, c.name(), fromNode);
      Element refClass = Xml.child(param, "refClass");
      refClass.appendChild(refClass.getOwnerDocument().createTextNode(c.name()));
      if (edges.size() <= 1) {
        Xml.child(param, "subParameter",
            "classPath", "jmt.engine.NetStrategies.RoutingStrategies.RandomStrategy",
            "name", "Random");
      } else {
        Element emp = Xml.child(param, "subParameter",
            "classPath", "jmt.engine.NetStrategies.RoutingStrategies.EmpiricalStrategy",
            "name", "Probabilities");
        Element entries = Xml.child(emp, "subParameter",
            "classPath", "jmt.engine.random.EmpiricalEntry", "name", "EmpiricalEntryArray", "array", "true");
        for (RoutingEdge edge : edges) {
          Element entry = Xml.child(entries, "subParameter",
              "classPath", "jmt.engine.random.EmpiricalEntry", "name", "EmpiricalEntry");
          Xml.textEl(Xml.child(entry, "subParameter", "classPath", "java.lang.String", "name", "stationName"),
              "value", edge.to());
          Xml.textEl(Xml.child(entry, "subParameter", "classPath", "java.lang.Double", "name", "probability"),
              "value", Double.toString(edge.probability()));
        }
      }
    }
  }

  private static List<RoutingEdge> outEdges(NetworkModel model, String className, String fromNode) {
    List<RoutingEdge> out = new ArrayList<>();
    List<RoutingEdge> edges = model.routing() == null ? List.of() : model.routing().getOrDefault(className, List.of());
    for (RoutingEdge e : edges) {
      if (e.from().equals(fromNode)) {
        out.add(e);
      }
    }
    return out;
  }

  private void writeConnections(Element sim, NetworkModel model) {
    // Distinct (from,to) pairs across all classes — connections are class-agnostic in JSIMG.
    var seen = new java.util.LinkedHashSet<String>();
    if (model.routing() != null) {
      for (List<RoutingEdge> edges : model.routing().values()) {
        for (RoutingEdge e : edges) {
          if (seen.add(e.from() + " " + e.to())) {
            Xml.child(sim, "connection", "source", e.from(), "target", e.to());
          }
        }
      }
    }
  }

  // ---- measures ------------------------------------------------------------

  private void writeMeasure(Element sim, MeasureSpec m, Stopping stopping) {
    String alpha = stopping == null || stopping.alpha() == null ? "0.01" : Double.toString(1.0 - stopping.alpha());
    String precision = stopping == null || stopping.precision() == null ? "0.03" : stopping.precision().toString();
    Xml.child(sim, "measure",
        "name", m.name(),
        "type", m.jmtType(),
        "referenceNode", m.referenceNode(),
        "referenceUserClass", m.referenceUserClass(),
        "nodeType", m.nodeType(),
        "alpha", alpha,
        "precision", precision,
        "verbose", "false");
  }

  // ---- XSD validation ------------------------------------------------------

  public void validate(Document doc) {
    try {
      SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
      var xsd = JsimgWriter.class.getResourceAsStream("/jmt/common/xml/SIMmodeldefinition.xsd");
      if (xsd == null) {
        throw new IllegalStateException("SIMmodeldefinition.xsd not found on classpath (JMT jar missing?)");
      }
      Schema schema = sf.newSchema(new javax.xml.transform.stream.StreamSource(xsd));
      Validator v = schema.newValidator();
      v.validate(new DOMSource(doc));
    } catch (org.xml.sax.SAXException e) {
      throw new ValidationException(ValidationException.Kind.UNPROCESSABLE,
          List.of("generated JSIMG failed XSD validation: " + e.getMessage()));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("XSD validation I/O error", e);
    }
  }
}
```

> **Implementation note on `alpha`:** JMT's `<measure alpha>` is the *confidence* (e.g. 0.99), not the significance. The domain contract's `stopping.alpha` is the significance (0.05 ⇒ 95%). The writer emits `1 - alpha`. The parser (Task 10) reverses this so the response reports the domain `alpha`.
>
> **Note on `RandomSource` param shape:** the exact `RandomSource` service-strategy block is verified against `open_1class_1stat_mm1fcfs.jsimg` in Task 2's extracted `mm1.sim.xml`. If the `refClass`/`ServiceStrategy` array layout in that template differs from the code above (e.g. the array wrapper element name), correct `writeServiceStrategyEntry`/`writeRandomSource` to match the template exactly before moving on — the XSD-validation test in this task is the gate. Remove the two throwaway `refClasses`/`removeChild` lines if the template shows `RandomSource` needs no extra guard parameter.

- [ ] **Step 5: Run — iterate against the XSD until green**

Run: `mvn -q test -Dtest=JsimgWriterOpenTest`
Expected: PASS. If `validatesAgainstBundledXsd` fails, diff the emitted XML (`toXmlString`) against `mm1.sim.xml` from Task 2 and reconcile element/attribute names — the template is ground truth. Do not weaken the assertion; fix the writer.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/qsim/translate/Xml.java src/main/java/qsim/translate/JsimgWriter.java src/test/java/qsim/translate/JsimgWriterOpenTest.java
git commit -m "feat: JSIMG writer for open networks (source/queue/sink, routing, measures)"
```

---

### Task 8: JSIMG writer — delay, fork-join, and closed classes

Extend `JsimgWriter` to cover the remaining node types and closed classes by implementing the two hooks left as throwing stubs in Task 7 (`writeClosedUserClass`, `writeNonOpenNode`). Delay = `Queue`+`Delay`+`Router` (infinite server); fork-join = `Fork` node → branch `Server` stations → `Join` node (`NormalJoin`); closed `userClass` = `customers`(population) + reference station (default per §5.3).

**Design decision (fork-join expansion):** the domain `ForkJoinNode` with N branches expands to **2 + N JSIMG nodes**: a fork node (`Queue`+`ServiceTunnel`+`Fork`), one branch server station per branch (named `<fj>__b0`, `<fj>__b1`, …), and a join node (`Join`+`ServiceTunnel`+`Router`) named `<fj>__join`. Connections: `fork → each branch → join`. External routing edges *into* the fork-join node target the fork node name; edges *out of* it originate from the join node name. This expansion is confined to the writer; the domain contract and measure references keep using the single `fork-join` node name (the measure mapper in Task 6 references `n.name()`, so a fork-join measure must be remapped to the join node — handled here in `expandedMeasureNode`).

**Files:**
- Modify: `src/main/java/qsim/translate/JsimgWriter.java`
- Test: `src/test/java/qsim/translate/JsimgWriterClosedForkTest.java`

**Interfaces:**
- Consumes: same as Task 7, plus `ForkJoinNode`, `DelayNode`, `Branch`, closed `JobClass` fields (`population`, `referenceStation`).
- Produces (in addition to Task 7's surface):
  - `JsimgWriter.branchStationName(String forkNode, int index): String` → `forkNode + "__b" + index` (static, package-visible; reused by the measure remap and by Task 2 tooling if needed).
  - `JsimgWriter.joinStationName(String forkNode): String` → `forkNode + "__join"`.
  - Overrides of `writeClosedUserClass` and `writeNonOpenNode` (no longer throw).

- [ ] **Step 1: Write the failing test**

`src/test/java/qsim/translate/JsimgWriterClosedForkTest.java`:

```java
package qsim.translate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import qsim.model.*;

class JsimgWriterClosedForkTest {

  private final JsimgWriter writer = new JsimgWriter();
  private Distribution exp(double r) { return new Distribution("exponential", r, null, null, null); }
  private Stopping stopping() { return new Stopping(0.05, 0.05, 10000, 1_000_000, null, null, 120, false); }

  /** Closed machine-repair: think(delay) -> q(queue) -> think. */
  private NetworkModel closedNet() {
    return new NetworkModel("closed",
        List.of(new JobClass("batch", "closed", 15, "think")),
        List.of(new DelayNode("think", "delay", Map.of("batch", new ServiceSpec(exp(0.2)))),
                new QueueNode("q", "queue", 2, "fcfs", null, Map.of("batch", new ServiceSpec(exp(3.0))))),
        Map.of("batch", List.of(new RoutingEdge("think", "q", 1.0), new RoutingEdge("q", "think", 1.0))));
  }

  /** Open fork-join: src -> fj(2 branches) -> sink. */
  private NetworkModel forkNet() {
    return new NetworkModel("fork",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
                new ForkJoinNode("fj", "fork-join",
                    List.of(new Branch(Map.of("web", new ServiceSpec(exp(4.0)))),
                            new Branch(Map.of("web", new ServiceSpec(exp(8.0))))),
                    "all"),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "fj", null), new RoutingEdge("fj", "snk", null))));
  }

  @Test
  void closedUserClassHasCustomersAndReference() throws Exception {
    Document doc = writer.toDocument(closedNet(), stopping(), 7L, List.of());
    XPath xp = XPathFactory.newInstance().newXPath();
    assertEquals("closed", xp.evaluate("/sim/userClass/@type", doc));
    assertEquals("15", xp.evaluate("/sim/userClass/@customers", doc));
    assertEquals("think", xp.evaluate("/sim/userClass/@referenceSource", doc));
  }

  @Test
  void delayNodeHasDelaySection() throws Exception {
    Document doc = writer.toDocument(closedNet(), stopping(), 7L, List.of());
    XPath xp = XPathFactory.newInstance().newXPath();
    assertEquals("1", xp.evaluate("count(/sim/node[@name='think']/section[@className='Delay'])", doc));
    assertDoesNotThrow(() -> writer.validate(doc));
  }

  @Test
  void forkJoinExpandsToForkBranchesAndJoin() throws Exception {
    Document doc = writer.toDocument(forkNet(), stopping(), 7L, List.of());
    XPath xp = XPathFactory.newInstance().newXPath();
    // fork node + 2 branch stations + join node + src + sink = 5 nodes
    assertEquals("5", xp.evaluate("count(/sim/node)", doc));
    assertEquals("1", xp.evaluate("count(/sim/node[@name='fj']/section[@className='Fork'])", doc));
    assertEquals("1", xp.evaluate("count(/sim/node[@name='fj__join']/section[@className='Join'])", doc));
    assertEquals("1", xp.evaluate("count(/sim/node[@name='fj__b0'])", doc));
    assertEquals("1", xp.evaluate("count(/sim/node[@name='fj__b1'])", doc));
    // external edge src->fj stays; internal fork->branch->join added
    assertEquals("1", xp.evaluate("count(/sim/connection[@source='src'][@target='fj'])", doc));
    assertEquals("1", xp.evaluate("count(/sim/connection[@source='fj'][@target='fj__b0'])", doc));
    assertEquals("1", xp.evaluate("count(/sim/connection[@source='fj__b0'][@target='fj__join'])", doc));
    // edge out of fj originates from the join
    assertEquals("1", xp.evaluate("count(/sim/connection[@source='fj__join'][@target='snk'])", doc));
    assertDoesNotThrow(() -> writer.validate(doc));
  }
}
```

- [ ] **Step 2: Run — expect failure** (the stubs throw)

Run: `mvn -q test -Dtest=JsimgWriterClosedForkTest`
Expected: FAIL.

- [ ] **Step 3: Implement closed `userClass`**

Replace the `writeClosedUserClass` stub in `JsimgWriter`:

```java
  @Override
  protected void writeClosedUserClass(Element sim, NetworkModel model, JobClass c) {
    String ref = c.referenceStation() != null ? c.referenceStation() : defaultReference(model, c.name());
    Xml.child(sim, "userClass",
        "name", c.name(), "type", "closed", "priority", "0",
        "customers", c.population().toString(),
        "referenceSource", ref);
  }

  /** §5.3: default to the class's delay node if present, else its first routed station. */
  private String defaultReference(NetworkModel model, String className) {
    for (Node n : model.nodes()) {
      if (n instanceof DelayNode d && d.service().containsKey(className)) {
        return d.name();
      }
    }
    var edges = model.routing() == null ? List.<RoutingEdge>of()
        : model.routing().getOrDefault(className, List.of());
    if (!edges.isEmpty()) {
      return edges.get(0).from();
    }
    throw new ValidationException(ValidationException.Kind.UNPROCESSABLE,
        List.of("cannot determine referenceStation for closed class '" + className + "'"));
  }
```

Remove `protected` abstract-style `throw` body — the method now has a concrete implementation. (Keep the method non-final so the open-only reasoning in Task 7 still reads correctly; both node/class hooks are now real.)

- [ ] **Step 4: Implement delay + fork-join nodes**

Replace the `writeNonOpenNode` stub:

```java
  @Override
  protected void writeNonOpenNode(Element sim, Element node, NetworkModel model, Node n) {
    if (n instanceof DelayNode d) {
      writeDelayNode(node, d);
    } else if (n instanceof ForkJoinNode fj) {
      writeForkJoin(sim, node, fj);
    } else {
      throw new ValidationException(ValidationException.Kind.UNPROCESSABLE,
          List.of("unknown node type '" + n.type() + "'"));
    }
  }

  private void writeDelayNode(Element node, DelayNode d) {
    // Queue (infinite) + Delay (infinite server) + Router.
    Element queue = Xml.child(node, "section", "className", "Queue");
    Xml.textEl(Xml.child(queue, "parameter", "classPath", "java.lang.Integer", "name", "size"),
        "value", "-1");
    Xml.textEl(Xml.child(queue, "parameter", "classPath", "java.lang.String", "name", "dropStrategy"),
        "value", "waiting queue");
    Element delay = Xml.child(node, "section", "className", "Delay");
    Element svc = Xml.child(delay, "parameter",
        "classPath", "jmt.engine.NetStrategies.ServiceStrategy", "name", "ServiceStrategy", "array", "true");
    for (Map.Entry<String, ServiceSpec> e : d.service().entrySet()) {
      writeServiceStrategyEntry(svc, e.getKey(), e.getValue().distribution());
    }
    writeRouterForNode(node, currentModel, node.getAttribute("name"));
  }
```

> **Note on `currentModel`:** `writeRouter` needs the model, but `writeNonOpenNode` receives it as a parameter. To avoid a field, change the delay branch to pass `model` explicitly. Rename `writeRouter(Element,NetworkModel,String)` usage so the delay path calls `writeRouter(node, model, n.name())`. Adjust the signature of `writeNonOpenNode` implementation to close over the `model` argument it already has (it does — `writeForkJoin` and `writeDelayNode` should take `model` too). Concretely, make `writeDelayNode(Element node, NetworkModel model, DelayNode d)` and call `writeRouter(node, model, d.name())`; drop the `currentModel`/`writeRouterForNode` placeholder. This keeps the writer field-free and thread-safe.

Corrected delay + fork-join implementation:

```java
  @Override
  protected void writeNonOpenNode(Element sim, Element node, NetworkModel model, Node n) {
    if (n instanceof DelayNode d) {
      writeDelayNode(node, model, d);
    } else if (n instanceof ForkJoinNode fj) {
      writeForkJoin(sim, node, model, fj);
    } else {
      throw new ValidationException(ValidationException.Kind.UNPROCESSABLE,
          List.of("unknown node type '" + n.type() + "'"));
    }
  }

  private void writeDelayNode(Element node, NetworkModel model, DelayNode d) {
    Element queue = Xml.child(node, "section", "className", "Queue");
    Xml.textEl(Xml.child(queue, "parameter", "classPath", "java.lang.Integer", "name", "size"), "value", "-1");
    Xml.textEl(Xml.child(queue, "parameter", "classPath", "java.lang.String", "name", "dropStrategy"),
        "value", "waiting queue");
    Element delay = Xml.child(node, "section", "className", "Delay");
    Element svc = Xml.child(delay, "parameter",
        "classPath", "jmt.engine.NetStrategies.ServiceStrategy", "name", "ServiceStrategy", "array", "true");
    for (Map.Entry<String, ServiceSpec> e : d.service().entrySet()) {
      writeServiceStrategyEntry(svc, e.getKey(), e.getValue().distribution());
    }
    writeRouter(node, model, d.name());
  }

  static String branchStationName(String forkNode, int index) { return forkNode + "__b" + index; }
  static String joinStationName(String forkNode) { return forkNode + "__join"; }

  private void writeForkJoin(Element sim, Element forkNodeEl, NetworkModel model, ForkJoinNode fj) {
    // Fork node sections: Queue + ServiceTunnel + Fork.
    Element queue = Xml.child(forkNodeEl, "section", "className", "Queue");
    Xml.textEl(Xml.child(queue, "parameter", "classPath", "java.lang.Integer", "name", "size"), "value", "-1");
    Xml.textEl(Xml.child(queue, "parameter", "classPath", "java.lang.String", "name", "dropStrategy"),
        "value", "waiting queue");
    Xml.child(forkNodeEl, "section", "className", "ServiceTunnel");
    Element fork = Xml.child(forkNodeEl, "section", "className", "Fork");
    Xml.textEl(Xml.child(fork, "parameter", "classPath", "java.lang.Integer", "name", "jobsPerLink"), "value", "1");
    Xml.textEl(Xml.child(fork, "parameter", "classPath", "java.lang.Integer", "name", "block"), "value", "-1");
    Xml.textEl(Xml.child(fork, "parameter", "classPath", "java.lang.Boolean", "name", "isSimplifiedFork"),
        "value", "true");

    // Branch server stations.
    for (int i = 0; i < fj.branches().size(); i++) {
      Branch b = fj.branches().get(i);
      Element bnode = Xml.child(sim, "node", "name", branchStationName(fj.name(), i));
      Element bqueue = Xml.child(bnode, "section", "className", "Queue");
      Xml.textEl(Xml.child(bqueue, "parameter", "classPath", "java.lang.Integer", "name", "size"), "value", "-1");
      Xml.textEl(Xml.child(bqueue, "parameter", "classPath", "java.lang.String", "name", "dropStrategy"),
          "value", "waiting queue");
      Element server = Xml.child(bnode, "section", "className", "Server");
      Xml.textEl(Xml.child(server, "parameter", "classPath", "java.lang.Integer", "name", "maxJobs"), "value", "1");
      Xml.textEl(Xml.child(server, "parameter", "classPath", "java.lang.Integer", "name", "numberOfVisits"), "value", "0");
      Element svc = Xml.child(server, "parameter",
          "classPath", "jmt.engine.NetStrategies.ServiceStrategy", "name", "ServiceStrategy", "array", "true");
      for (Map.Entry<String, ServiceSpec> e : b.service().entrySet()) {
        writeServiceStrategyEntry(svc, e.getKey(), e.getValue().distribution());
      }
      // Branch routes straight to the join (single edge → RandomStrategy).
      Element router = Xml.child(bnode, "section", "className", "Router");
      Element rp = Xml.child(router, "parameter",
          "classPath", "jmt.engine.NetStrategies.RoutingStrategy", "name", "RoutingStrategy", "array", "true");
      for (JobClass c : model.classes()) {
        Element rc = Xml.child(rp, "refClass");
        rc.appendChild(rc.getOwnerDocument().createTextNode(c.name()));
        Xml.child(rp, "subParameter",
            "classPath", "jmt.engine.NetStrategies.RoutingStrategies.RandomStrategy", "name", "Random");
      }
      Xml.child(sim, "connection", "source", fj.name(), "target", branchStationName(fj.name(), i));
      Xml.child(sim, "connection", "source", branchStationName(fj.name(), i), "target", joinStationName(fj.name()));
    }

    // Join node: Join + ServiceTunnel + Router.
    Element joinNode = Xml.child(sim, "node", "name", joinStationName(fj.name()));
    Element join = Xml.child(joinNode, "section", "className", "Join");
    Element jp = Xml.child(join, "parameter",
        "classPath", "jmt.engine.NetStrategies.JoinStrategy", "name", "JoinStrategy", "array", "true");
    for (JobClass c : model.classes()) {
      Element rc = Xml.child(jp, "refClass");
      rc.appendChild(rc.getOwnerDocument().createTextNode(c.name()));
      Element ns = Xml.child(jp, "subParameter",
          "classPath", "jmt.engine.NetStrategies.JoinStrategies.NormalJoin", "name", "Standard Join");
      Xml.textEl(Xml.child(ns, "subParameter", "classPath", "java.lang.Integer", "name", "numRequired"),
          "value", "-1"); // -1 = wait for all branches ("all")
    }
    Xml.child(joinNode, "section", "className", "ServiceTunnel");
    writeRouter(joinNode, model, joinStationName(fj.name()));
  }
```

Then in `writeConnections`, external edges into/out of a fork-join node must be redirected: an edge `X → fj` stays as `X → fj` (fork node keeps the domain name); an edge `fj → Y` must become `fj__join → Y`. Update `writeConnections`:

```java
  private void writeConnections(Element sim, NetworkModel model) {
    var forkJoinNames = new java.util.HashSet<String>();
    for (Node n : model.nodes()) {
      if (n instanceof ForkJoinNode) forkJoinNames.add(n.name());
    }
    var seen = new java.util.LinkedHashSet<String>();
    if (model.routing() != null) {
      for (List<RoutingEdge> edges : model.routing().values()) {
        for (RoutingEdge e : edges) {
          String source = forkJoinNames.contains(e.from()) ? joinStationName(e.from()) : e.from();
          String target = e.to(); // into a fork-join lands on the fork node, which keeps the domain name
          if (seen.add(source + " " + target)) {
            Xml.child(sim, "connection", "source", source, "target", target);
          }
        }
      }
    }
  }
```

And routing *out of* the domain fork-join name is emitted on the **join** node's router, not the fork node. In `writeRouter`, when the node being routed is a fork node the outgoing edges use the domain name as `fromNode`; but the join node's router is what actually forwards. Handle by having the join's `writeRouter(joinNode, model, joinStationName(fj.name()))` call fall through to `outEdges(model, class, fj.name())` — i.e. `writeRouter` must map a join station name back to its fork's domain name when looking up out-edges. Add:

```java
  private void writeRouter(Element node, NetworkModel model, String fromNode) {
    String routingKey = fromNode.endsWith("__join")
        ? fromNode.substring(0, fromNode.length() - "__join".length())
        : fromNode;
    Element section = Xml.child(node, "section", "className", "Router");
    // ... unchanged, but call outEdges(model, c.name(), routingKey) instead of fromNode
```

> **Verification gate:** the exact `Fork`/`Join`/`isSimplifiedFork` parameter block is confirmed against `open_1class_3stat_fork.jsimg` (see [[jmt-engine-facts]]). Extract its `<sim>` the same way as Task 2 (`fork.sim.xml`) and diff the writer output against it during Step 5; the XSD test plus this diff are the gates. Adjust parameter names/`numRequired` semantics to match the template if they differ.

- [ ] **Step 5: Run — expect pass (iterate against `fork.sim.xml`)**

Run: `mvn -q test -Dtest=JsimgWriterClosedForkTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/qsim/translate/JsimgWriter.java src/test/java/qsim/translate/JsimgWriterClosedForkTest.java
git commit -m "feat: JSIMG writer support for delay, fork-join, and closed classes"
```

---

### Task 9: JMT engine wrapper (the quarantine boundary)

The single class permitted to import `jmt.*`. Wraps `jmt.engine.simDispatcher.DispatcherJSIMschema` (see [[jmt-engine-facts]]): write the model to a temp file, set seed / wall-clock watchdog / terminal flag, `solveModel()`, hand back the output file and elapsed wall-clock. Each run constructs a **fresh** dispatcher (spec §4 residual note: clean engine state per run; subprocess fallback deferred unless a leak is observed — documented in self-review).

**Files:**
- Create: `src/main/java/qsim/engine/package-info.java` (GPL header + one-line note: "the only package that imports jmt.*"), `src/main/java/qsim/engine/JmtRunner.java`, `src/main/java/qsim/engine/RunResult.java`
- Test: `src/test/java/qsim/engine/JmtRunnerTest.java`
- Test resource: `src/test/resources/models/mm1.sim.xml` (the bare `<sim>` extracted in Task 2).

**Interfaces:**
- Consumes: a JSIMG XML string (produced by `qsim.translate.JsimgWriter`), a `qsim.model.Stopping` (for seed/wall-clock — passed decomposed to avoid a translate→engine dependency direction issue; pass primitives).
- Produces:
  - `RunResult(java.io.File outputFile, double wallClockSeconds)`.
  - `JmtRunner.run(String jsimgXml, long seed, Integer maxWallClockSeconds, boolean terminal): RunResult` — writes `jsimgXml` to a temp `.xml`, runs the engine, returns the JMT output file. Throws `qsim.engine.EngineException` (a plain `RuntimeException` subclass carrying the JMT message) on `solveModel()` failure.
  - `JmtRunner.cleanup(RunResult): void` — deletes the temp model + output files (called by the service after parsing).

- [ ] **Step 1: Write the failing test**

`src/test/java/qsim/engine/JmtRunnerTest.java`:

```java
package qsim.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JmtRunnerTest {

  private String mm1Xml() throws Exception {
    try (var in = getClass().getResourceAsStream("/models/mm1.sim.xml")) {
      return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  @Test
  void runsModelAndProducesSolutionsFile() throws Exception {
    JmtRunner runner = new JmtRunner();
    RunResult r = runner.run(mm1Xml(), 12345L, 60, true);
    assertTrue(r.outputFile().exists(), "engine must produce an output file");
    String out = Files.readString(Path.of(r.outputFile().toURI()));
    assertTrue(out.contains("<measure"), "output must contain measures");
    assertTrue(r.wallClockSeconds() >= 0.0);
    runner.cleanup(r);
  }

  @Test
  void identicalSeedProducesIdenticalMeasures() throws Exception {
    JmtRunner runner = new JmtRunner();
    RunResult a = runner.run(mm1Xml(), 999L, 60, true);
    RunResult b = runner.run(mm1Xml(), 999L, 60, true);
    String outA = Files.readString(Path.of(a.outputFile().toURI()));
    String outB = Files.readString(Path.of(b.outputFile().toURI()));
    // meanValue attributes must match bit-for-bit for the same seed
    assertEquals(meanValues(outA), meanValues(outB), "same seed must be deterministic");
    runner.cleanup(a);
    runner.cleanup(b);
  }

  private static java.util.List<String> meanValues(String xml) {
    var matcher = java.util.regex.Pattern.compile("meanValue=\"([^\"]*)\"").matcher(xml);
    var out = new java.util.ArrayList<String>();
    while (matcher.find()) out.add(matcher.group(1));
    return out;
  }
}
```

(Add `import static org.junit.jupiter.api.Assertions.assertEquals;`.)

- [ ] **Step 2: Run — expect compile failure**

Run: `mvn -q test -Dtest=JmtRunnerTest`
Expected: FAIL.

- [ ] **Step 3: Implement `RunResult` and `EngineException`**

`src/main/java/qsim/engine/RunResult.java`:

```java
package qsim.engine;
import java.io.File;
public record RunResult(File outputFile, double wallClockSeconds) {}
```

`src/main/java/qsim/engine/EngineException.java`:

```java
package qsim.engine;
public class EngineException extends RuntimeException {
  public EngineException(String message, Throwable cause) { super(message, cause); }
}
```

- [ ] **Step 4: Implement `JmtRunner`**

`src/main/java/qsim/engine/JmtRunner.java`:

```java
package qsim.engine;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import jmt.engine.simDispatcher.DispatcherJSIMschema;

/** The ONLY class in the service that imports jmt.* — the licensing/quarantine boundary. */
public class JmtRunner {

  public RunResult run(String jsimgXml, long seed, Integer maxWallClockSeconds, boolean terminal) {
    File model = null;
    try {
      model = File.createTempFile("qsim-model-", ".xml");
      Files.writeString(model.toPath(), jsimgXml, StandardCharsets.UTF_8);

      DispatcherJSIMschema dispatcher = new DispatcherJSIMschema(model);
      dispatcher.setSimulationSeed(seed);
      dispatcher.setTerminalSimulation(terminal);
      if (maxWallClockSeconds != null && maxWallClockSeconds > 0) {
        dispatcher.setSimulationMaxDuration((long) maxWallClockSeconds * 1000L);
      }

      long start = System.nanoTime();
      boolean ok = dispatcher.solveModel();
      double elapsed = (System.nanoTime() - start) / 1_000_000_000.0;
      if (!ok) {
        throw new EngineException("JMT solveModel() returned false for model " + model.getName(), null);
      }
      File output = dispatcher.getOutputFile();
      if (output == null || !output.exists()) {
        throw new EngineException("JMT produced no output file", null);
      }
      // The model temp file is no longer needed; the output file is returned.
      model.delete();
      return new RunResult(output, elapsed);
    } catch (EngineException e) {
      if (model != null) model.delete();
      throw e;
    } catch (Exception e) {
      if (model != null) model.delete();
      throw new EngineException("JMT engine failed: " + e.getMessage(), e);
    }
  }

  public void cleanup(RunResult result) {
    if (result != null && result.outputFile() != null) {
      result.outputFile().delete();
    }
  }
}
```

> **Note on `setTerminalSimulation`:** spec §4 specifies `true`. If the M/M/1 golden check in Task 13 shows the mean response time is systematically wrong (e.g. transient rather than steady-state), flip to `false` and re-run — this is the single verification the spec flags. Wire it as the `terminal` parameter so the flip is a one-line change in the service (Task 11), not here.
>
> **Note on the wall-clock watchdog:** `setSimulationMaxDuration` spawns a thread that calls `abortAllMeasures()` after the timeout, yielding *graceful partial results* (not a hard kill). So a timed-out run still returns an output file with some measures marked `successful="false"`. `completed` in the response is derived by the parser (Task 10) from whether all requested measures succeeded — not from a timeout flag here.

- [ ] **Step 5: Run — expect pass**

Run: `mvn -q test -Dtest=JmtRunnerTest`
Expected: PASS. (This is the first test that actually executes the JMT engine end-to-end from the wrapper; it confirms the quarantine boundary works and determinism holds.)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/qsim/engine/ src/test/java/qsim/engine/JmtRunnerTest.java
git commit -m "feat: JMT engine wrapper (quarantined jmt.* boundary) with seed + watchdog"
```

---

### Task 10: Solutions parser (JMT results XML → domain measures)

Parse the engine's `<solutions>/<measure ...>` output (see [[jmt-engine-facts]]: attributes `station class measureType meanValue lowerLimit upperLimit successful analyzedSamples discardedSamples precision alfa maxSamples nodeType variance standardDeviation`; note the `alfa` misspelling and string-typed `variance`/`standardDeviation`) into `List<MeasureResult>`, plus derive the top-level `completed` flag. No `jmt.*` imports — pure DOM over the file; lives in package `qsim.result` per the File Structure.

**Reverse mappings applied:**
- JMT `measureType` string → domain type (inverse of Task 6's `MeasureMapper`).
- `alfa` (JMT confidence) → domain `alpha` significance: `alpha = round(1 - alfa)` to the requested precision.
- Fork-join branch/join station names (`fj__b0`, `fj__join`) → the domain fork-join node name `fj` (strip the `__b<N>` / `__join` suffix).

**Files:**
- Create: `src/main/java/qsim/result/SolutionsParser.java`
- Test: `src/test/java/qsim/result/SolutionsParserTest.java`
- Test resource: `src/test/resources/results/mm1.solutions.xml` — a small hand-written sample matching `SIMmodeloutput.xsd` (created in this task's Step 1).

**Interfaces:**
- Consumes: a JMT output `java.io.File`.
- Produces:
  - `SolutionsParser.parse(java.io.File output): Parsed` where `Parsed(List<MeasureResult> measures, boolean completed)`.
  - `record Parsed(List<qsim.model.MeasureResult> measures, boolean completed)`.

- [ ] **Step 1: Write the failing test + sample resource**

`src/test/resources/results/mm1.solutions.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<solutions modelName="mm1" solutionMethod="simulation">
  <measure measureType="Utilization" station="q" class="web"
           meanValue="0.5012" lowerLimit="0.4901" upperLimit="0.5123"
           successful="true" analyzedSamples="45000" discardedSamples="1200"
           precision="0.0221" alfa="0.99" maxSamples="1000000" nodeType="station"
           variance="0.0011" standardDeviation="0.0331"/>
  <measure measureType="Response Time" station="fj__join" class="web"
           meanValue="0.83" lowerLimit="0.79" upperLimit="0.87"
           successful="false" analyzedSamples="1000000" discardedSamples="0"
           precision="0.12" alfa="0.99" maxSamples="1000000" nodeType="station"
           variance="0.04" standardDeviation="0.2"/>
</solutions>
```

`src/test/java/qsim/result/SolutionsParserTest.java`:

```java
package qsim.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.File;
import java.net.URL;
import org.junit.jupiter.api.Test;
import qsim.model.MeasureResult;

class SolutionsParserTest {

  private File resource(String path) throws Exception {
    URL u = getClass().getResource(path);
    return new File(u.toURI());
  }

  @Test
  void parsesMeasuresAndMapsTypeAndStation() throws Exception {
    SolutionsParser.Parsed p = new SolutionsParser().parse(resource("/results/mm1.solutions.xml"));
    assertEquals(2, p.measures().size());

    MeasureResult u = p.measures().get(0);
    assertEquals("q", u.station());
    assertEquals("web", u.jobClass());
    assertEquals("utilization", u.type());        // reverse-mapped from "Utilization"
    assertEquals(0.5012, u.mean());
    assertEquals(0.4901, u.lower());
    assertEquals(true, u.success());
    assertEquals(45000, u.samplesAnalyzed());
    assertEquals(0.05, u.alpha());                // 1 - 0.99 (confidence -> significance)

    MeasureResult rt = p.measures().get(1);
    assertEquals("fj", rt.station());             // fj__join -> fj
    assertEquals("response-time", rt.type());
  }

  @Test
  void completedFalseWhenAnyMeasureUnsuccessful() throws Exception {
    SolutionsParser.Parsed p = new SolutionsParser().parse(resource("/results/mm1.solutions.xml"));
    assertFalse(p.completed());                   // second measure has successful="false"
  }
}
```

- [ ] **Step 2: Run — expect compile failure**

Run: `mvn -q test -Dtest=SolutionsParserTest`
Expected: FAIL.

- [ ] **Step 3: Implement `SolutionsParser`**

`src/main/java/qsim/result/SolutionsParser.java`:

```java
package qsim.result;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import qsim.model.MeasureResult;

public class SolutionsParser {

  public record Parsed(List<MeasureResult> measures, boolean completed) {}

  // Inverse of MeasureMapper's registry (JMT measureType string -> domain type).
  private static final Map<String, String> REVERSE = Map.ofEntries(
      Map.entry("Response Time", "response-time"),
      Map.entry("Residence Time", "residence-time"),
      Map.entry("Queue Time", "queue-time"),
      Map.entry("Number of Customers", "queue-length"),
      Map.entry("Utilization", "utilization"),
      Map.entry("Throughput", "throughput"),
      Map.entry("Drop Rate", "drop-rate"),
      Map.entry("System Response Time", "system-response-time"));

  public Parsed parse(File output) {
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      Document doc = f.newDocumentBuilder().parse(output);
      NodeList measures = doc.getElementsByTagName("measure");
      List<MeasureResult> results = new ArrayList<>();
      boolean completed = true;
      for (int i = 0; i < measures.getLength(); i++) {
        Element m = (Element) measures.item(i);
        boolean success = Boolean.parseBoolean(m.getAttribute("successful"));
        completed &= success;
        results.add(new MeasureResult(
            domainStation(m.getAttribute("station")),
            m.getAttribute("class"),
            REVERSE.getOrDefault(m.getAttribute("measureType"), m.getAttribute("measureType")),
            parseD(m.getAttribute("meanValue")),
            parseD(m.getAttribute("lowerLimit")),
            parseD(m.getAttribute("upperLimit")),
            significance(m.getAttribute("alfa")),
            parseD(m.getAttribute("precision")),
            success,
            parseI(m.getAttribute("analyzedSamples")),
            parseI(m.getAttribute("discardedSamples")),
            parseD(m.getAttribute("variance")),
            parseD(m.getAttribute("standardDeviation"))));
      }
      return new Parsed(results, completed);
    } catch (Exception e) {
      throw new IllegalStateException("cannot parse JMT solutions file " + output, e);
    }
  }

  /** Map an expanded fork-join station name back to its domain node name. */
  static String domainStation(String station) {
    if (station == null) return null;
    int join = station.indexOf("__join");
    if (join >= 0) return station.substring(0, join);
    int branch = station.indexOf("__b");
    if (branch >= 0) return station.substring(0, branch);
    return station;
  }

  private static Double significance(String alfa) {
    Double conf = parseD(alfa);
    if (conf == null) return null;
    // round 1 - confidence to 6 dp to shed float noise (0.99 -> 0.01, 0.95 -> 0.05)
    return Math.round((1.0 - conf) * 1_000_000.0) / 1_000_000.0;
  }

  private static Double parseD(String s) {
    if (s == null || s.isBlank()) return null;
    try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
  }

  private static Integer parseI(String s) {
    if (s == null || s.isBlank()) return null;
    try { return (int) Math.round(Double.parseDouble(s)); } catch (NumberFormatException e) { return null; }
  }
}
```

- [ ] **Step 4: Run — expect pass**

Run: `mvn -q test -Dtest=SolutionsParserTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/qsim/result/SolutionsParser.java src/test/java/qsim/result/SolutionsParserTest.java src/test/resources/results/mm1.solutions.xml
git commit -m "feat: parse JMT solutions XML into domain measures with reverse mappings"
```

---

### Task 11: Orchestration service

Wire the pipeline end to end: fill stopping defaults from `Config`, resolve the effective seed, validate the contract, map measures, write + XSD-validate the JSIMG, run the engine, parse the results, clean up temp files, assemble the `SimulationResponse`. This is the single call the HTTP handler makes.

**Files:**
- Create: `src/main/java/qsim/http/Config.java`, `src/main/java/qsim/http/SimulationService.java`
- Test: `src/test/java/qsim/http/SimulationServiceTest.java`

**Interfaces:**
- Consumes: `qsim.model.*`, `qsim.contract.ContractValidator`, `qsim.translate.{JsimgWriter, MeasureMapper}`, `qsim.engine.JmtRunner`, `qsim.result.SolutionsParser`.
- Produces:
  - `Config(int port, String tempDir, double defaultAlpha, double defaultPrecision, int defaultMinSamples, int defaultMaxSamples, int defaultMaxWallClockSeconds)` with `static Config fromEnv()` (Task 12 uses `fromEnv`; tests use the canonical `Config.defaults()`).
  - `Config.defaults(): Config` — `port=8080`, `tempDir=System.getProperty("java.io.tmpdir")`, `alpha=0.05`, `precision=0.05`, `minSamples=10000`, `maxSamples=1000000`, `maxWallClockSeconds=120`.
  - `SimulationService(Config config, JmtRunner runner)` and a convenience `SimulationService(Config config)` (constructs a `JmtRunner`).
  - `SimulationService.simulate(SimulationRequest req): SimulationResponse` — orchestrates; propagates `ValidationException` (→ handler maps to 400/422) and `EngineException` (→ 500).
  - `SimulationService.effectiveStopping(Stopping requested): Stopping` — nulls filled from `config`.
  - `SimulationService.effectiveSeed(Long requested): long` — `requested` if non-null, else a fresh random seed (echoed back in the response so the caller can reproduce the run).

- [ ] **Step 1: Write the failing test** (end-to-end M/M/1 through the real engine)

`src/test/java/qsim/http/SimulationServiceTest.java`:

```java
package qsim.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qsim.model.*;

class SimulationServiceTest {

  private Distribution exp(double r) { return new Distribution("exponential", r, null, null, null); }

  /** M/M/1, lambda=1, mu=2 -> rho = U = 0.5. */
  private SimulationRequest mm1() {
    NetworkModel m = new NetworkModel("mm1",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
                new QueueNode("q", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(2.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));
    Stopping s = new Stopping(0.05, 0.03, 10000, 500_000, null, null, 60, false);
    return new SimulationRequest(m, 12345L, s, List.of("utilization", "response-time"));
  }

  @Test
  void runsEndToEndAndEchoesRequestMetadata() {
    SimulationService service = new SimulationService(Config.defaults());
    SimulationResponse resp = service.simulate(mm1());

    assertEquals("mm1", resp.modelName());
    assertEquals("simulation", resp.solutionMethod());
    assertEquals(12345L, resp.seed());
    assertTrue(resp.wallClockSeconds() >= 0.0);
    assertFalse(resp.measures().isEmpty());
    // every measure references a domain node name (never an internal fj__ name)
    assertTrue(resp.measures().stream().allMatch(mr -> !mr.station().contains("__")));
  }

  @Test
  void utilizationBracketsHalf() {
    SimulationService service = new SimulationService(Config.defaults());
    SimulationResponse resp = service.simulate(mm1());
    MeasureResult u = resp.measures().stream()
        .filter(mr -> mr.type().equals("utilization") && mr.station().equals("q"))
        .findFirst().orElseThrow();
    // U = rho = 0.5; the CI must bracket it (sanity gate; full golden checks in Task 13)
    assertTrue(u.lower() <= 0.5 && 0.5 <= u.upper(),
        "U CI [" + u.lower() + "," + u.upper() + "] must bracket 0.5");
  }

  @Test
  void missingSeedIsGeneratedAndEchoed() {
    SimulationService service = new SimulationService(Config.defaults());
    SimulationRequest noSeed = new SimulationRequest(mm1().model(), null, mm1().stopping(),
        List.of("utilization"));
    SimulationResponse resp = service.simulate(noSeed);
    assertTrue(resp.seed() != null, "service must generate and echo a seed when none is supplied");
  }
}
```

- [ ] **Step 2: Run — expect compile failure**

Run: `mvn -q test -Dtest=SimulationServiceTest`
Expected: FAIL.

- [ ] **Step 3: Implement `Config`**

`src/main/java/qsim/http/Config.java`:

```java
package qsim.http;

public record Config(int port, String tempDir, double defaultAlpha, double defaultPrecision,
                     int defaultMinSamples, int defaultMaxSamples, int defaultMaxWallClockSeconds) {

  public static Config defaults() {
    return new Config(8080, System.getProperty("java.io.tmpdir"),
        0.05, 0.05, 10_000, 1_000_000, 120);
  }

  public static Config fromEnv() {
    Config d = defaults();
    return new Config(
        envInt("QSIM_PORT", d.port()),
        env("QSIM_TEMP_DIR", d.tempDir()),
        envDouble("QSIM_DEFAULT_ALPHA", d.defaultAlpha()),
        envDouble("QSIM_DEFAULT_PRECISION", d.defaultPrecision()),
        envInt("QSIM_DEFAULT_MIN_SAMPLES", d.defaultMinSamples()),
        envInt("QSIM_DEFAULT_MAX_SAMPLES", d.defaultMaxSamples()),
        envInt("QSIM_DEFAULT_MAX_WALLCLOCK_SECONDS", d.defaultMaxWallClockSeconds()));
  }

  private static String env(String k, String dflt) {
    String v = System.getenv(k);
    return v == null || v.isBlank() ? dflt : v;
  }
  private static int envInt(String k, int dflt) {
    String v = System.getenv(k);
    return v == null || v.isBlank() ? dflt : Integer.parseInt(v.trim());
  }
  private static double envDouble(String k, double dflt) {
    String v = System.getenv(k);
    return v == null || v.isBlank() ? dflt : Double.parseDouble(v.trim());
  }
}
```

- [ ] **Step 4: Implement `SimulationService`**

`src/main/java/qsim/http/SimulationService.java`:

```java
package qsim.http;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import qsim.contract.ContractValidator;
import qsim.engine.JmtRunner;
import qsim.engine.RunResult;
import qsim.model.*;
import qsim.result.SolutionsParser;
import qsim.translate.JsimgWriter;
import qsim.translate.MeasureMapper;
import qsim.translate.MeasureSpec;

public class SimulationService {

  private final Config config;
  private final JmtRunner runner;
  private final ContractValidator validator = new ContractValidator();
  private final MeasureMapper measureMapper = new MeasureMapper();
  private final JsimgWriter writer = new JsimgWriter();
  private final SolutionsParser parser = new SolutionsParser();

  public SimulationService(Config config, JmtRunner runner) {
    this.config = config;
    this.runner = runner;
  }

  public SimulationService(Config config) {
    this(config, new JmtRunner());
  }

  public SimulationResponse simulate(SimulationRequest req) {
    validator.validate(req);

    Stopping stopping = effectiveStopping(req.stopping());
    long seed = effectiveSeed(req.seed());
    List<MeasureSpec> measures = measureMapper.map(req.model().measures() == null
        ? null : null, req.measures()); // measures come from the request, not the model
    // NOTE: measure list is at the request level (SimulationRequest.measures), so:
    measures = measureMapper.map(req.model(), req.measures());

    var doc = writer.toDocument(req.model(), stopping, seed, measures);
    writer.validate(doc); // XSD gate -> ValidationException(UNPROCESSABLE) on failure
    String xml = qsim.translate.Xml.serialize(doc);

    RunResult run = runner.run(xml, seed, stopping.maxWallClockSeconds(), /* terminal */ true);
    try {
      SolutionsParser.Parsed parsed = parser.parse(run.outputFile());
      return new SimulationResponse(req.model().name(), "simulation", seed,
          run.wallClockSeconds(), parsed.completed(), parsed.measures());
    } finally {
      runner.cleanup(run);
    }
  }

  Stopping effectiveStopping(Stopping s) {
    if (s == null) {
      return new Stopping(config.defaultAlpha(), config.defaultPrecision(),
          config.defaultMinSamples(), config.defaultMaxSamples(),
          null, null, config.defaultMaxWallClockSeconds(), false);
    }
    return new Stopping(
        s.alpha() != null ? s.alpha() : config.defaultAlpha(),
        s.precision() != null ? s.precision() : config.defaultPrecision(),
        s.minSamples() != null ? s.minSamples() : config.defaultMinSamples(),
        s.maxSamples() != null ? s.maxSamples() : config.defaultMaxSamples(),
        s.maxSimulatedTime(),
        s.maxEvents(),
        s.maxWallClockSeconds() != null ? s.maxWallClockSeconds() : config.defaultMaxWallClockSeconds(),
        s.disableStatisticStop() != null ? s.disableStatisticStop() : false);
  }

  long effectiveSeed(Long requested) {
    return requested != null ? requested : ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
  }
}
```

> **Fix the measure-mapping line before running:** the scratch line calling `measureMapper.map(... ? null : null, ...)` above is wrong on purpose to flag that `measures` lives on `SimulationRequest`, not `NetworkModel`. Delete it; keep only `measures = measureMapper.map(req.model(), req.measures());` and declare `List<MeasureSpec> measures =` on that line. (Left visible so the implementer removes it deliberately rather than copying a bug.)

- [ ] **Step 5: Run — expect pass**

Run: `mvn -q test -Dtest=SimulationServiceTest`
Expected: PASS. `utilizationBracketsHalf` may occasionally sit at the CI edge; if it flakes, it means `setTerminalSimulation(true)` gives transient-biased means — see Task 9's note and Task 13's golden gate, and flip the `terminal` argument to `false`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/qsim/http/Config.java src/main/java/qsim/http/SimulationService.java src/test/java/qsim/http/SimulationServiceTest.java
git commit -m "feat: orchestration service (validate -> translate -> run -> parse)"
```

---

### Task 12: HTTP frontend + error mapping

Expose the service over `com.sun.net.httpserver`: `POST /simulate` and `GET /health`, with the §7 error table mapped to status codes. `App.main` sets headless mode, builds `Config.fromEnv()`, starts the server (executor with a single worker — concurrency 1 per §4).

**§7 error mapping (implemented in `SimulationHandler`):**

| Cause | Status |
|-------|--------|
| Jackson parse/bind failure (malformed JSON, unknown node `type`, wrong value type) | 400 |
| `ValidationException(BAD_REQUEST)` (unsupported measure type) | 400 |
| `ValidationException(UNPROCESSABLE)` (semantic model error, XSD failure) | 422 |
| `EngineException` (JMT runtime failure) | 500 |
| Any other `Throwable` | 500 |
| Success | 200, `SimulationResponse` JSON |

(A watchdog timeout is **not** an error: it returns 200 with `completed:false` — handled naturally because the parser sets `completed` and the service returns normally.)

**Files:**
- Create: `src/main/java/qsim/http/ErrorResponse.java`, `SimulationHandler.java`, `HealthHandler.java`, `App.java`
- Test: `src/test/java/qsim/http/SimulationHandlerTest.java`

**Interfaces:**
- Consumes: `SimulationService`, `qsim.model.*`, `qsim.contract.ValidationException`, `qsim.engine.EngineException`, `Json.MAPPER`.
- Produces:
  - `ErrorResponse(String error, java.util.List<String> details)`.
  - `SimulationHandler implements com.sun.net.httpserver.HttpHandler` with constructor `SimulationHandler(SimulationService service)`; exposes `int statusFor(Throwable t)` and `byte[] handleBody(byte[] requestBody)` returning the response JSON, and a `Result(int status, byte[] body)` pair so the mapping is unit-testable without a live socket.
  - `HealthHandler implements HttpHandler` → 200 `{"status":"ok"}`.
  - `App.main(String[])`.

- [ ] **Step 1: Write the failing test** (exercise the mapping without a socket)

`src/test/java/qsim/http/SimulationHandlerTest.java`:

```java
package qsim.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import qsim.contract.ValidationException;
import qsim.engine.EngineException;

class SimulationHandlerTest {

  private final SimulationHandler handler = new SimulationHandler(new SimulationService(Config.defaults()));

  @Test
  void malformedJsonMapsTo400() {
    SimulationHandler.Result r = handler.process("{ this is not json ".getBytes(StandardCharsets.UTF_8));
    assertEquals(400, r.status());
    assertTrue(new String(r.body(), StandardCharsets.UTF_8).contains("error"));
  }

  @Test
  void semanticErrorMapsTo422() {
    // open class with no source -> ContractValidator throws UNPROCESSABLE
    String json = """
        {"model":{"name":"bad",
          "classes":[{"name":"web","type":"open"}],
          "nodes":[{"name":"snk","type":"sink"}],
          "routing":{"web":[]}},
         "seed":1}
        """;
    SimulationHandler.Result r = handler.process(json.getBytes(StandardCharsets.UTF_8));
    assertEquals(422, r.status());
  }

  @Test
  void unsupportedMeasureMapsTo400() {
    String json = """
        {"model":{"name":"m",
          "classes":[{"name":"web","type":"open"}],
          "nodes":[{"name":"src","type":"source","arrivals":{"web":{"distribution":{"type":"exponential","rate":1.0}}}},
                   {"name":"q","type":"queue","servers":1,"scheduling":"fcfs","service":{"web":{"distribution":{"type":"exponential","rate":2.0}}}},
                   {"name":"snk","type":"sink"}],
          "routing":{"web":[{"from":"src","to":"q"},{"from":"q","to":"snk"}]}},
         "seed":1,
         "measures":["teleportation-latency"]}
        """;
    SimulationHandler.Result r = handler.process(json.getBytes(StandardCharsets.UTF_8));
    assertEquals(400, r.status());
  }

  @Test
  void statusForMapsExceptionTypes() {
    assertEquals(422, handler.statusFor(
        new ValidationException(ValidationException.Kind.UNPROCESSABLE, java.util.List.of("x"))));
    assertEquals(400, handler.statusFor(
        new ValidationException(ValidationException.Kind.BAD_REQUEST, java.util.List.of("x"))));
    assertEquals(500, handler.statusFor(new EngineException("boom", null)));
  }
}
```

- [ ] **Step 2: Run — expect compile failure**

Run: `mvn -q test -Dtest=SimulationHandlerTest`
Expected: FAIL.

- [ ] **Step 3: Implement `ErrorResponse`**

```java
package qsim.http;
import java.util.List;
public record ErrorResponse(String error, List<String> details) {}
```

- [ ] **Step 4: Implement `SimulationHandler`**

`src/main/java/qsim/http/SimulationHandler.java`:

```java
package qsim.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import qsim.contract.ValidationException;
import qsim.engine.EngineException;
import qsim.model.SimulationRequest;
import qsim.model.SimulationResponse;

public class SimulationHandler implements HttpHandler {

  public record Result(int status, byte[] body) {}

  private final SimulationService service;

  public SimulationHandler(SimulationService service) {
    this.service = service;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try {
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        writeJson(exchange, 405, toJson(new ErrorResponse("method not allowed", List.of())));
        return;
      }
      byte[] body = exchange.getRequestBody().readAllBytes();
      Result r = process(body);
      writeJson(exchange, r.status(), r.body());
    } finally {
      exchange.close();
    }
  }

  /** Pure request→response mapping, testable without a socket. */
  public Result process(byte[] requestBody) {
    SimulationRequest req;
    try {
      req = Json.MAPPER.readValue(requestBody, SimulationRequest.class);
    } catch (JsonProcessingException | java.io.UncheckedIOException e) {
      return new Result(400, toJson(new ErrorResponse("malformed request JSON", List.of(rootMessage(e)))));
    } catch (IOException e) {
      return new Result(400, toJson(new ErrorResponse("malformed request JSON", List.of(rootMessage(e)))));
    }
    try {
      SimulationResponse resp = service.simulate(req);
      return new Result(200, toJson(resp));
    } catch (ValidationException e) {
      return new Result(statusFor(e), toJson(new ErrorResponse(
          e.kind() == ValidationException.Kind.BAD_REQUEST ? "invalid request" : "unprocessable model",
          e.details())));
    } catch (EngineException e) {
      return new Result(500, toJson(new ErrorResponse("simulation engine error", List.of(rootMessage(e)))));
    } catch (RuntimeException e) {
      return new Result(500, toJson(new ErrorResponse("internal error", List.of(rootMessage(e)))));
    }
  }

  public int statusFor(Throwable t) {
    if (t instanceof ValidationException v) {
      return v.kind() == ValidationException.Kind.BAD_REQUEST ? 400 : 422;
    }
    if (t instanceof JsonProcessingException || t instanceof JsonMappingException) {
      return 400;
    }
    return 500;
  }

  private static String rootMessage(Throwable t) {
    Throwable r = t;
    while (r.getCause() != null && r.getCause() != r) {
      r = r.getCause();
    }
    return r.getMessage() == null ? r.getClass().getSimpleName() : r.getMessage();
  }

  private static byte[] toJson(Object o) {
    try {
      return Json.MAPPER.writeValueAsBytes(o);
    } catch (JsonProcessingException e) {
      return ("{\"error\":\"failed to serialize response\"}").getBytes(StandardCharsets.UTF_8);
    }
  }

  private static void writeJson(HttpExchange exchange, int status, byte[] body) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
  }
}
```

- [ ] **Step 5: Implement `HealthHandler` and `App`**

`src/main/java/qsim/http/HealthHandler.java`:

```java
package qsim.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class HealthHandler implements HttpHandler {
  @Override
  public void handle(HttpExchange exchange) throws IOException {
    byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    try (var os = exchange.getResponseBody()) {
      os.write(body);
    }
  }
}
```

`src/main/java/qsim/http/App.java`:

```java
package qsim.http;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public final class App {
  private App() {}

  public static void main(String[] args) throws Exception {
    System.setProperty("java.awt.headless", "true");
    Config config = Config.fromEnv();
    SimulationService service = new SimulationService(config);

    HttpServer server = HttpServer.create(new InetSocketAddress(config.port()), 0);
    server.createContext("/simulate", new SimulationHandler(service));
    server.createContext("/health", new HealthHandler());
    // Concurrency 1 (spec §4): a single worker thread serializes simulations.
    server.setExecutor(Executors.newSingleThreadExecutor());
    server.start();
    System.out.println("qsim-service listening on :" + config.port());
  }
}
```

- [ ] **Step 6: Run — expect pass**

Run: `mvn -q test -Dtest=SimulationHandlerTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/qsim/http/ErrorResponse.java src/main/java/qsim/http/SimulationHandler.java src/main/java/qsim/http/HealthHandler.java src/main/java/qsim/http/App.java src/test/java/qsim/http/SimulationHandlerTest.java
git commit -m "feat: HTTP frontend (POST /simulate, GET /health) with error mapping"
```

---

### Task 13: Golden analytic checks + determinism

The correctness gate the project actually cares about (spec §8): assert the simulation's confidence intervals **bracket** known closed-form results. This is where a systematically-wrong translation (bad distribution params, wrong `terminal` flag, mis-mapped measures) is caught. Uses the M/G/1 Pollaczek–Khinchine formula — which simultaneously validates the mean *and* the SCV of the moment-matched distributions.

**Closed forms (single M/G/1 open queue, λ, service mean `m`, SCV `c`, μ=1/m, ρ=λm):**
- Utilization `U = ρ` (depends only on the mean — validates moment→mean).
- Throughput `X = λ`.
- Response time (P–K): `E[T] = m + λ·m²·(1+c) / (2·(1−ρ))` — validates mean *and* SCV together.
  - `c=1` (Exponential / M/M/1): reduces to `1/(μ−λ)`.
  - `c=0` (Deterministic / M/D/1).
  - `c=2` (Gamma via moment form).

**Files:**
- Create: `src/test/java/qsim/golden/GoldenAnalyticTest.java`
- (No production code — this task is a pure verification gate over Tasks 1–12.)

**Interfaces:**
- Consumes: `qsim.http.{SimulationService, Config}`, `qsim.model.*`. No new production types.

- [ ] **Step 1: Write the golden test**

`src/test/java/qsim/golden/GoldenAnalyticTest.java`:

```java
package qsim.golden;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qsim.http.Config;
import qsim.http.SimulationService;
import qsim.model.*;

class GoldenAnalyticTest {

  private final SimulationService service = new SimulationService(Config.defaults());

  private Distribution named(String type, double rate) {
    return new Distribution(type, rate, null, null, null);
  }
  private Distribution det(double value) {
    return new Distribution("deterministic", null, value, null, null);
  }
  private Distribution moment(double mean, double scv) {
    return new Distribution(null, null, null, mean, scv);
  }

  /** Single open M/G/1 queue: source -> q -> sink. lambda arrivals, given service dist. */
  private SimulationRequest mg1(double lambda, Distribution service) {
    NetworkModel m = new NetworkModel("mg1",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source",
                    Map.of("web", new ArrivalSpec(named("exponential", lambda)))),
                new QueueNode("q", "queue", 1, "fcfs", null,
                    Map.of("web", new ServiceSpec(service))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));
    Stopping s = new Stopping(0.05, 0.03, 50_000, 2_000_000, null, null, 120, false);
    return new SimulationRequest(m, 4242L, s, List.of("utilization", "throughput", "response-time"));
  }

  private MeasureResult pick(SimulationResponse r, String type) {
    return r.measures().stream()
        .filter(m -> m.type().equals(type) && m.station().equals("q"))
        .findFirst().orElseThrow(() -> new AssertionError("missing measure " + type));
  }

  private void assertBrackets(MeasureResult m, double expected) {
    assertTrue(m.lower() <= expected && expected <= m.upper(),
        m.type() + " CI [" + m.lower() + ", " + m.upper() + "] must bracket " + expected
            + " (mean=" + m.mean() + ", success=" + m.success() + ")");
  }

  private double pkResponseTime(double lambda, double mean, double scv) {
    double rho = lambda * mean;
    return mean + (lambda * mean * mean * (1 + scv)) / (2 * (1 - rho));
  }

  @Test
  void mm1BracketsClosedForms() {
    double lambda = 1.0, mu = 2.0, mean = 1 / mu, rho = lambda * mean;
    SimulationResponse r = service.simulate(mg1(lambda, named("exponential", mu)));
    assertBrackets(pick(r, "utilization"), rho);                 // 0.5
    assertBrackets(pick(r, "throughput"), lambda);               // 1.0
    assertBrackets(pick(r, "response-time"), pkResponseTime(lambda, mean, 1.0)); // 1.0
  }

  @Test
  void md1BracketsClosedForms() {
    double lambda = 1.0, mean = 0.5;
    SimulationResponse r = service.simulate(mg1(lambda, det(mean)));
    assertBrackets(pick(r, "utilization"), lambda * mean);       // 0.5
    assertBrackets(pick(r, "response-time"), pkResponseTime(lambda, mean, 0.0)); // 0.75
  }

  @Test
  void momentGammaBracketsPkFormula() {
    double lambda = 1.0, mean = 0.5, scv = 2.0;
    SimulationResponse r = service.simulate(mg1(lambda, moment(mean, scv)));
    assertBrackets(pick(r, "utilization"), lambda * mean);       // 0.5 (mean check)
    assertBrackets(pick(r, "response-time"), pkResponseTime(lambda, mean, scv)); // 1.25 (mean + SCV)
  }

  @Test
  void identicalSeedIsDeterministicThroughTheService() {
    SimulationResponse a = service.simulate(mg1(1.0, named("exponential", 2.0)));
    SimulationResponse b = service.simulate(mg1(1.0, named("exponential", 2.0)));
    List<MeasureResult> ma = a.measures(), mb = b.measures();
    assertTrue(ma.size() == mb.size());
    for (int i = 0; i < ma.size(); i++) {
      assertTrue(ma.get(i).mean().equals(mb.get(i).mean()),
          "same seed must yield identical means for measure " + ma.get(i).type());
    }
  }

  @Test
  void forkJoinResponseTimeRespectsLowerBound() {
    // 2-branch homogeneous fork-join; T_FJ >= single-branch E[T] (rigorous lower bound).
    double lambda = 0.5, mu = 2.0, mean = 1 / mu;
    NetworkModel m = new NetworkModel("fj",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source",
                    Map.of("web", new ArrivalSpec(named("exponential", lambda)))),
                new ForkJoinNode("fj", "fork-join",
                    List.of(new Branch(Map.of("web", new ServiceSpec(named("exponential", mu)))),
                            new Branch(Map.of("web", new ServiceSpec(named("exponential", mu))))),
                    "all"),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "fj", null), new RoutingEdge("fj", "snk", null))));
    Stopping s = new Stopping(0.05, 0.05, 50_000, 2_000_000, null, null, 120, false);
    SimulationResponse r = service.simulate(new SimulationRequest(m, 4242L, s,
        List.of("system-response-time")));
    MeasureResult sys = r.measures().stream()
        .filter(x -> x.type().equals("system-response-time"))
        .findFirst().orElseThrow();
    double singleBranchET = 1 / (mu - lambda); // 1/(2-0.5) = 0.667
    assertTrue(sys.mean() >= singleBranchET * 0.95,
        "fork-join E[T]=" + sys.mean() + " must be >= slowest-branch E[T]=" + singleBranchET);
  }
}
```

- [ ] **Step 2: Run — this is the correctness gate**

Run: `mvn -q test -Dtest=GoldenAnalyticTest`
Expected: PASS.

**If `response-time` fails to bracket** the P–K value across all three variance cases the same way (means systematically low), the likely cause is `setTerminalSimulation(true)` measuring a transient rather than steady state — change the `terminal` argument in `SimulationService.simulate` from `true` to `false` (Task 11) and re-run. Record the resolved value in [[jmt-engine-facts]]. If only the Gamma case fails but exp/det pass, the Gamma parameter order/scale in `DistributionResolver` (Task 4) is wrong — re-derive against §6.2 (`shape=1/scv`, `scale=mean·scv`). Do **not** widen the CIs to force a pass; a failing bracket means the numbers are wrong.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/qsim/golden/GoldenAnalyticTest.java
git commit -m "test: golden analytic checks (M/M/1, M/D/1, M/G/1 moment, fork-join, determinism)"
```

---

### Task 14: Containerization + GPL notices

Package the service as a headless Docker image and satisfy the GPL/JMT attribution obligations (spec §3, §10). The image bundles the unmodified JMT jar and all runtime dependencies.

**Files:**
- Create: `Dockerfile`, `.dockerignore`, `NOTICE`
- Modify: none (pom already copies runtime deps to `target/dependency` in Task 1; system-scoped JMT is copied explicitly in the Dockerfile).

**Interfaces:** none (build/deploy artifacts).

- [ ] **Step 1: Write `NOTICE`**

`NOTICE`:

```
qsim-service
Copyright (C) 2026 the qsim-service authors

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 2 of the License, or (at your option) any later
version. See the LICENSE file for the full text.

This product bundles and links, in-process, the following GPL software:

  Java Modelling Tools (JMT) 1.4.0 — https://jmt.sourceforge.net/
  Copyright (C) the JMT authors. Licensed under the GNU GPL v2 or later.
  The bundled artifact (lib/JMT-singlejar-1.4.0.jar) is unmodified.

Because this service links JMT engine classes in-process, it is a derivative
work and is therefore distributed under the GPL (v2 or later). The complete
corresponding source for this service is the contents of this repository.
Source for JMT is available from the JMT project at the URL above.
```

- [ ] **Step 2: Write `.dockerignore`**

`.dockerignore`:

```
target/
.git/
.idea/
*.iml
docs/
```

(Do **not** ignore `lib/` — the JMT jar must reach the build context.)

- [ ] **Step 3: Write the `Dockerfile`** (multi-stage)

`Dockerfile`:

```dockerfile
# ---- build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
# Bring in the bundled JMT jar first so the system-scope dependency resolves.
COPY lib/ lib/
COPY pom.xml .
RUN mvn -q -o -DskipTests dependency:go-offline || mvn -q -DskipTests dependency:go-offline
COPY src/ src/
# Package + copy runtime dependencies (see pom dependency-plugin, Task 1).
RUN mvn -q -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/qsim-service-0.1.0.jar app.jar
COPY --from=build /src/target/dependency/ dependency/
# System-scoped JMT is excluded from copy-dependencies; bring it explicitly.
COPY --from=build /src/lib/JMT-singlejar-1.4.0.jar dependency/JMT-singlejar-1.4.0.jar
COPY NOTICE LICENSE ./
ENV QSIM_PORT=8080
EXPOSE 8080
# Headless is also set in App.main; -Djava.awt.headless=true is belt-and-suspenders.
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-cp", "app.jar:dependency/*", "qsim.http.App"]
```

> **Note:** the jar name `qsim-service-0.1.0.jar` must match `${artifactId}-${version}` from the pom (Task 1: artifact `qsim-service`, version `0.1.0`). If Task 1 configured a shaded/uber jar instead, adjust the classpath accordingly. The plan's pom uses a plain jar + `target/dependency/*`, so the classpath above is correct.

- [ ] **Step 4: Build and smoke-test the image** (manual/integration gate — no JUnit)

Run:

```bash
docker build -t qsim-service:0.1.0 .
docker run --rm -d -p 8080:8080 --name qsim-smoke qsim-service:0.1.0
# wait for startup, then:
sleep 3
curl -fsS localhost:8080/health          # expect {"status":"ok"}
curl -fsS -X POST localhost:8080/simulate \
  -H 'Content-Type: application/json' \
  --data @src/test/resources/fixtures/mm1.json   # expect 200 + measures (fixture from Task 15)
docker stop qsim-smoke
```

Expected: `/health` returns `{"status":"ok"}`; `/simulate` returns a `SimulationResponse` with `completed:true` and a utilization measure bracketing 0.5. If `/simulate` fails with a `HeadlessException` or JMT class-load error, the classpath or headless flag is wrong — fix the `ENTRYPOINT` classpath / confirm the JMT jar copied into `dependency/`.

> This step depends on the `mm1.json` fixture created in Task 15. If executing Task 14 before Task 15, use an inline JSON body (the M/M/1 model from Task 11's test) instead.

- [ ] **Step 5: Commit**

```bash
git add Dockerfile .dockerignore NOTICE
git commit -m "build: headless Docker image + GPL/JMT NOTICE"
```

---

### Task 15: README, integration fixture, and full-suite green

Document usage and ship the spec's integration fixture (§8): the qopt 3-station mixed network (M/M/1 + M/D/1 + fork-join). Confirm the whole suite is green.

**Files:**
- Create: `src/test/resources/fixtures/mm1.json`, `src/test/resources/fixtures/qopt-3station.json`
- Create: `src/test/java/qsim/integration/FixtureIntegrationTest.java`
- Modify: `README.md` (replace the scaffold with real usage)

**Interfaces:**
- Consumes: `qsim.http.{SimulationService, Config, Json}`, `qsim.model.SimulationRequest`.

- [ ] **Step 1: Write the fixtures**

`src/test/resources/fixtures/mm1.json`:

```json
{
  "model": {
    "name": "mm1",
    "classes": [{ "name": "web", "type": "open" }],
    "nodes": [
      { "name": "src", "type": "source",
        "arrivals": { "web": { "distribution": { "type": "exponential", "rate": 1.0 } } } },
      { "name": "q", "type": "queue", "servers": 1, "scheduling": "fcfs", "capacity": null,
        "service": { "web": { "distribution": { "type": "exponential", "rate": 2.0 } } } },
      { "name": "snk", "type": "sink" }
    ],
    "routing": { "web": [ { "from": "src", "to": "q" }, { "from": "q", "to": "snk" } ] }
  },
  "seed": 12345,
  "stopping": { "alpha": 0.05, "precision": 0.03, "minSamples": 50000, "maxSamples": 2000000,
                "maxWallClockSeconds": 120 },
  "measures": ["utilization", "throughput", "response-time"]
}
```

`src/test/resources/fixtures/qopt-3station.json` (open `web` class: source → M/M/1 → M/D/1 → fork-join → sink):

```json
{
  "model": {
    "name": "qopt-3station",
    "classes": [{ "name": "web", "type": "open" }],
    "nodes": [
      { "name": "src", "type": "source",
        "arrivals": { "web": { "distribution": { "type": "exponential", "rate": 1.0 } } } },
      { "name": "mm1", "type": "queue", "servers": 1, "scheduling": "fcfs", "capacity": null,
        "service": { "web": { "distribution": { "type": "exponential", "rate": 3.0 } } } },
      { "name": "md1", "type": "queue", "servers": 1, "scheduling": "fcfs", "capacity": null,
        "service": { "web": { "distribution": { "type": "deterministic", "value": 0.25 } } } },
      { "name": "fj", "type": "fork-join",
        "branches": [
          { "service": { "web": { "distribution": { "mean": 0.2, "scv": 1.5 } } } },
          { "service": { "web": { "distribution": { "mean": 0.1, "scv": 0.5 } } } }
        ],
        "join": "all" },
      { "name": "snk", "type": "sink" }
    ],
    "routing": {
      "web": [
        { "from": "src", "to": "mm1" },
        { "from": "mm1", "to": "md1" },
        { "from": "md1", "to": "fj" },
        { "from": "fj",  "to": "snk" }
      ]
    }
  },
  "seed": 20260726,
  "stopping": { "alpha": 0.05, "precision": 0.05, "minSamples": 20000, "maxSamples": 1000000,
                "maxWallClockSeconds": 120 },
  "measures": ["utilization", "throughput", "response-time", "system-response-time"]
}
```

- [ ] **Step 2: Write the integration test**

`src/test/java/qsim/integration/FixtureIntegrationTest.java`:

```java
package qsim.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import qsim.http.Config;
import qsim.http.Json;
import qsim.http.SimulationService;
import qsim.model.MeasureResult;
import qsim.model.SimulationRequest;
import qsim.model.SimulationResponse;

class FixtureIntegrationTest {

  private final SimulationService service = new SimulationService(Config.defaults());

  private SimulationRequest load(String path) throws Exception {
    try (var in = getClass().getResourceAsStream(path)) {
      return Json.MAPPER.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8),
          SimulationRequest.class);
    }
  }

  @Test
  void mm1FixtureBracketsUtilization() throws Exception {
    SimulationResponse r = service.simulate(load("/fixtures/mm1.json"));
    MeasureResult u = r.measures().stream()
        .filter(m -> m.type().equals("utilization") && m.station().equals("q"))
        .findFirst().orElseThrow();
    assertTrue(u.lower() <= 0.5 && 0.5 <= u.upper());
  }

  @Test
  void qopt3StationRunsAndMeasuresEveryStation() throws Exception {
    SimulationResponse r = service.simulate(load("/fixtures/qopt-3station.json"));
    assertEquals("qopt-3station", r.modelName());
    // utilization present for both single-server queues
    assertTrue(r.measures().stream().anyMatch(m -> m.station().equals("mm1") && m.type().equals("utilization")));
    assertTrue(r.measures().stream().anyMatch(m -> m.station().equals("md1") && m.type().equals("utilization")));
    // fork-join measures collapse to the domain node name "fj" (never internal fj__ names)
    assertTrue(r.measures().stream().noneMatch(m -> m.station().contains("__")));
    // known utilizations: rho_mm1 = 1/3, rho_md1 = 1*0.25 = 0.25
    MeasureResult umm1 = r.measures().stream()
        .filter(m -> m.station().equals("mm1") && m.type().equals("utilization")).findFirst().orElseThrow();
    assertTrue(umm1.lower() <= 1.0 / 3 && 1.0 / 3 <= umm1.upper(),
        "mm1 U CI must bracket 1/3");
  }
}
```

- [ ] **Step 3: Rewrite `README.md`**

Replace the scaffold `README.md` with (keep the existing GPL badge/heading if present):

````markdown
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

**Distributions:** named (`{"type":"exponential","rate":r}`, `{"type":"deterministic","value":v}`)
or moment form (`{"mean":m,"scv":c}` → Exponential/Deterministic/Gamma). v1 implements these
three forms; the remaining JMT named distributions are a mechanical registry extension.

**Replication:** one request = one seed = one run. Run many seeds (e.g. many containers) and
aggregate per the independent-replications method (design spec §9).

## Error responses

| Status | Meaning |
|--------|---------|
| 400 | Malformed JSON, or unsupported measure type |
| 422 | Semantic model error (dangling routing, open class without a source, probabilities not summing to 1) or JSIMG schema failure |
| 500 | Simulation engine failure |
| 200 + `completed:false` | Watchdog fired before convergence — partial measures |
````

- [ ] **Step 4: Run the full suite**

Run: `mvn -q test`
Expected: ALL tests PASS (Tasks 1–15). If the golden or fixture tests reveal a systematic bias, resolve per Task 13's guidance (do not weaken assertions).

- [ ] **Step 5: Commit**

```bash
git add src/test/resources/fixtures/ src/test/java/qsim/integration/FixtureIntegrationTest.java README.md
git commit -m "docs: README + qopt 3-station integration fixture; full suite green"
```

---

## Self-Review (author's checklist — completed against the design spec)

**1. Spec coverage:**

| Spec section | Covered by |
|--------------|------------|
| §2 `POST /simulate` + `GET /health` | Task 12 |
| §2 node types (source/queue/fork-join/delay/sink), open/closed classes, probabilistic routing | Tasks 3, 7, 8 |
| §2 named + moment distributions | Tasks 3, 4 (v1 scope: exp/det/moment — documented) |
| §2 CI stopping + min/max samples + time/event caps + wall-clock watchdog | Tasks 7 (`<sim>` attrs, `<measure alpha precision>`), 9 (`setSimulationMaxDuration`) |
| §2 containerization headless | Task 14 |
| §4 three layers, stateless, concurrency 1 | Tasks 11, 12 (single-thread executor) |
| §4 quarantine + clean engine state per run | Task 9 (only `qsim.engine` imports `jmt.*`; fresh dispatcher per run) |
| §5.1 request contract | Task 3 |
| §5.2 response contract (mean/CI/samples/variance, `completed`, `success`) | Tasks 3, 10 |
| §5.3 invariants | Task 5 |
| §6.1 domain→JSIMG mapping table | Tasks 7, 8 |
| §6.2 moment→distribution | Task 4 |
| §7 error table (400/422/500/200-completed:false) | Task 12 (+ 422 XSD gate in Task 7, watchdog→200 in Tasks 9–11) |
| §8 translation snapshot / XSD | Tasks 7, 8 |
| §8 golden analytic checks (M/M/1, fork-join) | Task 13 |
| §8 moment-matching empirical | Task 13 (P–K bracket validates mean+SCV) |
| §8 determinism | Tasks 9, 13 |
| §8 headless | Task 1 (`-Djava.awt.headless=true` in surefire) |
| §8 error-path tests | Task 12 |
| §8 integration fixture (3-station mixed) | Task 15 |
| §10 tech/deploy (Java 17, Maven, httpserver+Jackson, Docker) | Tasks 1, 12, 14 |
| §11 open items | Resolved pre-plan (JMT facts in [[jmt-engine-facts]]); residual `terminal`-flag verification wired as a one-line flip (Tasks 9, 13) |

**Documented v1 scope decisions / deviations (not gaps):**
- **Distributions:** only `exponential`, `deterministic`, and the moment form are implemented in v1 (Task 3 scope note). The remaining named distributions in §5.1 are a registry extension (Task 4).
- **Measures:** `arrival-rate`, `system-throughput`, `fork-join-response-time` are excluded from v1's supported set (Task 6) pending verification of their exact JMT `type` strings; unsupported types fail loudly (400), never silently.
- **Stability precheck:** the §7 "open station arrival rate ≥ total service capacity" 422 is deferred (Task 5) — it needs traffic-equation solving; instability instead surfaces non-silently as `completed:false`.
- **`minSamples`:** JMT 1.4.0's `<sim>` has no `minSamples` attribute (per [[jmt-engine-facts]]); the field is accepted in the contract but not forwarded in v1. Note documented in Task 7 if the XSD rejects it.

**2. Placeholder scan:** No `TODO`/`TBD`/"add error handling"/"similar to Task N" placeholders. Two *intentional* scratch lines (the `RandomSource` guard removal in Task 7, the wrong `measureMapper.map` line in Task 11) are explicitly called out with instructions to delete them — flagged, not hidden.

**3. Type consistency:** `CanonicalDistribution(distributionClass, parameterClass, label, params)` / `DistParam(name, javaType, value)` (Task 4) are consumed with those exact accessors in Task 7. `MeasureSpec(name, jmtType, referenceNode, referenceUserClass, nodeType)` (Task 6) matches its use in Tasks 7, 11. `MeasureResult` 13-arg constructor (Task 3) matches Tasks 10, 13. `RunResult(outputFile, wallClockSeconds)` (Task 9) matches Task 11. `SolutionsParser` lives in `qsim.result` consistently (Task 10, fixed to match the File Structure). `ValidationException.Kind` used identically in Tasks 5, 6, 7, 12. `Stopping`/`SimulationRequest`/`SimulationResponse` signatures (Task 3) match all consumers.

---


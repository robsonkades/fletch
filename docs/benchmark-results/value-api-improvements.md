# Value API optimization investigation

Measured on September 18, 2026 (America/Sao_Paulo; September 19 UTC), starting
at `38d89b79066a27a299302d38531567b044df0239`. The starting runtime is unchanged
from `8ac74fec5a3b7561b0568bf851d3aaab4aecbb26`.

**Neither optimization met the predeclared adoption criterion.** Both
prototypes were reverted; the library implementation is unchanged. The
measurements identify costs and uncertainty without establishing a worthwhile
runtime change for the workloads tested.

A later [LocalDate experiment](local-date.md) evaluates a separate targeted
parser optimization. The two prototypes and results below remain historical.

## Rechecking the mapping regression signal

The [first study](value-api.md) found -6.65% throughput for mapping 50-item
NF-e documents versus published 1.3.0, with a descriptive 95% interval of
[-10.82%, -2.29%]. This follow-up repeated that comparison with the same
frozen jars and ten one-second warmup iterations instead of three.

| Scenario | Published 1.3.0 docs/s | Value API docs/s | Paired change [95% interval] | B/document |
|---|---:|---:|---:|---:|
| Mapping, 50 items | 27,212 | 27,041 | -0.61% [-7.24%, +6.49%] | 14,208 in both |

The repeat does not confirm a consistent slowdown, but its interval still
allows the loss estimated in the first study. It does not establish
equivalence, resolve the cause, or prove that longer warmup fixed anything:
host conditions were not controlled and the measurement period changed.
Neither batch was discarded or pooled with the other to obtain a preferred
conclusion.

## Candidate 1: retain the start-tag probe from `exists`

The prototype reused the engine's existing start-tag offsets for an immediate
read after `exists`. It added an owner marker to the pooled engine, invalidated
it on another live lookup and cleared it when extraction finished. The next
read still validated the live element normally. Additional tests exercised
pending values, nested replay, draining, failure and engine reuse.

The control in this comparison is the **unmodified value-API runtime**, not
published 1.3.0. Positive change means higher throughput for the prototype.

| Scenario | Role | Throughput change [95% interval] | Rounded B/document, control → prototype |
|---|---|---:|---:|
| `exists` + integer, 100% present | Primary | +3.20% [-7.51%, +15.14%] | 224 → 224 |
| `exists` + integer, 50% present | Primary | -0.54% [-9.82%, +9.70%] | 256 → 256 |
| Native integer, 100% present | Guard | -0.67% [-5.29%, +4.18%] | 224 → 224 |
| Cursor, 50 NF-e items | Guard | +0.38% [-6.22%, +7.45%] | See below |
| Mapping, 50 NF-e items | Guard | +1.02% [-1.98%, +4.10%] | 14,208 → 14,208 |

The control cursor allocated 32,408 B/document in three forks and 33,208 in
one; the prototype allocated 32,408 in all four. The original study also
observed these two levels, there in the value-API candidate. A fixed 800-byte
cost caused by adding the APIs is therefore not established.

**Rejected:** neither primary case met the predeclared requirement of at
least 5% point improvement with a positive interval. All 731 core tests passed
for the prototype, but the measured benefit did not justify its extra state.
The [source and test patch](value-api-improvements-reproduction/probe.patch)
is retained as an experiment, not applied to the library.

## Candidate 2: separate the common String conversion path

C2 diagnostics of the original cursor run captured 29 refusals to inline the
256-byte `TypeConverter.convert` method ("already compiled into a big method")
and one successful inline. These are compilation decisions across caller
trees and recompilations, not dynamic call counts or a CPU-share estimate.

The six-line prototype kept empty-span and String handling in `convert` and
delegated the unchanged remaining conversion chain to a private method.
It was built and measured separately from the rejected probe cache.
All 712 core tests passed. In a separate diagnostic run, the 28-byte dispatch
method had three captured C2 inline successes and no captured refusals; the
236-byte delegated method also had three successes. That confirms the
predicted inlining change, but does not establish a throughput improvement.

The control is again the unmodified value-API runtime.

| Scenario | Role | Throughput change [95% interval] | Rounded B/document, control → prototype |
|---|---|---:|---:|
| Cursor, 50 NF-e items | Primary | +0.92% [-3.99%, +6.08%] | 32,408 → 32,408 |
| Native integer, 100% present | Primary | +0.99% [-8.43%, +11.38%] | 224 → 224 |
| Mapping, 50 NF-e items | Guard | +0.00% [-4.62%, +4.85%] | 14,208 → 14,208 |
| Typed LocalDate, 100% present | Guard | -2.19% [-6.55%, +2.37%] | 712 → 712 |
| Custom integer, 100% present | Guard | -4.88% [-13.01%, +4.00%] | 272 → 272 |

**Rejected:** both primary intervals include zero and both point estimates
are below 5%. Better inlining did not produce a demonstrated material benefit.
All allocation levels above repeat across the eight forks per case, rounded
to whole bytes. The [patch](value-api-improvements-reproduction/dispatch.patch)
is preserved but not applied.

## Practical opportunities

- Prefer the existing typed accessor when it already provides the desired
  conversion. In the integer scenario, it allocated 224 B/document versus
  272 with a String conversion callback. This is an allocation result; the
  original throughput contrast was inconclusive.
- For a custom conversion with fallback, use `valueWith(name, converter,
  fallbackSupplier)` directly. A preceding `exists` is useful when structural
  presence itself matters, but adds another lookup for present elements.
- Further library optimization needs a representative application workload
  and a profile of its dominant work. This investigation does not justify a
  more complex probe cache, a dispatch refactor, or a new conversion API.

## Method and limits

The [initial plan](value-api-improvements-reproduction/plan.md) and
[dispatch plan](value-api-improvements-reproduction/dispatch-plan.md) were
written before their respective implementation and measurements. Adoption
required at least 5% primary point improvement, a positive descriptive 95%
interval, no guard point loss greater than 5%, and no unexplained allocation
increase. Broad guard intervals would remain uncertainty, not equivalence.
The investigation was limited to two isolated candidates.

There are 88 scored fresh JVMs: eight for the mapping repeat and 40 for each
prototype. Each case has four pairs, ten one-second warmup iterations and
five one-second measurements. The pair is the independent comparison unit.
The runner shuffled cases with seed 20260919 and reversed variant order
across rounds. All scheduled runs are retained, with no retries for a better
score. Changes are geometric means of paired ratios; intervals are 95%
Student-t intervals over four paired log ratios, without a multiple-comparison
correction. Absolute throughput is the arithmetic mean of fork scores.

Two of 88 scored forks crossed the descriptive +/-5% drift indicator
(last two measurement iterations versus first two): one mapping-repeat fork
and one dispatch fork; none in the probe batch. These forks remain in the
results. CPU-frequency and thermal telemetry were not collected.

JMH 1.37, Temurin 17.0.20.1+1, Windows 11 Pro 10.0.26200, i7-13700K,
31.75 GiB visible RAM, one worker, fixed 256 MiB G1 heap and GC profiler.
Affinity mask 340 selects logical CPUs 2, 4, 6 and 8, one thread on each of
four P cores; it does not reserve them or fix CPU frequency. The existing
high-performance power plan stayed unchanged. An unrelated, mostly idle
Java process was present. No build, test or other agent-run benchmark ran
concurrently with scored measurements.

The existing benchmarks return complete extraction results and rotate input
values, as described in the first study. One operation is one document.
Allocation means allocated bytes, not retained heap. Fixtures and mapping
construction are outside the timed operation. Only the listed targets,
document sizes, presence rates and Java 17 environment were measured;
results do not establish production latency or JDK 21/25 performance.

Four additional, unscored JVMs captured `LogCompilation`: original cursor,
original mapping, published mapping and dispatch-prototype cursor. The
analysis resolves method IDs within each compilation task and retains only
tasks installed by C2. Diagnostic throughput is excluded from adoption
decisions. Both mapping diagnostics showed similar inlining of existing
value accessors; neither established a cause for the earlier mapping loss.

After reverting both prototypes, the full Java 17 core/generator/example
reactor passed all 767 tests. The compatibility check again preserved 13
public types and 67 existing methods, ran the consumer compiled against
published 1.3.0 and the documented conversion example, and confirmed that
the library requires only `java.base`.
All 27 runtime class entries in the rebuilt jar match the frozen
before-experiment library byte-for-byte.

## Evidence and reproduction

Schedules, frozen-jar hashes, every fork score and the warmup, measurement,
allocation and GC trajectories are in
[mapping repeat data](data/value-api-improvements-mapping-repeat.json),
[probe data](data/value-api-improvements-probe-pairs.json),
[dispatch data](data/value-api-improvements-dispatch-pairs.json) and
[provenance](data/value-api-improvements-provenance.json).
The [exporter](value-api-improvements-reproduction/Export-Evidence.ps1)
records the original JSON/log hashes; published JSON uses UTF-8 without BOM
and LF newlines. Full raw output, compilation XML, patches and frozen jars
remain in the local `target/value-api-improvements` archive.

Each paired artifact preparation verified all 29 runtime/metadata entries
against the chosen library and equality of all 4,045 other entries, including
the benchmark harness. The prototype artifact named `published-benchmarks.jar`
contains the **before-prototype value-API runtime**; only the mapping repeat
uses the published 1.3.0 control.

To reproduce a prototype, create an isolated checkout of `38d89b7`, build the
before library, preserve its jar, apply only the corresponding
[probe](value-api-improvements-reproduction/probe.patch) or
[dispatch](value-api-improvements-reproduction/dispatch.patch) patch, and
build with `mvn -B -ntp -Pbenchmarks package '-Dgpg.skip=true'` under JDK 17.
Use the existing [artifact preparation script](value-api-reproduction/Prepare-Comparison.ps1)
with the preserved before jar as `-PublishedJar` and the prototype library as
`-CurrentJar`. Never combine the two patches for these comparisons.

Run `scripts/Compare-Benchmarks.ps1` with the matching
[case file](value-api-improvements-reproduction/dispatch-cases.json),
`-Rounds 4 -Seed 20260919 -WarmupIterations 10 -MeasurementIterations 5`,
explicit Java and a topology-appropriate affinity mask, then
`scripts/Summarize-Benchmarks.ps1`. The
[probe](value-api-improvements-reproduction/probe-cases.json) and
[mapping](value-api-improvements-reproduction/mapping-cases.json) cases are
also retained. Use a fresh output directory for every batch.

To reproduce compiler diagnostics, use a separate fresh JVM with ten warmup
and three measurement iterations, no GC profiler, and
`-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=<absolute XML path>`
in addition to the same heap and affinity settings. Analyze those files with
[Analyze-Jit.ps1](value-api-improvements-reproduction/Analyze-Jit.ps1).
Its `latestLibraryC2Seconds` field includes the benchmark namespace and is
not a runtime-only warmup-completion timestamp.

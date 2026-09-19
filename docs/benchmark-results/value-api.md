# Value API performance versus 1.3.0

Measured on September 18, 2026 (America/Sao_Paulo; September 19 UTC).
The candidate runtime is commit `8ac74fec5a3b7561b0568bf851d3aaab4aecbb26`.
These measurements do not establish performance neutrality: NF-e mapping with
50 items processed fewer documents per second in all four candidate/control
pairs. The cursor comparisons were inconclusive. No runtime optimization was
made as part of this measurement.

## Existing extraction paths

Both jars run the same `ExtractionBenchmark` and return the same NF-e records.
The control contains the published Maven Central 1.3.0 library. Preparation
verified all 29 runtime/metadata entries against their respective library jars
and the equality of all 4,045 other entries, including the benchmark harness,
dependencies, fixtures and executable manifest.
The unchanged mapping engine, scanner and other unchanged runtime classes also
match the published library byte-for-byte; the runtime class differences are
limited to `TypeConverter`, `XmlCursor`, `XmlCursorImpl` and its nested
context, and `XmlValue`.

| Scenario | 1.3.0 docs/s | Candidate docs/s | Paired throughput change | Descriptive 95% interval |
|---|---:|---:|---:|---:|
| Cursor, 1 item | 199,289 | 194,507 | -2.38% | [-5.40%, +0.74%] |
| Cursor, 50 items | 24,568 | 25,540 | +4.15% | [-6.86%, +16.47%] |
| Mapping, 50 items | 24,608 | 22,968 | -6.65% | [-10.82%, -2.29%] |

The mapping estimate crosses the predeclared 5% practical-loss threshold;
its interval remains below zero but does not establish a loss exceeding 5%.
This was the intended negative control because mapping's existing accessors
were unchanged. Its observed loss prevents claiming that adding the APIs has
no effect on existing paths. A compiler or host cause was not established.

Allocation is **allocated bytes per document**, not retained heap or RSS:

| Scenario | 1.3.0 B/document, per-fork levels | Candidate B/document, per-fork levels |
|---|---:|---:|
| Cursor, 1 item | 4,512 in 3 forks; 4,496 in 1 | 4,512 in all 4 |
| Cursor, 50 items | 32,408 in all 4 | 32,408 in 3; 33,208 in 1 |
| Mapping, 50 items | 14,208 in all 4 | 14,208 in all 4 |

The cursor's extra allocation did not repeat in every JVM. Treating the
four-fork averages (+4 and +200 B/document) as a fixed per-call cost would
hide that variation. Increasing the number of invoice items increased work
and allocation in both runtimes as expected.

## Opting into the new APIs

The feature comparisons use the **same candidate jar** on both sides.
One operation extracts one small document and returns the selected value and
a trailing sibling. Every worker rotates 64 distinct byte arrays. Present
integers are outside the boxing cache. Missing values are deterministically
interspersed; there are no empty elements. The `exists` and direct-read routes
are equivalent only for this input population: structural existence includes
empty elements in the actual API.

Callbacks are non-capturing. The fallback returns cached integer `-1`; this
does not measure expensive application fallback logic or captured callback
objects. Integer and `LocalDate` are the only conversion targets measured here.

| Equivalent routes (control → API) | Presence | Control docs/ms | API docs/ms | Throughput change [95% interval] | B/document, control → API |
|---|---:|---:|---:|---:|---:|
| Native integer → `valueWith("value", Integer::valueOf)` | 100% | 9,586.00 | 8,852.66 | -6.96% [-18.61%, +6.36%] | 224 → 272 |
| Native integer → `exists` then native integer | 100% | 8,965.16 | 8,613.89 | -3.73% [-9.83%, +2.78%] | 224 → 224 |
| Native integer → `exists` then native integer | 50% | 9,744.55 | 8,698.80 | -10.86% [-19.82%, -0.91%] | 256 → 256 |
| String + manual null check/conversion → `valueWith` + fallback | 50% | 9,265.16 | 8,882.32 | -4.51% [-18.16%, +11.42%] | 280 → 280 |
| String + `LocalDate.parse` → `value("value", LocalDate.class)` | 100% | 3,811.92 | 4,093.38 | +7.40% [+1.05%, +14.16%] | 752 → 712 |

These tiny documents isolate API usage; their throughput is not comparable
to the substantially larger NF-e documents above. Allocation levels repeat
in all eight forks of each comparison, rounded to whole bytes.

Custom integer conversion adds 48 allocated bytes per document, consistent
with decoding the intermediate String that the callback receives. Its
throughput contrast is inconclusive. The fallback helper also has an
inconclusive throughput contrast and adds no allocation versus the equivalent
manual String conversion and null check in this scenario.

`exists` followed by a read repeats start-tag lookup for a present value.
It added no allocation here, but the mixed-presence case processed fewer
documents per second in all four pairs. For conversion plus fallback, calling
`valueWith` directly avoids this preliminary presence check. Use `exists`
when structural presence, including an empty element, is itself needed.

Typed `LocalDate` reads processed more documents per second and allocated
40 fewer bytes in this compiled context. Both routes delegate date parsing
to the JDK; the JIT/allocation mechanism behind the difference was not
profiled. This does not establish that all new built-in types are faster
than application conversion functions.

## Method and limits

JMH 1.37; Temurin 17.0.20.1+1; Windows 11 Pro 10.0.26200; i7-13700K;
31.75 GiB RAM visible to the OS. One benchmark worker,
256 MiB fixed initial/maximum G1 heap, compiler blackholes, GC profiler.
All runner/JVM processes use affinity mask 340, logical CPUs 2, 4, 6 and 8,
one logical CPU on each of four P cores. This does not reserve cores, disable
SMT or fix clocks. The existing GameTurbo high-performance power plan remained
unchanged. An unrelated, mostly idle Java process was present.

Each case uses four pairs of fresh JVMs, 3 one-second warmup iterations and
5 one-second measurement iterations. Cases are shuffled with seed 20260918;
variant order reverses each round. Regression and feature batches run
sequentially, with no concurrent build, test or other agent-run benchmark.
One operation is one complete document. Construction of fixtures, callbacks
and mappings is excluded; parsing, conversion, result construction and engine
cleanup are included. There is no disk/network I/O in the timed operation.

Absolute throughput columns are arithmetic means of fork scores. Changes are
geometric means of paired candidate/control ratios. Intervals are descriptive
95% Student-t intervals over the four paired log ratios, without correction
for multiple comparisons. Iterations within one JVM are not independent
replications. The +/-5% margin is a local triage criterion, not a service SLO.
Every scheduled fork is retained; no fastest-run selection or retry occurred.

Six of 24 regression forks and six of 40 feature forks crossed the descriptive +/-5% drift indicator
(last two measurement iterations versus first two). Raw warmup and measurement
trajectories are retained. Compilation traces and CPU-frequency/thermal
telemetry were not collected, so steady compilation and the cause of drift
are unverified. These results describe this bounded local run, not production
request latency, multi-thread capacity, or performance on JDK 21/25.

The semantic benchmark test checks all seven methods over two complete input
rotations at 0%, 50% and 100% presence. All 712 core tests passed before the
measurement. Existing NF-e setup also checks cursor/mapping result equality.

## Evidence and reproduction

The paired schedules, jar hashes, all fork scores, allocation/GC counters and
warmup/measurement trajectories are preserved in
[regression data](data/value-api-regression.json),
[feature data](data/value-api-features.json) and
[provenance](data/value-api-provenance.json).
Full original JSON, console logs and frozen jars remain in the local
`target/value-api-performance` archive; the data records their SHA-256 hashes.
Published JSON hashes use UTF-8 without BOM and LF newlines, as in Git blobs;
normalize checkout line endings before checking them. Source and original
output hashes identify the bytes recorded on the measurement host.

The [predeclared experiment plan](value-api-reproduction/experiment-plan.md)
records the questions, controls, stopping rule and practical margin. The
[jar preparation script](value-api-reproduction/Prepare-Comparison.ps1)
replaces runtime entries and verifies the shared harness byte-for-byte.
The [export script](value-api-reproduction/Export-Evidence.ps1) shows how the
original local evidence was condensed into the published JSON.

To reproduce the paired measurements, use PowerShell 7, a JDK 17 installation
and a copy of the published 1.3.0 jar. Pick a new output directory and inspect
`scripts/Get-CpuTopology.ps1` before using this host's affinity mask:

```powershell
$java = 'C:/path/to/jdk-17/bin/java.exe'
$env:JAVA_HOME = Split-Path (Split-Path $java)
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
$published = 'C:/path/to/published/fletch-1.3.0.jar'
$repro = 'docs/benchmark-results/value-api-reproduction'
$run = 'target/value-api-new-run'
mvn -B -ntp -Pbenchmarks package '-Dgpg.skip=true'
New-Item -ItemType Directory -Path $run
& "$repro/Prepare-Comparison.ps1" -BenchmarksJar target/benchmarks.jar `
    -PublishedJar $published -CurrentJar target/fletch-1.3.0.jar `
    -OutputDirectory "$run/artifacts-verified"
$candidate = "$run/artifacts-verified/candidate-benchmarks.jar"
foreach ($batch in @('regression', 'features')) {
    $control = if ($batch -eq 'regression') { "$run/artifacts-verified/published-benchmarks.jar" } else { $candidate }
    ./scripts/Compare-Benchmarks.ps1 -ControlJar $control -CandidateJar $candidate `
        -CasesFile "$repro/$batch-cases.json" -OutputDirectory "$run/$batch" `
        -Rounds 4 -Seed 20260918 -WarmupIterations 3 -MeasurementIterations 5 `
        -Java $java -AffinityMask 340
    ./scripts/Summarize-Benchmarks.ps1 -Directory "$run/$batch"
}
```

Use the [benchmark guide](../benchmarking.md) for the runner's full contract.
Results apply to the runtime commit and source hashes in the provenance file;
rebuilding another revision is a new experiment.

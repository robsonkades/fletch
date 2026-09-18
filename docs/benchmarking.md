# Benchmarks

The [recorded results](benchmark-results/README.md) summarize the completed local
codegen, canonical-cache and session-lifecycle comparisons, including uncertainty
and regressions. Historical results are separate from a new run of the current code.

Build the JMH jar with Java 17 or newer:

```powershell
mvn -B -ntp -Pbenchmarks package '-Dgpg.skip=true'
```

Run one scenario first. The full parameter matrix is intentionally large.

```powershell
java -jar target/benchmarks.jar 'CorpusBenchmark.mapping$' -p shape=UNICODE -p fanout=16 -p source=STRING -p budget=DEFAULT -t 1 -f 2 -wi 3 -i 5 -w 1s -r 1s -prof gc -jvmArgs '-Xms256m -Xmx256m'
```

## What is measured

`ExtractionBenchmark` extracts the NF-e fixture with 1, 50 or 500 repeated items.
Its bare Woodstox loop scans events without constructing the same result. Do not
present that loop as an equivalent deserialization comparison.

`CorpusBenchmark` returns the same complete result from pooled mappings, mapping
sessions and the cursor API:
an ID and an ordered list of all selected values. Each worker rotates through 16
prebuilt documents. Setup validates all three paths against expected results;
`CorpusBenchmarkTest` independently checks the documents with Woodstox.

| Parameter | Values | Meaning |
|---|---|---|
| `shape` | PLAIN, UNICODE, REORDERED, ATTRIBUTES, UNSELECTED | Plain text; accented/Japanese/emoji text and entities; reverse field order; 32 extra attributes; ignored nested subtrees |
| `fanout` | 4, 16, 48 | Number of selected sibling fields |
| `source` | BYTES, STRING, STREAM | Existing bytes; String encoded by extraction; a fresh ByteArrayInputStream |
| `budget` | DEFAULT, BOUNDED | Default limits; finite input/depth/element/name/attribute/text limits |

Mapping compilation and fixture creation happen before timing. Extraction,
decoding, callbacks, result construction and engine cleanup happen inside timing.
`ExtractionBenchmark.planSession` and `planSessionLimited` use the public session
API, including owner/lifecycle checks and trimming. `CorpusBenchmark.mappingSession`
uses the same rotating inputs as `mapping`. Sessions are created and closed once
per worker trial outside timing; this measures reuse over a batch.

String encoding and stream wrapper creation are included in their respective
scenarios. The stream is in memory; this does not measure network or disk I/O.
Mapping may finish scanning earlier than cursor while returning the same result;
neither API performs full-document XML validation.

## Paired changes

The comparison scripts require PowerShell 7. Keep separate jars when comparing
source changes, or use one jar to compare two methods. Record the exact source revision
or source snapshot for each. Create a JSON cases file such as:

```json
[
  {"benchmark":"ExtractionBenchmark.plan","params":{"dets":"50"}},
  {"benchmark":"CorpusBenchmark.mapping","params":{"shape":"UNICODE","fanout":"16","source":"STRING","budget":"DEFAULT"}}
]
```

```powershell
./scripts/Compare-Benchmarks.ps1 -ControlJar target/control.jar -CandidateJar target/candidate.jar -CasesFile cases.json -OutputDirectory target/paired -Rounds 4
./scripts/Summarize-Benchmarks.ps1 -Directory target/paired
```

The runner uses one fresh JVM per jar/case/round. It shuffles case order with a
recorded seed and reverses jar order in the following round. It preserves jar
hashes, environment, schedule, JMH JSON and logs. Choose a new output directory
for every run. Do not compile, test or run another benchmark concurrently.

To compare two methods, set `candidateBenchmark` in each relevant case and pass
the same jar as both `-ControlJar` and `-CandidateJar`:

```json
[
  {"benchmark":"ExtractionBenchmark.plan","candidateBenchmark":"ExtractionBenchmark.planSession","params":{"dets":"50"}}
]
```

Both methods still run in separate, fresh JVMs. Without `candidateBenchmark`,
the runner uses `benchmark` for both variants as before.

### Concurrent workers

Set `threads` per case to compare a shared mapping pool with one session per worker:

```json
[
  {"benchmark":"ConcurrentMappingBenchmark.sharedPool","candidateBenchmark":"ConcurrentMappingBenchmark.session","threads":16,"params":{"dets":"1"}}
]
```

Omitting `threads` still selects one worker. The runner validates the actual JMH
thread count. `ConcurrentMappingBenchmark` returns the complete NF-e result and
checks it in worker setup/teardown. Inputs and the compiled mapping are shared;
sessions are created, used and closed on their owning worker. Trial setup logs
the real worker IDs and their `threadId & 7` pool slots outside the timed loop.

`ConcurrentMappingBenchmark.privatePool` is a diagnostic control with an independent
mapping and pool per worker. It retains pool operations but removes sharing of
the pool array. Its mapping construction is outside timing; this is not a general
recommendation to duplicate mappings. It also duplicates immutable mapping metadata,
so it cannot isolate atomic-array cost from all cache/footprint effects.

Report aggregate documents/time, not a per-worker score. Keep CPU affinity fixed
across worker counts and identify oversubscription explicitly. A closed-loop JMH
thread sweep does not measure arrival-rate capacity, fairness or service p99.

### Java executable and CPU affinity

Select a specific JDK with `-Java 'C:/path/to/jdk/bin/java.exe'`. JVM options can
be supplied with `-JvmArguments`; the default remains `-Xms256m -Xmx256m`.

On Windows, inspect the CPU topology before choosing an affinity mask:

```powershell
./scripts/Get-CpuTopology.ps1 | Format-Table
./scripts/Compare-Benchmarks.ps1 -ControlJar target/control.jar -CandidateJar target/candidate.jar -CasesFile cases.json -OutputDirectory target/pinned -AffinityMask 340
```

Here 340 (`0x154`) means logical CPUs 2, 4, 6 and 8. Choose a mask for your machine;
these numbers are not portable core identities. The runner limits its process and
child JVMs to that set, records the mask and restores its own affinity afterward.
It rejects CPUs outside its original affinity. This option supports a single
Windows process affinity mask; it does not select individual worker threads or
coordinate processor groups on large machines.

Affinity also restricts compiler and GC threads and can change JVM ergonomics.
It does not reserve the cores or fix CPU frequency. Record actual fork affinity
and JVM flags when comparing different placement or JVM conditions.

The summary uses paired log ratios, reports their geometric effect and a
descriptive 95% Student-t interval. Each pair of fresh JVMs is one unit; iterations
within a fork are not independent replications. Two pairs are a coarse screen,
often with very wide intervals. Report every case and retain unfavorable runs.
There is no correction for multiple comparisons. These throughput measurements
do not establish tail latency, retained memory, scaling or a production SLO.

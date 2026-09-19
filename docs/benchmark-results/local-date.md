# Direct ISO LocalDate conversion

Measured on September 18, 2026 (America/Sao_Paulo; September 19 UTC), against
the value-API runtime at `2a05a834ae3781cfdd54b17207af92f66403393f`.

**Ordinary typed dates processed 92.28% more documents per second and allocated
480 fewer bytes per document.** The optimization is retained for review, with
the guard limitations and qualification to the original adoption rule below.

## Results and decision

| Scenario | Control docs/ms | Candidate docs/ms | Paired throughput change [95% interval] | Rounded B/document, control → candidate |
|---|---:|---:|---:|---:|
| Ordinary LocalDate, 100% present | 4,457.14 | 8,578.68 | +92.28% [+81.94%, +103.20%] | 712 → 232 |
| Extended LocalDate, 100% present | 4,359.23 | 4,248.89 | -2.62% [-8.56%, +3.70%] | 712 → 712 |
| Native integer, 100% present | 10,159.60 | 9,672.74 | -4.63% [-13.11%, +4.69%] | 224 → 224 |
| Cursor, 50 NF-e items | 29.28 | 29.22 | -0.11% [-4.23%, +4.18%] | See below |
| Mapping, 50 NF-e items | 28.13 | 27.36 | -2.75% [-9.63%, +4.65%] | 14,208 → 14,208 |

The ordinary-date speedup is 1.92x, with a 67.4% reduction in allocated bytes
per complete document. All four primary pairs improved throughput, by 84.76%
to 101.07%; all eight primary forks repeated the stated allocation levels.
These observations meet the predeclared primary throughput and allocation
criteria. All guard point estimates stayed inside the 5% loss threshold, but
their intervals do not establish performance neutrality; the integer interval,
for example, still permits a material loss.

**Allocation guard qualification:** all four NF-e cursor controls allocated
32,408 B/document. Three candidate forks repeated that level; round 2 allocated
33,208. The candidate average is therefore 32,608, not a fixed 200-byte cost.
The same 33,208-byte level appeared in an unmodified control in the
[prior probe experiment](data/value-api-improvements-probe-pairs.json), round 0,
case 3. This supports an existing variation between JVM runs but does not
explain its cause or establish whether this change affects its frequency.

The original allocation guard is consequently **not fully resolved**. Adoption
accepts this uncertainty explicitly, rather than claiming every original
criterion was satisfied. The reasons are the large repeatable date benefit,
the small stateless implementation, preserved behavior, and the absence of a
new observed allocation level. This qualification remains part of PR review;
the experiment does not justify an unconditional no-regression claim.

## Change and scope

The cursor's typed `LocalDate` conversion recognizes valid ASCII `uuuu-MM-dd`
values with years 0000 through 9999 and constructs the date directly from
the decoded value bytes. It reuses the existing digit and calendar helpers.
Other forms, including signed extended years, still use `LocalDate.parse`.
Invalid inputs also fall back to that parser to preserve its exception type,
parsed text, error index and message. Empty-value behavior is unchanged.

The conversion path is shared by cursor `value`, `attribute` and `firstOf`.
The benchmark measures `value` in a complete small-document extraction.
This change does not optimize the mapping default `XmlValue.as(LocalDate.class)`
or application String callbacks; no public method or dependency was added.

The earlier [API comparison](value-api.md) and
[two rejected prototypes](value-api-improvements.md) remain separate historical
experiments. This candidate removes work from ordinary date parsing; it does
not apply either rejected patch or attempt to fix the unresolved historical
mapping result.

## Correctness

Before measurement, all 752 core tests passed. Forty additional test invocations
cover the complete 146,097-day Gregorian cycle from 2000 through 2399, boundary
years including zero, signed extended years and the supported JDK extrema,
malformed text, impossible dates and JDK exception details. The cycle uses
JDK-generated expected dates and offset byte spans with surrounding sentinels.
Decoded attributes, mixed CDATA/text and extended-year choices are checked
across cursor, mapping and session input forms.

The benchmark oracle now checks eight methods over all 64 document variants,
two full rotations and 0%, 50% and 100% presence. The added extended-date method
uses the same extraction/result shape with dates beginning at +12026-09-01.

The final Java 17 core/generator/example reactor passed 807 tests without
failures or skips. Binary/source compatibility with published 1.3.0, existing
interface implementations and the documented conversion example passed;
`jdeps` still reports only `java.base`. The measured source hashes were checked
again after measurement and remained unchanged.

## Measurement contract

One operation parses a complete XML document and returns the converted value
and a trailing sibling. The small-document fixtures rotate through 64 immutable
byte arrays; ordinary dates begin at 2026-09-01. Both date comparisons use
100% presence. Input, extractor and mapping construction are outside timing;
parsing, conversion, result construction and engine cleanup are included.
The NF-e guards use the unchanged 50-item fixture and return complete records.

Both benchmark jars contain the identical harness, fixtures and dependencies:
preparation checked all 29 runtime/metadata entries against their library jars
and all 4,047 other entries for equality. The control is the pre-optimization
value-API runtime, **not published 1.3.0**, despite the preparation script's
`published-benchmarks.jar` filename.

JMH 1.37; Temurin 17.0.20.1+1; Windows 11 Pro 10.0.26200; i7-13700K;
31.75 GiB visible memory; one worker, G1 and a fixed 256 MiB heap; GC profiler.
Affinity mask 340 selects logical CPUs 2, 4, 6 and 8, one hardware thread on
each of four P cores. It does not reserve the cores or fix CPU frequency.
The existing high-performance power plan remained unchanged. An unrelated,
mostly idle Java process was present. No build, test or other benchmark ran
concurrently with scored measurements.

Forty fresh JVMs provide four paired comparisons per case, ten one-second
warmup iterations and five one-second measurement iterations. Cases are
shuffled with seed 20260920 and variant order reverses across rounds. All
scheduled runs are retained. The paired JVMs, not their individual iterations,
are the comparison units. Changes are geometric means of paired ratios;
95% intervals use Student-t over the four paired log ratios, without a
multiple-comparison correction. Absolute scores are arithmetic fork means.

The [predeclared plan](local-date-reproduction/plan.md) requires at least
128 fewer allocated bytes/document in every ordinary-date pair, at least
10% primary throughput improvement with a positive interval, no guard point
loss over 5%, and no unexplained guard allocation increase. Wide guard
intervals remain uncertainty rather than equivalence. The sample size targets
the predicted large effect, not precise certification of tiny guard changes.

Allocation means allocated bytes per complete document, not retained heap,
RSS or guaranteed GC-pause reduction. No CPU-frequency/thermal telemetry or
compilation trace was collected in this batch. Within-fork trajectories can
flag drift but cannot prove compilation stability. These are local Java 17
results for the listed inputs, not production latency or JDK 21/25 speed.
One of 40 forks crossed the descriptive +/-5% drift indicator (last two
measurement iterations versus first two): extended-date candidate, round 0,
+6.62%. That fork remains in every reported result.

## Evidence and reproduction

[The data](data/local-date.json) contains environment and source hashes,
artifact verification, schedule, all fork scores, warmup/measurement
trajectories, allocation/GC counters and original JSON/log hashes.
The [exporter](local-date-reproduction/Export-Evidence.ps1) produces UTF-8
without BOM and LF newlines. Complete raw logs, JSON and frozen jars remain
in the local `target/local-date-performance` archive.

Build the before library from an isolated checkout of `2a05a83` and the
candidate library from the source identified in the data, using JDK 17.
Build the candidate benchmark harness with
`mvn -B -ntp -Pbenchmarks package '-Dgpg.skip=true'`.
Prepare identical harness jars using
[Prepare-Comparison.ps1](value-api-reproduction/Prepare-Comparison.ps1), with
the before library as `-PublishedJar` and the candidate as `-CurrentJar`.
Then run:

```powershell
./scripts/Compare-Benchmarks.ps1 `
    -ControlJar "$run/artifacts/published-benchmarks.jar" `
    -CandidateJar "$run/artifacts/candidate-benchmarks.jar" `
    -CasesFile docs/benchmark-results/local-date-reproduction/cases.json `
    -OutputDirectory "$run/pairs" -Rounds 4 -Seed 20260920 `
    -WarmupIterations 10 -MeasurementIterations 5 -Java $java -AffinityMask 340
./scripts/Summarize-Benchmarks.ps1 -Directory "$run/pairs"
```

Set `$run` to a fresh output directory and `$java` to the JDK executable;
inspect CPU topology before choosing an affinity mask for another host.
Do not run builds or other benchmarks concurrently. A different source,
JDK, workload or host is a new experiment.

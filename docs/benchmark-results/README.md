# Recorded performance results

The [value API study](value-api.md) compares the new conversions, presence checks
and fallback with release 1.3.0 and explicit alternatives on Java 17. It records
an observed mapping throughput loss and inconclusive cursor comparisons.

The historical studies below informed the implementation. They compare the named variants
at their recorded checkpoints, not the entire change against release 1.2.0.
The final delivery checks rebuild and test the code; they do not repeat these
completed studies or turn their estimates into production guarantees.

Throughput measurements used JMH 1.37 on Java 21, an i7-13700K running Windows 11,
one worker, a 256 MiB G1 heap and affinity to eight separate physical cores.
Each case used four pairs of fresh JVMs with alternating variant order, ten
one-second warmup iterations and five one-second measurements. The statistical
unit is the JVM pair. Reported changes are geometric means of paired ratios;
95% intervals are descriptive t intervals over log ratios, without multiplicity
correction. Every scheduled fork was retained.

## Optional codegen

Both routes used the same jar and returned the same complete result. Generation,
mapping construction and fixture/oracle setup happened before timing. The 48
attribute comparison used the scanner with the duplicate-attribute optimization.
The NF-e case used the repository's original one-item XML and a synthetic
50-item expansion, checked against an independent DOM oracle.

| Fixture | Generated / DSL throughput change | Descriptive 95% interval | B/document, both routes |
|---|---:|---:|---:|
| 48 selected attributes | +2.70% | [-0.58%, +6.08%] | ~208 |
| NF-e, 1 item | +0.17% | [-2.76%, +3.19%] | ~1,192 |
| NF-e, 50 items | +3.19% | [+2.11%, +4.27%] | ~14,208 |

None met the predefined 5% practical-benefit threshold. One generated attribute
fork had -5.26% within-measurement drift and remains included; no NF-e fork crossed
the same +/-5% indicator. This does not establish general superiority or equivalence.
Codegen remains optional and adds classes and name tables at startup.

Data: [attribute pairs](data/codegen-attributes-summary.json),
[NF-e pairs](data/codegen-nfe-summary.json).
Use the [codegen build and benchmark guide](../../codegen/README.md) for a new run.

## Canonical value cache

This comparison changed only the fixed-size cache to the retained adaptive cache
with a local hash mixer. Values above 64 UTF-8 bytes and exhausted eight-slot
probe windows still fall back to decoding a new String. Equality checks use the
complete bytes; growth preserves already-cached String identities.

Instrumentation measurements of the three tables and their reachable keys/values
agreed on Java 17, 21 and 25 with compressed references, traditional headers,
CompactStrings and eight-byte alignment:

| Corpus | Slots, fixed → adaptive | Bytes in cache graph, fixed → adaptive |
|---|---:|---:|
| Two state codes | 1,024 → 16 | 16,576 → 448 (-97.30%) |
| All 27 state codes | 1,024 → 64 | 18,376 → 3,016 (-83.59%) |
| 4,096 distinct values | 1,024 → 1,024 | 122,928 → 122,928 |
| 32 complete hash collisions | 1,024 → 1,024 | 17,264 → 17,264 |

This is reachable cache-graph size, not RSS or exclusive retained size. Growing
all the way to 1,024 slots allocates 16,416 extra temporary array bytes. Complete
collisions retain only eight values despite growing to the maximum capacity.

| Warmed fixture | Adaptive / fixed throughput change | Descriptive 95% interval |
|---|---:|---:|
| Canonical NF-e, 1 item | -1.21% | [-3.84%, +1.50%] |
| Canonical NF-e, 50 items | +0.91% | [-3.56%, +5.58%] |
| NF-e without canonicalization, 1 item | -0.23% | [-4.17%, +3.87%] |
| 27 state codes | +2.33% | [-4.13%, +9.22%] |
| 4,096 distinct values | +2.06% | [-0.67%, +4.87%] |
| 32 complete hash collisions | -3.00% | [-10.64%, +5.30%] |

All six point estimates stayed within the predefined maximum 5% throughput-loss
criterion, and warmed allocation per document was unchanged. All intervals cross
zero; the collision interval still permits a loss greater than 5%. The decision
rests on the measured small-cache memory reduction and that point-estimate
criterion, not a demonstrated absence of throughput regression. No fork crossed
the +/-5% drift indicator in this 48-fork study.

Data: [paired throughput](data/canonical-cache-summary.json),
[cache memory](data/canonical-cache-memory.json).

## Session lifecycle

This separate study used the same adaptive-cache core in both routes. It opened
and closed either one session per XML or one session per batch, inside timing.
Both started with empty caches. A JMH operation was a whole batch; AverageTime
in microseconds and allocation were divided by its fixed document count.
Mapping construction, input preparation and complete-result oracles were outside
timing. Classes and code were warmed; this does not measure JVM startup, individual
rehash latency or request percentiles.

| Fixture | Documents/batch | Batch / fresh-session time change | Descriptive 95% interval | B/document, fresh → batch |
|---|---:|---:|---:|---:|
| Two state codes | 128 | -51.93% | [-53.07%, -50.76%] | 1,240 → 34.06 |
| 27 state codes | 128 | -46.18% | [-51.28%, -40.53%] | 1,240 → 60.88 |
| 4,096 distinct values | 4,096 | -37.06% | [-46.81%, -25.52%] | 1,272 → 106.23 |
| 32 complete hash collisions | 128 | +7.32% | [+2.90%, +11.93%] | 1,272 → 341.69 |
| NF-e, 1 item | 64 | -3.29% | [-8.42%, +2.12%] | 2,464.03 → 1,117.40 |
| NF-e, 50 items | 64 | +1.20% | [-7.32%, +10.51%] | 15,480.26 → 14,133.64 |

Lower time is better. Allocation fell in every case, but all four collision pairs
took longer with a batch session. NF-e timing was inconclusive. Eighteen of 48
forks crossed the +/-5% drift indicator (last two versus first two measurements);
their trajectories remain included, and no cause was established.

`Xml.extract()` already pools engines. This experiment does not compare sessions
with that pooled API and does not support opening a new session for every XML.
Use [sessions](../mapping-sessions.md) for explicit worker ownership and measure
the application's actual corpus and lifecycle.

Data: [paired time and allocation](data/session-lifecycle-summary.json).

## Evidence scope

The JSON files retain the original schedules, measured jar hashes, per-fork
scores, paired summaries and available iteration trajectories. The
[provenance manifest](provenance.json) records their original local archive paths
and SHA-256 hashes, plus hashes of the published UTF-8 JSON normalized to LF.
Absolute paths and `resultFile` names inside the data identify the original runs;
they are not links to files in this PR.

The complete research archive, frozen jars, source snapshots, raw console logs
and compiler/JFR captures remain preserved locally. This selected evidence is
not a standalone reconstruction bundle for every historical experiment. New
measurements should record their own exact revision, environment and complete
results using the [benchmark guide](../benchmarking.md).

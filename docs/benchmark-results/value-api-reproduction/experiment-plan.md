# Value API performance experiment — declared before implementation/runs

Question: on this host and JDK 17, does candidate 8ac74fec change existing NF-e
extraction throughput/allocation versus published Fletch 1.3.0, and what does
opting into the new value APIs cost versus equivalent explicit code?

Decision: report local throughput and B/document; use ±5% throughput as a
practical triage margin, not a production SLO or an equivalence guarantee.
Do not optimize based on one noisy mean. No production exposure is supplied.

Regression cases: ExtractionBenchmark.cursorDocOrder at 1 and 50 items;
ExtractionBenchmark.plan at 50 items (unchanged mapping negative control).
Use identical benchmark/dependency bytes for both runtime jars; replace only
the runtime files enumerated by the published/current library jars. Verify
every replacement and the equality of all other jar entry contents.

Feature cases on the candidate only: native integer versus valueWith integer
(100% present); native integer versus exists then native read (100% and 50%
present); manual String + null check + Integer.valueOf versus valueWith with
lazy fallback (50% present); manual String + LocalDate.parse versus typed
LocalDate (100% present). Each operation extracts one small document, returns
the converted value plus a trailing sibling, and rotates 64 byte-array inputs.
Integer values exceed the boxing cache. Missing inputs are deterministically
interspersed. No empty values: exists tests structure and is not equivalent
to checking for nonempty text in general. Fixtures/callback construction and
file I/O are excluded; parsing, conversion, result objects, and cleanup are
included. State is thread-owned; single worker; JMH throughput in ops/ms.

Environment: Windows 11, Intel i7-13700K, local JDK 17.0.20.1+1, JMH 1.37,
-Xms256m -Xmx256m, gc profiler. Pin JVMs to logical CPUs 2,4,6,8 (mask 340),
one logical CPU per four P cores per Get-CpuTopology. Affinity does not reserve
cores or fix clocks. Record topology, power scheme, effective JVM flags and
background processes; leave OS power/turbo policy unchanged.

Design: two sequential paired batches (regression, opt-in APIs), 4 pairs/case,
fresh JVM per variant/round, shuffled case order with seed 20260918 and reversed
variant order each round. 3 x 1 s warmup, 5 x 1 s measurement, 1 fork/run.
Total: 64 measured JVMs. Pair is the experimental unit. Report geometric
throughput ratios and descriptive 95% Student-t intervals on paired log
ratios, all pairs retained, no multiple-comparison correction.

Before timing: run semantic oracles across two complete rotations and all
presence mixes, check benchmark discovery/generated harness and artifact
identity. Existing NF-e setup validates cursor/mapping equality and increasing
document size is a scaling control. String integer conversion and repeated
start-tag scanning are candidate cost mechanisms, not predetermined results.

Stop after the declared batches. Inspect all per-fork warmup/measurement
trajectories and allocation counts; flag first-half/second-half measurement
drift over 5% descriptively. Preserve failed/incomplete runs. If host/lifecycle
instability dominates, report inconclusive instead of silently retrying or
selecting faster runs. Additional diagnostic work requires an identified
unresolved concern. No concurrent builds/tests/other benchmarks during timing.

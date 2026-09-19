# Value API improvement investigation — before code changes and new runs

Starting source: 38d89b79066a27a299302d38531567b044df0239, runtime unchanged
from 8ac74fec. Preserve target/value-api-performance and all its results.
Question: can a bounded, semantics-preserving change reduce actual work in
exists/read or common conversions without material regressions elsewhere?

1. Repeat the mapping-50 published/current comparison with frozen identical
   harnesses, four fresh-JVM pairs, ten 1s warmups and five 1s measurements.
   The prior -6.65% is a hypothesis to reproduce, not an assumed diagnosis.
2. Collect bounded diagnostic compilation/inlining logs (not decision scores)
   for current cursor and published/current mapping. Inspect C2 identities and
   relevant calls; do not infer a missed inline from an unrelated C1 refusal.
3. Consider at most two isolated candidates: reuse an exists start-tag probe
   using pooled-engine scratch (no per-cursor/per-call allocation); separate
   uncommon conversion dispatch only if compiler evidence supports the target.
   No new conversion API, parser semantics or global JVM tuning.
4. A selected candidate gets four fresh-JVM pairs per case, 10 x 1s warmup and
   5 x 1s measurement, with GC profiler, one worker. Primary cases are
   existsThenInteger with 100% and 50% presence; guards are nativeInteger100,
   NF-e cursor50, and mapping50. If converter dispatch is selected, include
   typedDate100. Expected benefit: avoid repeated name/start-tag validation;
   refutation: no material throughput improvement or new guard/allocation cost.

Use the same JDK17, G1 256MiB heap, four P-core affinity mask 340, recorded
environment and shuffled/reversed paired order, seed 20260919. JVM pair is
the comparison unit. Report all runs and descriptive 95% log-ratio t intervals;
no multiple-testing correction. Treat >5% last/first measurement-half drift
as a diagnostic indicator, never a reason to remove a fork. Do not build or
test while timing. No forced inlining in decision measurements.

Adoption: preserve behavior and allocation, require at least 5% point benefit
with a positive effect interval in an intended primary case, and no guard's
point loss exceeding 5%. Broad guard intervals are residual uncertainty, not
equivalence. An unexplained allocation increase requires diagnosis before
adoption. Keep at most one clearly supported candidate (or a separately
validated combination); reject merely attractive source edits. Further
measurements require a concrete unresolved concern; no retries for a better
score. If bounded candidates do not qualify, retain the original code and
document findings. Final checks include the full reactor, API compatibility,
new cursor replay/ownership/reuse cases if probe state is changed, and CI.

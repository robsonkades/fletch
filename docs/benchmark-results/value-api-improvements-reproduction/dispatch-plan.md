# Second bounded candidate: small String dispatch

Chosen after the original-runtime C2 diagnostic, before its implementation or
measurement. The original 256-byte TypeConverter.convert had 29 captured C2
inline refusals (already compiled into a big method) and one successful inline
across compilation tasks/caller trees. These are compilation decisions, not
dynamic execution frequencies or a CPU-share estimate.

Candidate: retain empty-span and String handling in convert; delegate all other
types to a private convertNonString method. This targets String conversion at
the cursor's observed failed-inline sites without changing any parser or API.
Do not combine with the exists-probe prototype. Restore its source changes
before building this candidate; preserve its frozen jars, patch and all runs.

Primary cases: NF-e cursor with 50 items and nativeInteger with 100% presence.
Guards: NF-e mapping50, typedDate100 and customInteger100. Same complete-result
benchmarks and inputs as the first study, identical harness bytes in both jars.
Four pairs/case, 10 x 1s warmup and 5 x 1s measurement, gc profiler, seed 20260919,
JDK17, affinity340, -Xms256m -Xmx256m. No concurrent builds/tests or benchmarks.
At least 5% primary point improvement with a positive descriptive 95% interval,
no guard point loss greater than 5%, and no unexplained allocation increase.
Inspect C2 diagnostics separately to confirm/refute the predicted inlining
change. Keep all failures, trajectories and pairs. No third candidate is planned.
If neither bounded candidate qualifies, retain the original implementation.

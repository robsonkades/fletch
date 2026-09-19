# Direct LocalDate conversion experiment

Starting commit: 2a05a834ae3781cfdd54b17207af92f66403393f.
Written before implementation and measurements. This is a new bounded
follow-up to the user's request, not a retry of either rejected prototype.

Hypothesis: the cursor's LocalDate conversion currently constructs a String
and uses the generic ISO formatter even for valid ASCII uuuu-MM-dd dates.
Recognizing that common form, validating its calendar fields, and constructing
LocalDate directly should remove substantial temporary allocation and work.
All other forms and invalid values must retain LocalDate.parse as the fallback.
No changes to custom converter callbacks, mapping defaults or public APIs.

One candidate only. Preserve the old parser result and exception contract,
empty handling, decoded XML text, and offset spans. Differential tests include
the complete 400-year Gregorian cycle, year bounds, extended/negative years,
invalid dates and malformed text, plus existing conversion integration tests.

Primary workload: ValueApiBenchmark.typedDate, 100% presence, rotating 64
ordinary dates and observable complete document results. Add an analogous
typedExtendedDate benchmark for the fallback using year 12026. Fixture
construction stays outside timing. Both runtime jars share the identical
new harness; oracle tests cover all 64 variants and missing-value mixes.

Guards: typedExtendedDate100, nativeInteger100, NF-e cursor50 and mapping50.
Four fresh-JVM pairs per case (40 JVMs), ten 1s warmup and five 1s measurement
iterations, gc profiler, one worker. JMH 1.37, Temurin 17.0.20.1+1, G1 with
-Xms256m -Xmx256m, affinity 340, existing high-performance power plan. Seed
20260920 with shuffled cases and reversing variant order. No builds/tests or
other benchmark processes concurrently with measurement. Host clocks/thermals
are not controlled. Preserve environment, artifacts and every scheduled run.

Adoption requires at least 128 fewer allocated bytes/document in the primary
case across all paired forks, at least 10% paired throughput improvement with
a positive descriptive 95% log-ratio t interval, no guard point loss over 5%,
and no unexplained guard allocation increase. Guard intervals remain residual
uncertainty, not proof of equivalence. Four pairs should distinguish the
predicted large effect; no retries for a better score. Retain >5% last-two vs
first-two drift flags without excluding runs. Report local throughput and
allocated bytes, not production latency, retained memory or JDK21/25 speed.

If adopted, run full reactor/API compatibility checks and JDK17/21/25 CI;
publish results and update the existing draft PR. Otherwise revert the sole
candidate and report the outcome. Do not add unrelated parsers or APIs.

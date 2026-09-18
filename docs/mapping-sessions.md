# Reusable mapping sessions

`XmlMapping.openSession()` creates a dedicated engine for a sequence of documents.
`openSession(XmlLimits)` fixes the resource limits for every extraction. The
returned `XmlMappingSession<T>` accepts `byte[]`, `String` and `InputStream` and
implements `AutoCloseable` without checked close exceptions.

## Worker ownership

Create the session inside the worker that uses it:

```java
executor.submit(() -> {
    try (var session = mapping.openSession(limits)) {
        for (byte[] document : batch) {
            consume(session.extract(document));
        }
    }
});
```

The caller owns the executor and the session. The task closes the session after
its last synchronous extraction. A session is bound to its creating `Thread`,
including when that thread is virtual. Creating it on a coordinator and passing
it to a worker is rejected. Async continuations that switch threads need their
own sessions. External synchronization does not enable thread transfer.

The compiled mapping can be shared; its callbacks must support concurrent use
with separate drafts. Each draft supplier must produce a fresh draft. Input
arrays must remain unchanged until extraction returns. Callbacks run on the
session owner thread without a library lock.

## Lifecycle and failures

| Operation | Behavior |
| --- | --- |
| Extract while open and idle | Uses a fresh draft and resets all per-document budgets |
| Parse, I/O, conversion or callback failure | Propagates the failure, releases input/draft references, permits the next extraction |
| Recursive extraction on this session | `IllegalStateException` before touching the active engine |
| Close from this session's callback | `IllegalStateException`; the active extraction remains intact |
| Extract or close from another thread | `IllegalStateException`, including idle/closed sessions |
| Close on the owner thread | Detaches the engine; repeated calls have no effect |
| Extract after close | `IllegalStateException` |
| Null input on an available session | `NullPointerException`; session remains usable |
| Null limits at creation | `NullPointerException` |

Callback side effects are not rolled back. A callback may perform nested work
through another session or `Xml.extract(..., mapping)`. Neither stream reads nor
callbacks acquire a session timeout or cancellation mechanism: extraction blocks
according to the input and callback behavior. `close()` does not cancel an active
extraction and cannot be used from another thread as a cancellation signal.

## Input, memory and limits

Input streams remain caller-owned on success, failure and session close. UTF-8
and US-ASCII use the existing sliding window; Latin-1 and UTF-16 are buffered and
transcoded. A mapping can finish before the stream ends, while read-ahead may
consume bytes after the last selected value. Do not use the remaining stream
position to split concatenated documents. See [resource limits](resource-limits.md).

The byte array or stream, binding value and draft references are cleared after
each extraction. Scratch buffers are reused and outliers are trimmed using the
same policy as the pool. Cleanup does not overwrite scratch contents. The bounded
`asCanonical()` cache intentionally retains decoded values between documents.
Closing detaches the entire engine, including its buffers and cache, so they can
be reclaimed. It does not return the engine to the mapping pool.

Sessions consume memory per worker and allocate an engine when opened. Keep one
for the batch; opening one per document loses the amortization. Parsing, draft,
result, text conversion and captured-callback allocations still apply. Public
sessions preserve selective validation and early-exit behavior; they do not add
whole-document XML validation.

## API compatibility

This is an additive API: one final public class and two new factory methods on
the existing final mapping class. Existing `Xml.extract` signatures and behavior
are unchanged. No new runtime dependency, preview API, or Java baseline change
is required; the project remains on Java 17. The two factories differ only by
explicit/default limits. The three `extract` overloads follow the existing input
forms; a literal `null` must be cast to select an input form.

## Performance

The change removes the per-document pool exchange and return, adding owner,
closed-state and reentrancy checks. It keeps the same extraction and trimming
work. Expected benefit is workload-dependent and must be measured, including
the public wrapper's checks. The benchmark measures reusable sessions after
creation, not the cost of opening and closing one per document.

Use sessions when explicit worker ownership fits your processing model, and measure
your workload before adopting them for speed. `Xml.extract()` already reuses engines
through its pool; opening a new session for each XML adds setup and discards its caches.

A separate [lifecycle experiment](benchmark-results/README.md#session-lifecycle)
included opening and closing sessions inside the measurement. Reusing one session
per batch reduced allocation in all six local fixtures, but the complete-collision
fixture took 7.32% longer and NF-e timing was inconclusive. This compares a session
per batch with a session per document; it does not measure replacement of the pooled
API. See the [benchmark guide](benchmarking.md) for pool/session comparisons.

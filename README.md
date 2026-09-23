# SeqLock

A writer-biased optimistic concurrency primitive for Java: a single writer thread mutates shared
state without ever blocking, and any number of reader threads read that state without acquiring a
lock, retrying only if a write happened to overlap their read.

## Why

The write path is the whole point: `beginWrite()`/`endWrite()` always succeed on the first
attempt, in a bounded, fixed number of steps — no lock to wait for, no compare-and-swap retry
loop, no dependence on what readers are doing. Contrast that with most "lock-free" designs, where
the writer itself is the thing that loops (CAS, retry, CAS again) under contention; `SeqLock`
avoids that entirely because it only ever supports one writer, so there's nothing to contend with
on the write side.

Readers get an intentionally different deal: no lock either, but they *may* need to retry if a
write happened to land mid-read. A `ReentrantReadWriteLock` still makes every reader take a lock,
so readers contend with each other (and with the writer) even when nothing is being written.
`SeqLock` doesn't care how often reads happen relative to writes — writes stay cheap and
unconditional either way — but it does assume reads are cheap enough to redo: the more often (or
the longer) writes happen, the more often a given reader's window may land on one and need a
retry, so keep both the write section and the read section short.

This trades away multi-writer support and blocking semantics for that. If you need either, use
[`java.util.concurrent.locks.StampedLock`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/StampedLock.html)
instead.

## Requirements

Java 8+. `seqlock` ships as a multi-release JAR: the same jar runs on Java 8, but automatically
uses `VarHandle`/`Thread.onSpinWait()` instead of internal APIs when run on Java 11+.

## Installation

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.deephaven.seqlock:deephaven-seqlock:<version>")
}
```

```xml
<!-- pom.xml -->
<dependency>
  <groupId>io.deephaven.seqlock</groupId>
  <artifactId>deephaven-seqlock</artifactId>
  <version><!-- see the latest release --></version>
</dependency>
```

This project is still pre-1.0 (currently `0.1.0-SNAPSHOT`) and not yet published to Maven Central
— check with whoever gave you access to this repository for how to depend on it in the meantime.

## Usage

Create one `SeqLock` per piece of state it protects:

```java
SeqLock lock = SeqLock.newInstance();
int sharedX, sharedY;
```

**Writing** (single writer thread only):

```java
lock.beginWrite();
try {
    sharedX = 42;
    sharedY = 99;
} finally {
    lock.endWrite();
}
```

That's the entire writer protocol — unlike the reader below, there's no retry loop here, because
there's nothing for `beginWrite()`/`endWrite()` to ever wait on or fail to acquire.

**Reading, retrying until consistent** (any number of threads):

```java
long stamp;
int localX, localY;
do {
    stamp = lock.beginRead();
    localX = sharedX;
    localY = sharedY;
} while (!lock.validate(stamp));
// localX, localY are consistent
```

**Reading once, without spinning** — useful when a stale read is an acceptable outcome, not just
an intermediate step to retry away:

```java
long stamp = lock.tryBeginRead();
if (lock.isReadStamp(stamp)) {
    int localX = sharedX;
    int localY = sharedY;
    if (lock.validate(stamp)) {
        // localX, localY are consistent
    }
}
```

`beginRead()` differs from `tryBeginRead()` in that it spins internally until no write is in
progress, so its result is always a valid stamp to read against — you only need to retry after
`validate()` fails, not after `beginRead()` itself.

## The memory model contract

Fields read or written under a `SeqLock` do **not** need to be `volatile` — the lock's own fences
establish the ordering for everything between `beginWrite`/`endWrite` and `beginRead`/`validate`.

Keep the code between `beginRead()` and `validate()` to plain copying: load the shared fields, store
them into locals (or a caller-owned object, as in the examples above), nothing else. Whether that
copy is written inline or in a method called from there doesn't matter. Copying a reference is fine
when the object behind it is immutable (`String`, `Instant`, an unmodifiable collection the writer
never touches again) and the writer swaps in a new object rather than mutating the old one:
validating the reference then validates everything reachable through it. It is not fine for an
object the writer mutates in place — a field read through such a reference after `validate()`
happens outside the window, unvalidated. Everything else — computation on the values, or waiting for
a condition — belongs after a successful `validate()`, operating on the copies.

## Protecting a group of related fields

`SeqLock` itself only knows about one sequence counter — it has no idea how many fields you're
protecting or how they relate to each other. That choice is the caller's, and it changes what
consistency guarantee readers actually get. This comes up constantly for a hot path that's updating
several related fields together — say, a request counter and an accumulated latency total for it —
and wants to publish them to any number of concurrent readers.

**Recommended: accumulate locally, publish as a batch.** Have the hot path write to plain
(non-shared, non-`volatile`) local fields with no locking at all, and periodically copy all of them
into the shared, `SeqLock`-protected fields in a single `beginWrite()`/`endWrite()` pair:

```java
final class RequestStats {
    private final SeqLock lock = SeqLock.newInstance();

    // reader-visible; written only inside beginWrite()/endWrite(), read only inside
    // beginRead()/validate() (via read()/snapshot())
    private final Stats shared = new Stats();

    // writer-thread-local; nothing else ever touches this, so it needs no protection at all
    private final Stats local = new Stats();

    void recordSuccess(long elapsedNanos) {          // the hot path: no lock, no publish
        local.count++;
        local.totalElapsedNanos += elapsedNanos;
    }

    void publish() {                                 // call this on whatever interval you choose
        lock.beginWrite();
        try {
            shared.copyFrom(local);
        } finally {
            lock.endWrite();
        }
    }

    void read(Stats out) {                            // any reader thread, any time -- no allocation
        long stamp;
        do {
            stamp = lock.beginRead();
            out.copyFrom(shared);
        } while (!lock.validate(stamp));
    }

    static final class Stats {
        long count;
        long totalElapsedNanos;

        void copyFrom(Stats other) {
            count = other.count;
            totalElapsedNanos = other.totalElapsedNanos;
        }
    }
}
```

Because `count` and `totalElapsedNanos` are always written together inside one
`beginWrite()`/`endWrite()` pair, a reader that gets a validated stamp is guaranteed to see them as
they existed at the *same instant* — never `count` from one publish and `totalElapsedNanos` from
the next. How often `publish()` runs is entirely up to you: once per batch, once a second, whatever
matches how fresh your downstream consumers need the data to be.

`Stats` plays three roles here — the shared, published state; the writer-local accumulator; and,
via `read(Stats)`, an allocation-free target a caller supplies for a read — rather than three
separate sets of fields, so adding a new field only means touching `Stats` once. A caller who
doesn't mind allocating can wrap `read(Stats)` in a convenience `snapshot()` that allocates a fresh
`Stats` and returns it; a caller on their own hot path can instead keep one `Stats` instance and
call `read(Stats)` repeatedly, exactly as `RequestStatsExampleTest`'s reader threads do below.

See
[`RequestStatsExampleTest`](seqlock/src/test/java/io/deephaven/seqlock/RequestStatsExampleTest.java)
for a complete, runnable version of this — including a concurrent stress test that fails if any
reader ever observes `count` and `totalElapsedNanos` out of sync with each other (and which really
does fail if you break the pattern; see that test's neighbor,
[`RequestStatsExample`](seqlock/src/test/java/io/deephaven/seqlock/RequestStatsExample.java)).

**A setter per field, each with its own `beginWrite()`/`endWrite()`, is a *different* guarantee.**
Each individual field is atomically consistent on its own, but there's no guarantee that two fields
set by two *separate* calls were ever true at the same instant. That's fine when the fields are
genuinely independent of each other (a request counter here, an unrelated config flag there); it's
a bug waiting to happen if a reader expects two independently-set fields to be mutually consistent.

**A setter per logical group works for the same reason batch-publish does.** If `count` and
`totalElapsedNanos` above always change together, but some other field — say, a rarely-changing
concurrency limit — is independent of both, giving that field its own `beginWrite()`/`endWrite()`
setter is fine: it's its own group, with its own internal consistency, uncoupled from the request
stats.

## API reference

See the [`SeqLock` javadoc](seqlock/src/main/java/io/deephaven/seqlock/SeqLock.java) for
the full method list, including `beginReadInterruptible()` for a spin that responds to thread
interruption, and the `tryBeginRead(pollInterval, totalWait, unit)` variants that sleep between
attempts instead of spinning.

## License

[Apache License, Version 2.0](LICENSE).

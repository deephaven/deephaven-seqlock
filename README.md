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

A hot path often updates several related fields together — say, a request counter and the
accumulated latency total behind it — and wants to publish them to any number of concurrent readers,
who need to see them as a consistent set. Below are two shapes of that, sharing one `Stats` holder:
`RequestStats` makes every update its own write section, while `RequestStatsBatched` has the hot
path write to plain (non-shared, non-`volatile`) local fields with no locking at all and
periodically copies all of them into the shared fields in a single `beginWrite()`/`endWrite()` pair.

```java
final class Stats {
    long count;
    long totalElapsedNanos;

    void copyFrom(Stats other) {
        count = other.count;
        totalElapsedNanos = other.totalElapsedNanos;
    }
}

final class RequestStats {
    private final SeqLock lock = SeqLock.newInstance();

    // written only inside beginWrite()/endWrite(), read only inside beginRead()/validate()
    private final Stats shared = new Stats();

    /** Writer thread only. */
    void recordSuccess(long elapsedNanos) {
        lock.beginWrite();
        try {
            shared.count++;
            shared.totalElapsedNanos += elapsedNanos;
        } finally {
            lock.endWrite();
        }
    }

    /** Any reader thread, any time -- no allocation. */
    void read(Stats out) {
        long stamp;
        do {
            stamp = lock.beginRead();
            out.copyFrom(shared);
        } while (!lock.validate(stamp));
    }
}

final class RequestStatsBatched {
    private final SeqLock lock = SeqLock.newInstance();

    // written only inside beginWrite()/endWrite(), read only inside beginRead()/validate()
    private final Stats shared = new Stats();

    // writer-thread-local; nothing else ever touches this, so it needs no protection at all
    private final Stats local = new Stats();

    /** Writer thread only. The hot path: no lock, no publish. */
    void recordSuccess(long elapsedNanos) {
        local.count++;
        local.totalElapsedNanos += elapsedNanos;
    }

    /** Writer thread only. Call this on whatever interval you choose. */
    void publish() {
        lock.beginWrite();
        try {
            shared.copyFrom(local);
        } finally {
            lock.endWrite();
        }
    }

    /** Any reader thread, any time -- no allocation. */
    void read(Stats out) {
        long stamp;
        do {
            stamp = lock.beginRead();
            out.copyFrom(shared);
        } while (!lock.validate(stamp));
    }
}
```

Both give readers the same guarantee: `count` and `totalElapsedNanos` are always written together
inside one `beginWrite()`/`endWrite()` pair, so a reader that gets a validated stamp sees them as
they existed at the *same instant* — never `count` from one update and `totalElapsedNanos` from the
next. They differ in where the cost lands and how fresh the data is:

- **`RequestStats`** puts a write section on the hot path. `beginWrite()`/`endWrite()` are cheap and
  unconditional, but not free, and every one of them is a chance to overlap a reader's window and
  send that reader around its loop again — the busier the hot path, the more often readers retry.
  In exchange, readers always see the latest recorded value, and there is nothing to schedule.
- **`RequestStatsBatched`** takes the hot path down to two plain increments — no fences, no volatile
  writes — and writes to the shared fields only in `publish()`, so readers rarely collide with a
  write no matter how hot the path is. In exchange, what readers see is only as fresh as the last
  `publish()`, and you have to decide when to call it: once per batch, once a second, whatever
  matches how fresh your downstream consumers need the data to be.

Start with `RequestStats`; reach for `RequestStatsBatched` when the hot path is hot enough that the
per-update write section shows up, or when consumers are fine with data that lags by a publish
interval anyway.

In both, `Stats` doubles as the shared state and as the allocation-free target a caller supplies to
`read(Stats)` (and, in the batched version, as the writer-local accumulator too), rather than
separate sets of fields, so adding a new field only means touching `Stats` once. A caller who
doesn't mind allocating can wrap `read(Stats)` in a convenience `snapshot()` that allocates a fresh
`Stats` and returns it; a caller on their own hot path can instead keep one `Stats` instance and
call `read(Stats)` repeatedly, exactly as `RequestStatsExampleTest`'s reader threads do below.

See
[`RequestStatsExampleTest`](seqlock/src/test/java/io/deephaven/seqlock/RequestStatsExampleTest.java)
for a complete, runnable version of the batched variant — including a concurrent stress test that
fails if any reader ever observes `count` and `totalElapsedNanos` out of sync with each other (and
which really does fail if you break the pattern; see that test's neighbor,
[`RequestStatsExample`](seqlock/src/test/java/io/deephaven/seqlock/RequestStatsExample.java)).

## API reference

See the [`SeqLock` javadoc](seqlock/src/main/java/io/deephaven/seqlock/SeqLock.java) for
the full method list, including `beginReadInterruptible()` for a spin that responds to thread
interruption, and the `tryBeginRead(pollInterval, totalWait, unit)` variants that sleep between
attempts instead of spinning.

## License

[Apache License, Version 2.0](LICENSE).

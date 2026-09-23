/*
 * Copyright (c) 2026 Deephaven Data Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.deephaven.seqlock;

/**
 * Worked example for the README's "Protecting a group of related fields" section: a hot-path
 * counter/accumulator, published to any number of concurrent readers as a coherent snapshot at
 * whatever interval the writer chooses.
 *
 * <p>{@link Stats}' fields are only ever written together, inside a single {@link
 * SeqLock#beginWrite()}/{@link SeqLock#endWrite()} pair, so a reader that gets a validated stamp
 * always sees them as they existed at the same instant -- never {@code count} from one publish and
 * {@code totalElapsedNanos} from the next. The hot path itself ({@link #recordSuccess(long)})
 * touches only the writer-thread-local {@link #local} instance with no locking at all; nothing else
 * ever reads it, so it needs no protection until {@link #publish()} copies it into {@link #shared}.
 *
 * <p>{@link Stats} plays three different roles here -- the shared, published state; the
 * writer-local accumulator; and, via {@link #read(Stats)}, an allocation-free target a caller
 * supplies for a read -- rather than three separate sets of fields, so adding a new field to track
 * means touching {@link Stats} (and its {@link Stats#copyFrom(Stats)}) once instead of duplicating
 * it across every place a copy of the data lives.
 *
 * <p>See {@link RequestStatsExampleTest} for both a single-threaded demonstration of the
 * accumulate-then-publish timing, and a concurrent stress test that fails if any reader ever
 * observes {@code count}/{@code totalElapsedNanos} out of sync with each other.
 */
final class RequestStatsExample {

  private final SeqLock lock = SeqLock.newInstance();

  // Reader-visible; written only inside beginWrite()/endWrite(), read only inside
  // beginRead()/validate() (via read()/snapshot()). Deliberately not volatile: SeqLock's fences
  // supply the ordering, as long as field accesses stay within that window (see SeqLock's own
  // "Memory model contract").
  private final Stats shared = new Stats();

  // Writer-thread-local accumulator. No other thread ever touches this, so there's nothing here
  // for SeqLock (or volatile) to protect -- accumulating is exactly as cheap as it would be for a
  // plain, unshared object.
  private final Stats local = new Stats();

  /** Hot path: record one operation. No lock, no publish -- just a local increment. */
  void recordSuccess(long elapsedNanos) {
    local.count++;
    local.totalElapsedNanos += elapsedNanos;
  }

  /**
   * Publishes the locally-accumulated counters as a single, internally-consistent snapshot. Call
   * this at whatever interval suits your readers (once per batch, once a second, ...) -- not after
   * every {@link #recordSuccess(long)} call, which would defeat the point of accumulating locally
   * in the first place.
   */
  void publish() {
    lock.beginWrite();
    try {
      shared.copyFrom(local);
    } finally {
      lock.endWrite();
    }
  }

  /**
   * Reads the current published state into {@code out}, allocating nothing. Any number of reader
   * threads may call this concurrently with {@link #publish()} -- but each caller needs its own
   * {@code Stats} instance; don't share one {@code out} across concurrent callers.
   *
   * <p>The {@link Stats#copyFrom(Stats)} call sits between {@link SeqLock#beginRead()} and {@link
   * SeqLock#validate(long)} in program order, which is all the fences need -- whether or not the
   * JIT inlines it (see {@link SeqLock}'s "Memory model contract"). Every iteration of this retry
   * loop contains a volatile read, so the field reads inside {@code copyFrom} can't be hoisted out
   * of the loop and reused across retries the way a plain-field spin loop's could.
   */
  void read(Stats out) {
    long stamp;
    do {
      stamp = lock.beginRead();
      out.copyFrom(shared);
    } while (!lock.validate(stamp));
  }

  /** Convenience over {@link #read(Stats)} for callers that don't mind an allocation. */
  Stats snapshot() {
    Stats out = new Stats();
    read(out);
    return out;
  }

  static final class Stats {
    long count;
    long totalElapsedNanos;

    void copyFrom(Stats other) {
      count = other.count;
      totalElapsedNanos = other.totalElapsedNanos;
    }

    long averageElapsedNanos() {
      return count == 0 ? 0 : totalElapsedNanos / count;
    }
  }
}

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

import java.util.concurrent.TimeUnit;

/**
 * SeqLock, a writer-biased optimistic concurrency primitive.
 *
 * <p>Guarantees:
 *
 * <ul>
 *   <li>A single writer thread may mutate shared state via a single, unconditional pass: {@link
 *       #beginWrite()}/{@link #endWrite()} always succeed on the first attempt, in a bounded number
 *       of steps, with no blocking, no compare-and-swap retry loop, and no dependence on what
 *       readers are doing.
 *   <li>Any number of reader threads may optimistically read shared state without acquiring a lock,
 *       optionally retrying if the read was not consistent.
 * </ul>
 *
 * <p>Correct use is assumed, not checked. {@code SeqLock} takes it as given that there is exactly
 * one writer: a single thread issuing one {@link #beginWrite()}/{@link #endWrite()} section at a
 * time. Using it with more than one writer results in undefined behavior.
 *
 * <p>Typical writer usage:
 *
 * <pre>{@code
 * lock.beginWrite();
 * try {
 *     sharedX = 42;
 *     sharedY = 99;
 * } finally {
 *     lock.endWrite();
 * }
 * }</pre>
 *
 * <p>Typical reader usage (retry until consistent):
 *
 * <pre>{@code
 * long stamp;
 * int localX, localY;
 * do {
 *     stamp = lock.beginRead();
 *     localX = sharedX;
 *     localY = sharedY;
 * } while (!lock.validate(stamp));
 * // localX, localY are consistent
 * }</pre>
 *
 * <p>Typical reader usage (try-once, no spin).
 *
 * <pre>{@code
 * long stamp = lock.tryBeginRead();
 * if (lock.isReadStamp(stamp)) {
 *     int localX = sharedX;
 *     int localY = sharedY;
 *     if (lock.validate(stamp)) {
 *         // localX, localY are consistent
 *     }
 * }
 * }</pre>
 *
 * <p><b>Memory model contract:</b> shared fields read/written under this lock do <i>not</i> need to
 * be {@code volatile}. The caller is responsible for placing field accesses strictly between {@code
 * beginWrite}/{@code endWrite} and {@code beginRead}/{@code validate}. The fences inside this class
 * enforce the required ordering.
 *
 * <p>Keep the code between {@code beginRead} and {@code validate} to plain copying: load the shared
 * fields, store them into locals (or a caller-owned object), nothing else. Whether that copy is
 * written inline or in a method called from there doesn't matter — the fences cover every read that
 * sits between the two calls in program order. Copying a reference is fine when the object behind
 * it is immutable ({@code String}, {@code Instant}, an unmodifiable collection the writer never
 * touches again) and the writer swaps in a new object rather than mutating the old one: validating
 * the reference then validates everything reachable through it. It is not fine for an object the
 * writer mutates in place — a field read through such a reference after {@code validate} happens
 * outside the window, unvalidated. Everything else — computation on the values, or waiting for a
 * condition — belongs after a successful {@code validate}, operating on the copies.
 */
public final class SeqLock {

  static final long ORIGIN = 2;

  // read by readers; written by writer
  private volatile long sequence = ORIGIN;

  // writer-private; allows writers to manage state without needing to do a volatile read
  private long writerSeq = ORIGIN;

  /**
   * Creates a new {@link SeqLock}. This is the recommended way to construct a {@link SeqLock} for
   * most callers.
   *
   * <p>Currently, this is equivalent to {@link #newInstanceUnpadded()}, but this may change in the
   * future.
   *
   * @return a new {@link SeqLock}.
   */
  public static SeqLock newInstance() {
    return newInstanceUnpadded();
  }

  /**
   * Creates a new, unpadded {@link SeqLock}.
   *
   * @return a new, unpadded {@link SeqLock}.
   */
  public static SeqLock newInstanceUnpadded() {
    return new SeqLock();
  }

  private SeqLock() {}

  /**
   * Marks the start of a write section. Should be paired with {@link #endWrite()} in a finally
   * block.
   *
   * <p>Always returns immediately: there is no lock to wait for and no retry loop, regardless of
   * how many readers are active.
   *
   * <p>Must only ever be called by the single writer (see the class documentation); calls from more
   * than one writer result in undefined behavior.
   */
  public void beginWrite() {
    assert isReadStampImpl(writerSeq);
    // (1) plain increment + volatile write -> odd, signals write-in-progress
    sequence = ++writerSeq;
    // Release: prior stores can't sink below (1).
    // (2) prevents subsequent state stores from rising above (1)
    VarHandleShim.storeStoreFence();
  }

  /**
   * Marks the end of a write section. Should be called in a finally block to ensure the sequence is
   * always restored to a consistent state even if the write throws.
   *
   * <p>Always returns immediately: there is no lock to wait for and no retry loop, regardless of
   * how many readers are active.
   */
  public void endWrite() {
    assert !isReadStampImpl(writerSeq);
    // (3) state stores happen here (caller's code, between begin/end)
    // (4) plain increment + volatile write -> even, signals write-complete
    sequence = ++writerSeq;
    // Release: state stores can't sink below (4). No extra fence needed.
  }

  /**
   * Checks if the stamp is valid for reading.
   *
   * @param stamp the stamp
   * @return {@code true} if the stamp can be used for reading; {@code false} if the caller should
   *     discard the stamp and retry if necessary
   */
  public boolean isReadStamp(final long stamp) {
    return isReadStampImpl(stamp);
  }

  private static boolean isReadStampImpl(long stamp) {
    return (stamp & 1) == 0;
  }

  /**
   * Try to begin an optimistic read.
   *
   * <p>The return value <b>must</b> be checked against {@link #isReadStamp(long)}. If {@code true},
   * it is as if the caller has called {@link #beginRead()}; otherwise, the caller should discard
   * the invalid read stamp.
   *
   * <pre>{@code
   * long stamp = lock.tryBeginRead();
   * if (lock.isReadStamp(stamp)) {
   *     int localX = sharedX;
   *     int localY = sharedY;
   *     if (lock.validate(stamp)) {
   *         // localX, localY are consistent
   *     }
   * }
   * }</pre>
   *
   * @return a potential read stamp
   */
  public long tryBeginRead() {
    return sequence;
  }

  /**
   * Begins an optimistic read.
   *
   * <p>If a write is currently in progress this method spins until the write completes before
   * returning, ensuring the caller always starts from a consistent sequence boundary.
   *
   * <p>After reading, the stamp <b>must</b> be {@link #validate(long) validated} to ensure the read
   * was consistent. This should almost always be a loop that retries until validation succeeds:
   *
   * <pre>{@code
   * long stamp;
   * int localX, localY;
   * do {
   *     stamp = lock.beginRead();
   *     localX = sharedX;
   *     localY = sharedY;
   * } while (!lock.validate(stamp));
   * // localX, localY are consistent
   * }</pre>
   *
   * <p>Callers that would rather give up than retry — treating a stale read as an acceptable
   * outcome — should use {@link #tryBeginRead()} instead.
   *
   * @return a read stamp ({@link #isReadStamp(long)} is guaranteed to return {@code true})
   */
  public long beginRead() {
    long stamp;
    // (A) volatile read - acquire: state reads can't rise above (A). Spin while write is in
    // progress.
    while (!isReadStampImpl(stamp = sequence)) {
      ThreadShim.onSpinWait();
    }
    return stamp;
  }

  /**
   * Same as {@link #beginRead()}, but propagates interruption instead of ignoring it: checked once
   * before spinning, and again on each iteration of the spin.
   *
   * <p>After reading, the stamp <b>must</b> be {@link #validate(long) validated} to ensure the read
   * was consistent.
   *
   * @return a read stamp ({@link #isReadStamp(long)} is guaranteed to return {@code true})
   * @throws InterruptedException if interrupted before or while spinning
   */
  public long beginReadInterruptible() throws InterruptedException {
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
    long stamp;
    // (A) volatile read — acquire: state reads can't rise above (A). Spin while write is in
    // progress.
    while (!isReadStampImpl(stamp = sequence)) {
      ThreadShim.onSpinWait();
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
    }
    return stamp;
  }

  /**
   * Validates that the shared state read since the matching {@link #beginRead()} was consistent
   * (i.e., no write overlapped the read window).
   *
   * @param stamp the value returned by {@link #beginRead()}
   * @return {@code true} if the read was consistent; {@code false} if the caller should discard the
   *     values and retry if necessary
   */
  public boolean validate(final long stamp) {
    assert isReadStampImpl(stamp);
    // (B) state reads from the read window can't sink below (C)
    VarHandleShim.acquireFence();
    // (C) volatile read - sequence unchanged -> read was consistent
    return sequence == stamp;
  }

  /**
   * Try to begin an optimistic read, polling with {@link Thread#sleep(long)} between attempts if a
   * write is in progress, up to a bounded total wait. Doesn't abort polling on interruption — but
   * if interrupted, restores the thread's interrupt status just before returning, so the caller can
   * still observe and respond to it afterward. Use {@link #tryBeginReadInterruptible(long, long,
   * TimeUnit)} instead for a variant that propagates {@link InterruptedException} immediately
   * rather than continuing to poll.
   *
   * <p>Unlike {@link #beginRead()}, this does not busy-spin: on a virtual thread, {@code
   * Thread.sleep} releases the carrier between polls, where {@code Thread.onSpinWait()} does not.
   * The tradeoff is latency — the caller only learns a write completed on the next poll, up to
   * {@code pollInterval} later than {@link #beginRead()} would have.
   *
   * <p>Each poll always sleeps a full {@code pollInterval}, rather than clamping the final sleep to
   * land exactly on {@code totalWait} — so the total time spent may exceed {@code totalWait} by up
   * to one {@code pollInterval}.
   *
   * <p>The return value <b>must</b> be checked against {@link #isReadStamp(long)}, exactly as with
   * {@link #tryBeginRead()}: reaching {@code totalWait} without an intervening write completing is
   * not an error, it just means the caller gets back the last (write-in-progress) stamp observed.
   *
   * @param pollInterval how long to sleep between polls, and the maximum staleness of learning that
   *     a write completed; rounded up to 1 millisecond if smaller, since {@link Thread#sleep(long)}
   *     only has millisecond resolution
   * @param totalWait the maximum total time to spend polling before giving up, plus up to one more
   *     {@code pollInterval}
   * @param unit the unit both {@code pollInterval} and {@code totalWait} are expressed in
   * @return a potential read stamp
   */
  public long tryBeginRead(final long pollInterval, final long totalWait, final TimeUnit unit) {
    long stamp;
    if (isReadStampImpl(stamp = sequence)) {
      return stamp;
    }
    final long totalWaitNanos = unit.toNanos(totalWait);
    if (totalWaitNanos <= 0) {
      return stamp;
    }
    final long pollIntervalMillis = Math.max(1, unit.toMillis(pollInterval));
    final long startNanos = System.nanoTime();
    boolean interrupted = false;
    do {
      try {
        Thread.sleep(pollIntervalMillis);
      } catch (InterruptedException e) {
        // Captured, not propagated or restored immediately -- restoring it here would make
        // every later Thread.sleep() in this loop immediately re-throw too, degrading into a
        // busy loop for the rest of totalWait. Restored once, below, just before returning.
        interrupted = true;
      }
    } while (!isReadStampImpl(stamp = sequence) && System.nanoTime() - startNanos < totalWaitNanos);
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
    return stamp;
  }

  /**
   * Same as {@link #tryBeginRead(long, long, TimeUnit)}, but propagates interruption instead of
   * ignoring it, mirroring {@link #beginReadInterruptible()}.
   *
   * @param pollInterval how long to sleep between polls, and the maximum staleness of learning that
   *     a write completed; rounded up to 1 millisecond if smaller, since {@link Thread#sleep(long)}
   *     only has millisecond resolution
   * @param totalWait the maximum total time to spend polling before giving up, plus up to one more
   *     {@code pollInterval}
   * @param unit the unit both {@code pollInterval} and {@code totalWait} are expressed in
   * @return a potential read stamp
   * @throws InterruptedException if interrupted before or while sleeping between polls
   */
  public long tryBeginReadInterruptible(
      final long pollInterval, final long totalWait, final TimeUnit unit)
      throws InterruptedException {
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
    long stamp;
    if (isReadStampImpl(stamp = sequence)) {
      return stamp;
    }
    final long totalWaitNanos = unit.toNanos(totalWait);
    if (totalWaitNanos <= 0) {
      return stamp;
    }
    final long pollIntervalMillis = Math.max(1, unit.toMillis(pollInterval));
    final long startNanos = System.nanoTime();
    do {
      Thread.sleep(pollIntervalMillis);
      if (isReadStampImpl(stamp = sequence)) {
        return stamp;
      }
    } while (System.nanoTime() - startNanos < totalWaitNanos);
    return stamp;
  }
}

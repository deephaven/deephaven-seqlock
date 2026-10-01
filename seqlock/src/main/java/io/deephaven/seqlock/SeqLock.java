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
 * Sequence lock: a writer-biased concurrency primitive for publishing shared state from one writer
 * thread to any number of reader threads. The writes are wait-free and the reads are optimistic
 * with optional retry.
 *
 * <p>Unlike {@link java.util.concurrent.locks.Lock} and many other concurrency primitives, writes
 * do not signal, wake, or actively notify readers. Readers learn of a change only by reading.
 *
 * <p>Correct use is assumed, not checked. {@code SeqLock} takes it as given that there is exactly
 * one writer: a single thread issuing one {@link #beginWrite()}/{@link #endWrite()} section at a
 * time. Using it with more than one concurrent writer results in undefined behavior.
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
 * int localX, localY;
 * long stamp;
 * do {
 *     stamp = lock.beginRead();
 *     localX = sharedX;
 *     localY = sharedY;
 * } while (!lock.validate(stamp));
 * // localX, localY are consistent
 * }</pre>
 *
 * <p>Typical reader usage (no retry):
 *
 * <pre>{@code
 * long stamp = lock.tryBeginRead();
 * if (stamp != 0) {
 *     int localX = sharedX;
 *     int localY = sharedY;
 *     if (lock.validate(stamp)) {
 *         // localX, localY are consistent
 *     }
 * }
 * }</pre>
 *
 * <p><b>Memory model contract:</b> shared fields read/written under this lock do <i>not</i> need to
 * be {@code volatile}. The caller is responsible for placing shared field stores strictly between
 * {@code beginWrite}/{@code endWrite} and shared field loads strictly between {@code
 * beginRead}/{@code validate}. The fences inside this class enforce the required ordering: if
 * {@code validate} returns {@code true}, every write made in write sections that ended before the
 * stamp was obtained <i>happens-before</i> the reads between {@code beginRead} and {@code
 * validate}; if it returns {@code false}, nothing is guaranteed about those reads, and the copies
 * must be discarded. The only cost readers impose on the writer is the ordinary cache-coherence
 * price of sharing memory: a reader's load of a guarded cache line makes the writer's next store to
 * that line reclaim ownership.
 *
 * <p>Keep the code between {@code beginRead} and {@code validate} to plain copying: load the shared
 * fields, store them into locals (or a caller-owned object), nothing else. Copying an immutable
 * reference is fine.
 */
public final class SeqLock {

  // Set in every sequence value the writer stores, so no value is ever 0: tryBeginRead returns 0
  // to mean "write in progress", and validate(0) is then false by the same comparison that
  // rejects any other stale stamp. The counter is the low 63 bits.
  static final long MARK = Long.MIN_VALUE;

  static final long ORIGIN = MARK | 1;

  // read by readers; written by writer. Odd: readable (a read stamp); even: write in progress.
  // Always has MARK set, so never 0.
  private volatile long sequence = ORIGIN;

  // writer-private; allows writers to manage state without needing to do a volatile read
  private long writerSeq = ORIGIN;

  /**
   * Creates a new {@link SeqLock}.
   *
   * @return a new {@link SeqLock}.
   */
  public static SeqLock newInstance() {
    return new SeqLock();
  }

  private SeqLock() {}

  /**
   * Marks the start of a write section. Should be paired with {@link #endWrite()} in a finally
   * block:
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
   * <p>Always returns immediately regardless of how many readers are active.
   *
   * <p>Must only ever be called by the single writer; calls from more than one writer result in
   * undefined behavior.
   */
  public void beginWrite() {
    assert (writerSeq & 1) != 0;
    // (1) volatile write -> even, signals write-in-progress. Published first so that the store
    // that takes ownership of the line is the one readers need to see; the writer-private copy
    // follows as a write hit on a line the writer already owns. The OR keeps MARK set across the
    // one carry that could clear it (-1 + 1); endWrite adds 1 to an even value and cannot.
    final long next = (writerSeq + 1) | MARK;
    sequence = next;
    writerSeq = next;
    // Release: prior stores can't sink below (1).
    // (2) prevents subsequent state stores from rising above (1)
    VarHandleShim.storeStoreFence();
  }

  /**
   * Marks the end of a write section. Should be called in a finally block to ensure the sequence is
   * always restored to a consistent state even if the write throws.
   *
   * <p>Always returns immediately regardless of how many readers are active.
   */
  public void endWrite() {
    assert (writerSeq & 1) == 0;
    // (3) state stores happen here (caller's code, between begin/end)
    // (4) volatile write -> odd, signals write-complete; see (1) for the ordering.
    final long next = writerSeq + 1;
    sequence = next;
    writerSeq = next;
    // Release: state stores can't sink below (4). No extra fence needed.
  }

  /**
   * Try to begin an optimistic read.
   *
   * <p>Always returns immediately: returns a read stamp if available, otherwise returns {@code 0}
   * signalling a write is in progress.
   *
   * <p>After reading, the stamp <b>must</b> be {@link #validate(long) validated} to ensure the read
   * was consistent.
   *
   * <pre>{@code
   * long stamp = lock.tryBeginRead();
   * if (stamp != 0) {
   *     int localX = sharedX;
   *     int localY = sharedY;
   *     if (lock.validate(stamp)) {
   *         // localX, localY are consistent
   *     }
   * }
   * }</pre>
   *
   * <p>Callers may choose to opt out checking if a write is in progress since {@code validate(0)}
   * will always return {@code false}.
   *
   * <pre>{@code
   * long stamp = lock.tryBeginRead();
   * int localX = sharedX;
   * int localY = sharedY;
   * if (lock.validate(stamp)) {
   *     // localX, localY are consistent
   * }
   * }</pre>
   *
   * @return a read stamp, or {@code 0} if a write is in progress
   */
  public long tryBeginRead() {
    final long stamp = sequence;
    // A branch, not a ternary: the write-in-progress case is rare, so this predicts perfectly,
    // while the ternary tends to become a conditional move that adds to every attempt's dependency
    // chain.
    if ((stamp & 1) == 0) {
      return 0L;
    }
    return stamp;
  }

  /**
   * Begins an optimistic read.
   *
   * <p>If a write is currently in progress this method spins until a write is no longer in progress
   * (invoking {@code Thread.onSpinWait()} between attempts on Java 11+).
   *
   * <p>After reading, the read stamp <b>must</b> be {@link #validate(long) validated} to ensure the
   * read was consistent.
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
   * <p>Callers that would rather give up than retry should use {@link #tryBeginRead()} instead.
   *
   * @return a read stamp
   */
  public long beginRead() {
    long stamp;
    // (A) volatile read - acquire: state reads can't rise above (A). Spin while write is in
    // progress.
    while (((stamp = sequence) & 1) == 0) {
      ThreadShim.onSpinWait();
    }
    return stamp;
  }

  /**
   * Validates that the shared state read since the matching {@link #beginRead()} was consistent
   * (i.e., no write overlapped the read window). A {@code true} result means every write made in
   * write sections that ended before the stamp was obtained happens-before the reads in the window;
   * a {@code false} result means nothing about those reads, and the copies must be discarded.
   *
   * <p>Always returns {@code false} for {@code 0}, the value {@link #tryBeginRead()} returns while
   * a write is in progress. Invoking it with a value not obtained from one of the {@code beginRead}
   * or {@code tryBeginRead} methods of this lock has no defined result.
   *
   * @param stamp the value returned by {@link #beginRead()} or {@link #tryBeginRead()}
   * @return {@code true} if the read was consistent; {@code false} if the caller should discard the
   *     values and retry if necessary
   */
  public boolean validate(final long stamp) {
    // (B) state reads from the read window can't sink below (C)
    VarHandleShim.acquireFence();
    // (C) volatile read - sequence unchanged -> read was consistent. 0 (tryBeginRead during a
    // write) never validates: every sequence value has MARK set, so none is ever 0, and a
    // forgotten check fails here rather than passing torn data through.
    return sequence == stamp;
  }
}

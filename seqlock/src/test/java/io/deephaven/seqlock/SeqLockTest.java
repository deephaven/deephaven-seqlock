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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.failBecauseExceptionWasNotThrown;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(5)
class SeqLockTest {

  SeqLock lock;

  @BeforeEach
  void setUp() {
    lock = SeqLock.newInstance();
  }

  @Test
  void tryBeginRead() {
    final long stamp = lock.tryBeginRead();
    assertThat(stamp).isNotZero();
    assertThat(lock.validate(stamp)).isTrue();
  }

  @Test
  void beginRead() {
    final long stamp = lock.beginRead();
    assertThat(lock.validate(stamp)).isTrue();
  }

  @Test
  void validateFailsAfterAnInterveningWrite() {
    final long stamp = lock.beginRead();
    lock.beginWrite();
    lock.endWrite();
    assertThat(lock.validate(stamp)).isFalse();
    // A fresh stamp taken after the write validates.
    assertThat(lock.validate(lock.beginRead())).isTrue();
  }

  @Test
  void validateFailsWhileAWriteIsInProgress() {
    final long stamp = lock.beginRead();
    lock.beginWrite();
    try {
      assertThat(lock.validate(stamp)).isFalse();
    } finally {
      lock.endWrite();
    }
    // And stays stale once that write has completed.
    assertThat(lock.validate(stamp)).isFalse();
  }

  @Test
  void validateRejectsAStampTakenDuringAWrite() {
    // A tryBeginRead() during a write returns 0. A caller who skips the check could still pass it
    // to validate(), which must reject it on its own -- the sequence is never 0 -- rather than
    // pass a read taken mid-write off as consistent.
    lock.beginWrite();
    try {
      final long stamp = lock.tryBeginRead();
      assertThat(stamp).isZero();
      assertThat(lock.validate(stamp)).isFalse();
    } finally {
      lock.endWrite();
    }
  }

  @Test
  void zeroNeverValidatesAcrossTheCounterWrap() throws ReflectiveOperationException {
    // The one section where writerSeq + 1 would be 0: readable -1, then a write. Every stored
    // value has the mark bit set, so the write-in-progress value is Long.MIN_VALUE, not 0, and
    // validate(0) stays false even for a caller that skipped the tryBeginRead() check.
    setSequence(-1L);
    lock.beginWrite();
    try {
      assertThat(lock.tryBeginRead()).isZero();
      assertThat(lock.validate(0)).isFalse();
    } finally {
      lock.endWrite();
    }
    final long stamp = lock.beginRead();
    assertThat(stamp).isNotZero();
    assertThat(lock.validate(stamp)).isTrue();
    assertThat(lock.validate(0)).isFalse();
  }

  @Test
  void staleStampNeverValidatesAgain() {
    final long stamp = lock.beginRead();
    for (int i = 0; i < 1000; i++) {
      lock.beginWrite();
      lock.endWrite();
      assertThat(lock.validate(stamp)).isFalse();
    }
  }

  @Test
  void beginReadPreservesInterruptFlag() throws InterruptedException {
    final long[] protectedValue = new long[1];
    final Thread writer = startWriterHoldingWriteSection(protectedValue);
    Thread.currentThread().interrupt();
    final long stamp = lock.beginRead();
    // Spins straight through the interrupt and leaves the flag set for the caller.
    // Thread.interrupted() both checks and clears it, which also keeps the still-set flag from
    // making writer.join() below throw.
    assertThat(Thread.interrupted()).isTrue();
    writer.join();
    assertThat(lock.validate(stamp)).isTrue();
    assertThat(protectedValue[0]).isEqualTo(42L);
  }

  @Test
  void tryBeginReadDuringWrite() {
    lock.beginWrite();
    try {
      assertThat(lock.tryBeginRead()).isZero();
    } finally {
      lock.endWrite();
    }
  }

  @Test
  void beginReadDuringWrite() throws InterruptedException, ExecutionException {
    final long[] protectedValue = new long[1];

    final int numReaders = 4;
    final CountDownLatch latch = new CountDownLatch(numReaders);
    final Future<Long>[] readFutures = new Future[numReaders];
    final ExecutorService executor = Executors.newFixedThreadPool(numReaders);
    try {
      lock.beginWrite();
      try {
        for (int i = 0; i < numReaders; ++i) {
          readFutures[i] =
              executor.submit(
                  () -> {
                    latch.countDown();
                    final long stamp = lock.beginRead();
                    final long readValue = protectedValue[0];
                    // once we get read stamp, based on test setup we know there will be no more
                    // writes
                    assertThat(lock.validate(stamp)).isTrue();
                    return readValue;
                  });
        }
        // Waiting in beginWrite is *not* something we expect users of the interface to actually do
        // - write sections should only set values. But, blocking in this unit test is desirable to
        // make sure all of the reader threads have started.
        latch.await();
        protectedValue[0] = 42L;
      } finally {
        lock.endWrite();
      }
    } finally {
      executor.shutdown();
    }
    for (Future<Long> readFuture : readFutures) {
      assertThat(readFuture.get()).isEqualTo(42L);
    }
  }

  @Test
  void doubleBeginWrite() {
    lock.beginWrite();
    try {
      shouldError(lock::beginWrite);
    } finally {
      lock.endWrite();
    }
  }

  @Test
  void doubleEndWrite() {
    lock.beginWrite();
    lock.endWrite();
    shouldError(lock::endWrite);
  }

  /**
   * Starts a thread that begins a write section, holds it for ~30ms, sets {@code protectedValue[0]}
   * to 42, and ends it -- beginWrite and endWrite both on that thread. Returns once the write
   * section has begun, so on return a write is known to be in progress; join the returned thread to
   * know it has ended.
   */
  /**
   * Forces the sequence to {@code value} (which must be odd: readable) through the private fields.
   */
  private void setSequence(long value) throws ReflectiveOperationException {
    for (String field : new String[] {"sequence", "writerSeq"}) {
      final java.lang.reflect.Field f = SeqLock.class.getDeclaredField(field);
      f.setAccessible(true);
      f.setLong(lock, value);
    }
  }

  private Thread startWriterHoldingWriteSection(long[] protectedValue) throws InterruptedException {
    final CountDownLatch writeStarted = new CountDownLatch(1);
    final Thread writer =
        new Thread(
            () -> {
              lock.beginWrite();
              try {
                writeStarted.countDown();
                try {
                  Thread.sleep(30);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                protectedValue[0] = 42L;
              } finally {
                lock.endWrite();
              }
            });
    writer.start();
    writeStarted.await();
    return writer;
  }

  private static void shouldError(Runnable runnable) {
    try {
      runnable.run();
    } catch (final AssertionError e) {
      // expected
      return;
    }
    failBecauseExceptionWasNotThrown(AssertionError.class);
  }
}

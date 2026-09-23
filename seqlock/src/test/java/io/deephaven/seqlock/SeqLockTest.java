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
import java.util.concurrent.TimeUnit;
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
    assertThat(lock.isReadStamp(stamp)).isTrue();
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
  void staleStampNeverValidatesAgain() {
    final long stamp = lock.beginRead();
    for (int i = 0; i < 1000; i++) {
      lock.beginWrite();
      lock.endWrite();
      assertThat(lock.validate(stamp)).isFalse();
    }
  }

  @Test
  void beginReadInterruptible() throws InterruptedException {
    final long stamp = lock.beginReadInterruptible();
    assertThat(lock.validate(stamp)).isTrue();
  }

  @Test
  void beginReadInterruptibleThrowsIfInterrupted() {
    Thread.currentThread().interrupt();
    try {
      lock.beginReadInterruptible();
      failBecauseExceptionWasNotThrown(InterruptedException.class);
    } catch (InterruptedException e) {
      // expected
    }
  }

  @Test
  void beginReadInterruptibleThrowsIfInterruptedWhileSpinning() throws InterruptedException {
    // Interrupt from another thread, ~30ms in, so the interrupt lands while beginReadInterruptible
    // is spinning on the held write -- interrupting up front would instead trip its initial
    // interrupted() check before it ever spun.
    final Thread interrupter = interruptCurrentThreadAfter(30);
    lock.beginWrite();
    try {
      lock.beginReadInterruptible();
      failBecauseExceptionWasNotThrown(InterruptedException.class);
    } catch (InterruptedException e) {
      // expected; throwing it also cleared the interrupt flag
    } finally {
      lock.endWrite();
    }
    interrupter.join();
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
      assertThat(lock.isReadStamp(lock.tryBeginRead())).isFalse();
    } finally {
      lock.endWrite();
    }
  }

  @Test
  void tryBeginReadPollNoWriteInProgress() {
    final long stamp = lock.tryBeginRead(5, 1000, TimeUnit.MILLISECONDS);
    assertThat(lock.isReadStamp(stamp)).isTrue();
    assertThat(lock.validate(stamp)).isTrue();
  }

  @Test
  void tryBeginReadPollTimesOutDuringWrite() {
    lock.beginWrite();
    try {
      final long stamp = lock.tryBeginRead(5, 30, TimeUnit.MILLISECONDS);
      assertThat(lock.isReadStamp(stamp)).isFalse();
    } finally {
      lock.endWrite();
    }
  }

  @Test
  void tryBeginReadPollSucceedsAfterWriteCompletes() throws InterruptedException {
    final long[] protectedValue = new long[1];
    final Thread writer = startWriterHoldingWriteSection(protectedValue);
    final long stamp = lock.tryBeginRead(5, 2000, TimeUnit.MILLISECONDS);
    writer.join();
    assertThat(lock.isReadStamp(stamp)).isTrue();
    assertThat(lock.validate(stamp)).isTrue();
    assertThat(protectedValue[0]).isEqualTo(42L);
  }

  @Test
  void tryBeginReadPollIntervalRoundedUpToOneMilli() throws InterruptedException {
    final long[] protectedValue = new long[1];
    final Thread writer = startWriterHoldingWriteSection(protectedValue);
    // 0 would round down to a 0ms Thread.sleep() without the 1ms floor, degrading into a busy
    // loop for the whole totalWait instead of actually sleeping between polls.
    final long stamp = lock.tryBeginRead(0, 2000, TimeUnit.MILLISECONDS);
    writer.join();
    assertThat(lock.isReadStamp(stamp)).isTrue();
    assertThat(lock.validate(stamp)).isTrue();
    assertThat(protectedValue[0]).isEqualTo(42L);
  }

  @Test
  void tryBeginReadPollIgnoresInterruption() throws InterruptedException {
    final long[] protectedValue = new long[1];
    final Thread writer = startWriterHoldingWriteSection(protectedValue);
    Thread.currentThread().interrupt();
    final long stamp = lock.tryBeginRead(5, 2000, TimeUnit.MILLISECONDS);
    // Doesn't abort polling on interruption, but restores the flag before returning so the caller
    // can still observe it. Thread.interrupted() both checks and clears it, which also keeps the
    // still-set flag from making writer.join() below throw.
    assertThat(Thread.interrupted()).isTrue();
    writer.join();
    assertThat(lock.isReadStamp(stamp)).isTrue();
    assertThat(lock.validate(stamp)).isTrue();
    assertThat(protectedValue[0]).isEqualTo(42L);
  }

  @Test
  void tryBeginReadInterruptiblePollNoWriteInProgress() throws InterruptedException {
    final long stamp = lock.tryBeginReadInterruptible(5, 1000, TimeUnit.MILLISECONDS);
    assertThat(lock.isReadStamp(stamp)).isTrue();
    assertThat(lock.validate(stamp)).isTrue();
  }

  @Test
  void tryBeginReadInterruptiblePollThrowsIfInterrupted() {
    Thread.currentThread().interrupt();
    try {
      lock.tryBeginReadInterruptible(10, 1000, TimeUnit.MILLISECONDS);
      failBecauseExceptionWasNotThrown(InterruptedException.class);
    } catch (InterruptedException e) {
      // expected; throwing it also cleared the interrupt flag
    }
  }

  @Test
  void tryBeginReadInterruptiblePollThrowsIfInterruptedDuringWait() throws InterruptedException {
    // Interrupt from another thread, ~30ms in, so the interrupt lands while
    // tryBeginReadInterruptible is asleep between polls -- interrupting up front would instead trip
    // its initial interrupted() check before it ever slept.
    final Thread interrupter = interruptCurrentThreadAfter(30);
    lock.beginWrite();
    try {
      lock.tryBeginReadInterruptible(5, 2000, TimeUnit.MILLISECONDS);
      failBecauseExceptionWasNotThrown(InterruptedException.class);
    } catch (InterruptedException e) {
      // expected; throwing it also cleared the interrupt flag
    } finally {
      lock.endWrite();
    }
    interrupter.join();
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

  /** Starts a thread that interrupts the calling thread after ~{@code delayMillis}. */
  private static Thread interruptCurrentThreadAfter(long delayMillis) {
    final Thread target = Thread.currentThread();
    final Thread interrupter =
        new Thread(
            () -> {
              try {
                Thread.sleep(delayMillis);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              target.interrupt();
            });
    interrupter.start();
    return interrupter;
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

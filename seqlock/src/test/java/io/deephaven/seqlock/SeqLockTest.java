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
  void tryBeginReadDuringWrite() {
    lock.beginWrite();
    assertThat(lock.isReadStamp(lock.tryBeginRead())).isFalse();
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
    lock.beginWrite();
    final Thread writer =
        new Thread(
            () -> {
              try {
                Thread.sleep(30);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              protectedValue[0] = 42L;
              lock.endWrite();
            });
    writer.start();
    final long stamp = lock.tryBeginRead(5, 2000, TimeUnit.MILLISECONDS);
    writer.join();
    assertThat(lock.isReadStamp(stamp)).isTrue();
    assertThat(lock.validate(stamp)).isTrue();
    assertThat(protectedValue[0]).isEqualTo(42L);
  }

  @Test
  void tryBeginReadPollIntervalRoundedUpToOneMilli() throws InterruptedException {
    final long[] protectedValue = new long[1];
    lock.beginWrite();
    final Thread writer =
        new Thread(
            () -> {
              try {
                Thread.sleep(30);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              protectedValue[0] = 42L;
              lock.endWrite();
            });
    writer.start();
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
    lock.beginWrite();
    final Thread writer =
        new Thread(
            () -> {
              try {
                Thread.sleep(30);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              protectedValue[0] = 42L;
              lock.endWrite();
            });
    writer.start();
    Thread.currentThread().interrupt();
    final long stamp;
    try {
      stamp = lock.tryBeginRead(5, 2000, TimeUnit.MILLISECONDS);
      // Doesn't abort polling on interruption, but restores the flag before returning so the
      // caller can still observe it afterward -- isInterrupted() (unlike Thread.interrupted())
      // doesn't itself clear the flag. Checked here, before any other blocking call (e.g.
      // writer.join() below), since the still-set flag would otherwise make that call throw too.
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted(); // clear the flag before any further blocking calls
    }
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
    try {
      Thread.currentThread().interrupt();
      lock.tryBeginReadInterruptible(10, 1000, TimeUnit.MILLISECONDS);
      failBecauseExceptionWasNotThrown(InterruptedException.class);
    } catch (InterruptedException e) {
      // expected
    } finally {
      Thread.interrupted(); // clear the flag
    }
  }

  @Test
  void tryBeginReadInterruptiblePollThrowsIfInterruptedDuringWait() {
    lock.beginWrite();
    try {
      Thread.currentThread().interrupt();
      lock.tryBeginReadInterruptible(10, 1000, TimeUnit.MILLISECONDS);
      failBecauseExceptionWasNotThrown(InterruptedException.class);
    } catch (InterruptedException e) {
      // expected
    } finally {
      Thread.interrupted(); // clear the flag
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

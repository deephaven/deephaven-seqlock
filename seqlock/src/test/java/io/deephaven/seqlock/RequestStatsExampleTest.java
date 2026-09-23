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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class RequestStatsExampleTest {

  @Test
  void accumulatesLocallyAndOnlyPublishesOnDemand() {
    final RequestStatsExample stats = new RequestStatsExample();
    assertThat(stats.snapshot().count).isZero();

    stats.recordSuccess(100);
    stats.recordSuccess(300);

    // Not yet published -- readers still see the pre-accumulation (empty) snapshot.
    assertThat(stats.snapshot().count).isZero();

    stats.publish();

    final RequestStatsExample.Stats published = stats.snapshot();
    assertThat(published.count).isEqualTo(2);
    assertThat(published.totalElapsedNanos).isEqualTo(400);
    assertThat(published.averageElapsedNanos()).isEqualTo(200);
  }

  @Test
  void readReusesTheProvidedStatsInstanceWithoutAllocating() {
    final RequestStatsExample stats = new RequestStatsExample();
    final RequestStatsExample.Stats out = new RequestStatsExample.Stats();

    stats.read(out);
    assertThat(out.count).isZero();

    stats.recordSuccess(100);
    stats.recordSuccess(300);
    stats.publish();

    stats.read(out); // same `out` instance as above, reused rather than reallocated
    assertThat(out.count).isEqualTo(2);
    assertThat(out.totalElapsedNanos).isEqualTo(400);
    assertThat(out.averageElapsedNanos()).isEqualTo(200);
  }

  @Test
  void readersNeverObserveATornSnapshot() throws Exception {
    final RequestStatsExample stats = new RequestStatsExample();
    final long elapsedNanosPerOp = 1000;
    final int readerCount = 4;
    final long testDurationMillis = 200;

    final AtomicBoolean stop = new AtomicBoolean(false);
    final Thread writer =
        new Thread(
            () -> {
              final Random random = new Random();
              while (!stop.get()) {
                // A random batch size per publish, rather than always 1, better exercises the
                // recommended pattern: accumulate several updates locally, then publish once.
                final int batchSize = random.nextInt(100) + 1; // 1-100 inclusive
                for (int i = 0; i < batchSize; i++) {
                  stats.recordSuccess(elapsedNanosPerOp);
                }
                stats.publish();
              }
            });

    final ExecutorService readers = Executors.newFixedThreadPool(readerCount);
    final List<Future<Long>> readerFutures = new ArrayList<>();
    try {
      writer.start();
      for (int i = 0; i < readerCount; i++) {
        readerFutures.add(
            readers.submit(
                () -> {
                  // One Stats instance reused for this whole thread's loop, rather than
                  // reallocating via snapshot() every iteration -- see read(Stats)'s doc.
                  final RequestStatsExample.Stats out = new RequestStatsExample.Stats();
                  while (!stop.get()) {
                    stats.read(out);
                    // count and totalElapsedNanos are only ever written together, so this must
                    // always hold -- every recordSuccess() call above uses the same
                    // elapsedNanosPerOp, so totalElapsedNanos is always exactly count times it.
                    assertThat(out.totalElapsedNanos)
                        .as("torn snapshot at count=%d", out.count)
                        .isEqualTo(out.count * elapsedNanosPerOp);
                  }
                  return out.count;
                }));
      }
      Thread.sleep(testDurationMillis);
    } finally {
      stop.set(true);
      writer.join();
      readers.shutdown();
    }
    // Sanity: every reader saw at least one published snapshot, so the absence of a torn snapshot
    // above isn't vacuously true because a reader only ever observed the initial empty state.
    for (int i = 0; i < readerFutures.size(); i++) {
      assertThat(readerFutures.get(i).get()).as("reader %d final count", i).isGreaterThan(0);
    }
    assertThat(stats.snapshot().count).isGreaterThan(0);
  }
}

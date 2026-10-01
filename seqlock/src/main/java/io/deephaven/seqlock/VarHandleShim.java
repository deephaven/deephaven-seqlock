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

import java.lang.reflect.Field;
import sun.misc.Unsafe;

final class VarHandleShim {

  private static final Unsafe UNSAFE;

  static {
    try {
      Field f = Unsafe.class.getDeclaredField("theUnsafe");
      f.setAccessible(true);
      UNSAFE = (Unsafe) f.get(null);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  public static void storeStoreFence() {
    // Note: this is stricter than it needs to be - can be loosened in Java 9+ w/
    // VarHandle.storeStoreFence
    // https://bugs.openjdk.org/browse/JDK-8252990
    UNSAFE.storeFence();
  }

  public static void acquireFence() {
    UNSAFE.loadFence();
  }
}

/**
 * MIT License
 * <p>
 * Copyright (c) 2019 Anatoly Gudkov
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE  LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.green.cab;

import sun.misc.Unsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;

import static java.lang.invoke.MethodType.methodType;

public class Utils {
    public static final int CACHE_LINE_SIZE = 64;

    private static final sun.misc.Unsafe UNSAFE;
    private static final MethodHandle ON_SPIN_WAIT_METHOD_HANDLE; // for Java 9 and above

    static {
        try {
            final PrivilegedExceptionAction<Unsafe> action = () -> {
                final Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                return (sun.misc.Unsafe) theUnsafe.get(null);
            };
            UNSAFE = AccessController.doPrivileged(action);
        } catch (final Exception e) {
            throw new RuntimeException("Cannot load Unsafe", e);
        }

        MethodHandle methodHandle = null;
        try {
            methodHandle = MethodHandles.lookup().findStatic(Thread.class, "onSpinWait", methodType(void.class));
        } catch (final NoSuchMethodException | IllegalAccessException ignore) {
        }

        ON_SPIN_WAIT_METHOD_HANDLE = methodHandle;
    }

    public static sun.misc.Unsafe getUnsafe() {
        return UNSAFE;
    }

    public static void onSpinWait() {
        if (ON_SPIN_WAIT_METHOD_HANDLE == null) {
            return;
        }
        try {
            ON_SPIN_WAIT_METHOD_HANDLE.invokeExact();
        } catch (final Throwable ignore) {
        }
    }

    public static int nextPowerOfTwo(final int value) {
        return 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
    }
}

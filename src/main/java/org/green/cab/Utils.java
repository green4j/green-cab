package org.green.cab;

import sun.misc.Unsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;

import static java.lang.invoke.MethodType.methodType;

public class Utils {

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
        } catch (final NoSuchMethodException | IllegalAccessException e) {
            // ignore
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
        } catch (final Throwable t) {
            // ignore
        }
    }

    public static int nextPowerOfTwo(final int value) {
        return 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
    }
}

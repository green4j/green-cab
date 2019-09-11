package org.green.cab;

import java.util.function.Supplier;

public class CabBackingOff<E, M> extends Cab<E, M> {
    public CabBackingOff(
        final int bufferSize,
        final long spinTimeoutNanos,
        final long yieldTimeoutNanos) {

        super(bufferSize, WaitingStaregy.BACKING_OFF, spinTimeoutNanos, yieldTimeoutNanos, null);
    }

    public CabBackingOff(
        final int bufferSize,
        final long spinTimeoutNanos,
        final long yieldTimeoutNanos,
        final Supplier<E> supplier) {

        super(bufferSize, WaitingStaregy.BACKING_OFF, spinTimeoutNanos, yieldTimeoutNanos, supplier);
    }
}
package org.green.cab;

import java.util.function.Supplier;

public class CabBackingOff<E, M> extends Cab<E, M> {
    public CabBackingOff(
        final int bufferSize,
        final long maxSpins,
        final long maxYields) {

        super(bufferSize, WaitingStaregy.BACKING_OFF, maxSpins, maxYields, null);
    }

    public CabBackingOff(
        final int bufferSize,
        final long maxSpins,
        final long maxYields,
        final Supplier<E> supplier) {

        super(bufferSize, WaitingStaregy.BACKING_OFF, maxSpins, maxYields, supplier);
    }
}
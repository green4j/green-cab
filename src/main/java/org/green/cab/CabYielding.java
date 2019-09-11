package org.green.cab;

import java.util.function.Supplier;

public class CabYielding<E, M> extends Cab<E, M> {
    public CabYielding(final int bufferSize) {
        super(bufferSize, WaitingStaregy.YIELDING, 0, 0, null);
    }

    public CabYielding(
        final int bufferSize,
        final Supplier<E> supplier) {

        super(bufferSize, WaitingStaregy.YIELDING, 0, 0, supplier);
    }
}
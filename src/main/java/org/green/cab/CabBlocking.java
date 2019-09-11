package org.green.cab;

import java.util.function.Supplier;

public class CabBlocking<E, M> extends Cab<E, M> {
    public CabBlocking(final int bufferSize) {
        super(bufferSize, WaitingStaregy.BLOCKING, 0, 0, null);
    }

    public CabBlocking(
        final int bufferSize,
        final Supplier<E> supplier) {

        super(bufferSize, WaitingStaregy.BLOCKING, 0, 0, supplier);
    }
}
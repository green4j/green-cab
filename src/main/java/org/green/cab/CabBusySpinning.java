package org.green.cab;

import java.util.function.Supplier;

public class CabBusySpinning<E, M> extends Cab<E, M> {
    public CabBusySpinning(final int bufferSize) {
        super(bufferSize, WaitingStaregy.BUSY_SPINNING, 0, 0, null);
    }

    public CabBusySpinning(
        final int bufferSize,
        final Supplier<E> supplier) {

        super(bufferSize, WaitingStaregy.BUSY_SPINNING, 0, 0, supplier);
    }
}

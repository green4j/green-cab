package org.green.jmh.cab;

import org.green.cab.Cab;

public class ToNilConsumer extends Thread implements AutoCloseable {
    private final Cab cab;

    public ToNilConsumer(final Cab cab) {
        this.cab = cab;
    }

    @Override
    public void run() {
        try {
            while (true) {
                cab.consumerCommit(cab.consumerNext());
            }
        } catch (final InterruptedException ignore) {
            cab.consumerInterrupt();
        }
    }

    @Override
    public void close() throws InterruptedException {
        interrupt();
        join();
    }
}
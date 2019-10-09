package org.green.jmh.cab;

import org.green.cab.Cab;
import org.green.cab.CabBackingOff;
import org.green.cab.CabBlocking;
import org.green.cab.CabYielding;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

public class CabBenchmark {
    private static final int CAB_SIZE = 10_000;
    private static final int BACKING_OFF_MAX_SPINS = 1_000;
    private static final int BACKING_OFF_MAX_YIELDS = 10_000;

    private abstract static class CabSetup {
        Cab cab;

        private NilConsumer consumer;

        @Setup(Level.Trial)
        public void doSetup() {
            cab = prepareCab();
            consumer = new NilConsumer(cab);
            consumer.start();
        }

        @TearDown(Level.Trial)
        public void doTearDown() throws InterruptedException {
            consumer.close();
        }

        protected abstract Cab prepareCab();
    }

    @State(Scope.Benchmark)
    public static class CabBlockingSetup extends CabSetup {
        @Override
        protected Cab prepareCab() {
            return new CabBlocking(CAB_SIZE);
        }
    }

    @State(Scope.Benchmark)
    public static class CabBackingOffSetup extends CabSetup {
        @Override
        protected Cab prepareCab() {
            return new CabBackingOff(CAB_SIZE, BACKING_OFF_MAX_SPINS, BACKING_OFF_MAX_YIELDS);
        }
    }

    @State(Scope.Benchmark)
    public static class CabYieldingSetup extends CabSetup {
        @Override
        protected Cab prepareCab() {
            return new CabYielding(CAB_SIZE);
        }
    }
}
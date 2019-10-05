package org.green.jmh.cab;

import org.green.cab.Cab;
import org.green.cab.ConsumerInterruptedException;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@Fork(3)
@Measurement(iterations = 3)
@Warmup(iterations = 3)
@BenchmarkMode(Mode.Throughput)
public class EntryProducerBenchmark extends CabBenchmark {

    @Benchmark
    @Threads(1)
    public void oneEntryProducerWithCabBlocking(
        final CabBlockingSetup cabSetup, final Blackhole blackhole)
        throws ConsumerInterruptedException, InterruptedException {

        final Cab cab = cabSetup.cab;

        final long ps = cab.producerNext();
        blackhole.consume(cab.getEntry(ps));
        cab.producerCommit(ps);
    }

    @Benchmark
    @Threads(2)
    public void twoEntryProducersWithCabBlocking(
        final CabBlockingSetup cabSetup, final Blackhole blackhole)
        throws ConsumerInterruptedException, InterruptedException {

        final Cab cab = cabSetup.cab;

        final long ps = cab.producerNext();
        blackhole.consume(cab.getEntry(ps));
        cab.producerCommit(ps);
    }

    @Benchmark
    @Threads(1)
    public void oneEntryProducerWithCabBackingOff(
        final CabBackingOffSetup cabSetup, final Blackhole blackhole)
        throws ConsumerInterruptedException, InterruptedException {

        final Cab cab = cabSetup.cab;

        final long ps = cab.producerNext();
        blackhole.consume(cab.getEntry(ps));
        cab.producerCommit(ps);
    }

    @Benchmark
    @Threads(2)
    public void twoEntryProducersWithCabBackingOff(
        final CabBackingOffSetup cabSetup, final Blackhole blackhole)
        throws ConsumerInterruptedException, InterruptedException {

        final Cab cab = cabSetup.cab;

        final long ps = cab.producerNext();
        blackhole.consume(cab.getEntry(ps));
        cab.producerCommit(ps);
    }

    @Benchmark
    @Threads(1)
    public void oneEntryProducerWithCabYielding(
        final CabBackingOffSetup cabSetup, final Blackhole blackhole)
        throws ConsumerInterruptedException, InterruptedException {

        final Cab cab = cabSetup.cab;

        final long ps = cab.producerNext();
        blackhole.consume(cab.getEntry(ps));
        cab.producerCommit(ps);
    }

    @Benchmark
    @Threads(2)
    public void twoEntryProducersWithCabYielding(
        final CabYieldingSetup cabSetup, final Blackhole blackhole)
        throws ConsumerInterruptedException, InterruptedException {

        final Cab cab = cabSetup.cab;

        final long ps = cab.producerNext();
        blackhole.consume(cab.getEntry(ps));
        cab.producerCommit(ps);
    }
}
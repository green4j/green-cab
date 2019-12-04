package org.green.jmh.cab;

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
public class MessageSenderBenchmark extends CabBenchmark {

    @Benchmark
    @Threads(1)
    public void oneMessageSenderWithCabBlocking(
            final CabBlockingSetup cabSetup, final Blackhole blackhole)
            throws ConsumerInterruptedException, InterruptedException {

        cabSetup.cab.send(this);
    }

    @Benchmark
    @Threads(2)
    public void twoMessageSendersWithCabBlocking(
            final CabBlockingSetup cabSetup, final Blackhole blackhole)
            throws ConsumerInterruptedException, InterruptedException {

        cabSetup.cab.send(this);
    }

    @Benchmark
    @Threads(1)
    public void oneMessageSenderWithCabBackingOff(
            final CabBackingOffSetup cabSetup, final Blackhole blackhole)
            throws ConsumerInterruptedException, InterruptedException {

        cabSetup.cab.send(this);
    }

    @Benchmark
    @Threads(2)
    public void twoMessageSendersWithCabBackingOff(
            final CabBackingOffSetup cabSetup, final Blackhole blackhole)
            throws ConsumerInterruptedException, InterruptedException {

        cabSetup.cab.send(this);
    }

    @Benchmark
    @Threads(1)
    public void oneMessageSenderWithCabYielding(
            final CabYieldingSetup cabSetup, final Blackhole blackhole)
            throws ConsumerInterruptedException, InterruptedException {

        cabSetup.cab.send(this);
    }

    @Benchmark
    @Threads(2)
    public void twoMessageSendersWithCabYielding(
            final CabYieldingSetup cabSetup, final Blackhole blackhole)
            throws ConsumerInterruptedException, InterruptedException {

        cabSetup.cab.send(this);
    }
}
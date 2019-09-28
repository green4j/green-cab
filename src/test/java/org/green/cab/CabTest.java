/**
 * MIT License
 * <p>
 * Copyright (c) 2019 Anatoly Gudkov
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE  LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.green.cab;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class CabTest {
    private static final int BUFFER_SIZE = 10_000;
    private static final int NUMBER_OF_ENTRIES_FOR_EACH_PRODUCER = 1_000_000;

    @Test
    public void testSupplier() {
        final Cab<Long, Object> cab = new CabBusySpinning<>(BUFFER_SIZE, new Supplier<Long>() {
            long value = -1;
            @Override
            public Long get() {
                value++;
                return value;
            }
        });
        for (int i = 0; i < cab.bufferSize(); i++) {
            assertEquals(i, cab.getEntry(i).longValue());
        }
    }

    @Test
    public void testRemoveEntry() {
        final Cab<Long, Object> cab = new CabBusySpinning<>(BUFFER_SIZE, new Supplier<Long>() {
            long value = -1;
            @Override
            public Long get() {
                value++;
                return value;
            }
        });

        final int index = BUFFER_SIZE / 2;

        final long entry = cab.removeEntry(index);

        assertEquals(index, entry);

        assertNull(cab.getEntry(index));
    }

    @Test
    public void testConsumerInterrupted() {
        final Cab<Long, Object> cab = new CabBusySpinning<>(BUFFER_SIZE);

        cab.consumerInterrupt();

        int ceExceptionCount = 0;

        try {
            cab.consumerNext();
        } catch (ConsumerInterruptedException e) {
            ceExceptionCount++;
        } catch (InterruptedException e) {
        }

        try {
            cab.producerNext();
        } catch (ConsumerInterruptedException e) {
            ceExceptionCount++;
        } catch (InterruptedException e) {
        }

        try {
            cab.send(null);
        } catch (ConsumerInterruptedException e) {
            ceExceptionCount++;
        } catch (InterruptedException e) {
        }

        assertEquals(3, ceExceptionCount);
    }

    @Test(timeout = 3000)
    public void testSpScBusySpinning() throws InterruptedException {
        testNpSc(new CabBusySpinning<>(BUFFER_SIZE), 1, NUMBER_OF_ENTRIES_FOR_EACH_PRODUCER, false);
    }

    @Test(timeout = 3000)
    public void testSpScYielding() throws InterruptedException {
        testNpSc(new CabYielding<>(BUFFER_SIZE), 1, NUMBER_OF_ENTRIES_FOR_EACH_PRODUCER, false);
    }

    @Test(timeout = 3000)
    public void testSpScBackingOff() throws InterruptedException {
        testNpSc(new CabBackingOff<>(BUFFER_SIZE, 10, 100), 1, NUMBER_OF_ENTRIES_FOR_EACH_PRODUCER, true);
    }

    @Test(timeout = 3000)
    public void testSpScBlocking() throws InterruptedException {
        testNpSc(new CabBlocking<>(BUFFER_SIZE), 1, NUMBER_OF_ENTRIES_FOR_EACH_PRODUCER, false);
    }

    @Test(timeout = 3000)
    public void test3pScBusySpinning() throws InterruptedException {
        testNpSc(new CabBusySpinning<>(BUFFER_SIZE), 3, NUMBER_OF_ENTRIES_FOR_EACH_PRODUCER, false);
    }

    @Test(timeout = 3000)
    public void test3pScYielding() throws InterruptedException {
        testNpSc(new CabYielding<>(BUFFER_SIZE), 3, NUMBER_OF_ENTRIES_FOR_EACH_PRODUCER, false);
    }

    @Test(timeout = 3000)
    public void test3pScBackingOff() throws InterruptedException {
        testNpSc(new CabBackingOff<>(BUFFER_SIZE, 1000, 10000), 3, NUMBER_OF_ENTRIES_FOR_EACH_PRODUCER, false);
    }

    @Test(timeout = 3000)
    public void test3pScBlocking() throws InterruptedException {
        testNpSc(new CabBlocking<>(BUFFER_SIZE), 3, NUMBER_OF_ENTRIES_FOR_EACH_PRODUCER, false);
    }

    private void testNpSc(
        final Cab<Long, Message> cab,
        final int numberOfProducersSenders,
        final int numberOfEntriesForEach,
        final boolean slowConsumer) throws InterruptedException {

        final ProducerSenderSet psSet = new ProducerSenderSet(cab, numberOfProducersSenders, numberOfEntriesForEach);
        final Consumer cs = new Consumer(
            cab,
            psSet.size(),
            psSet.getTotalNumberOfEntries(),
            psSet.getTotalNumberOfMessages(),
            slowConsumer);

        cs.start();
        psSet.start();

        psSet.join();
        cs.join();

        assertEquals(psSet.getTotalNumberOfEntries(), cs.getNumberOfEntries());
        assertEquals(psSet.getTotalNumberOfMessages(), cs.getNumberOfMessages());

        assertEquals(1, cs.getMaxEntryValueDiff());
        assertEquals(1, cs.getMaxMessageValueDiff());
    }

    class Message {
        private long value;

        Message() {
        }

        Message(final int id, final int value) {
            set(id, value);
        }

        public void set(final int id, final int value) {
            this.value = ((long) id << 32) | value;
        }

        public int id() {
            return (int) (value >> 32);
        }

        public int value() {
            return (int) (value & 0xffffffff);
        }

        @Override
        public String toString() {
            return id() + ":" + value();
        }
    }

    class ProducerSender extends Thread {
        private static final int MESSAGING_FACTOR = 100;

        private final int id;
        private final Cab<Long, Message> cab;
        private final int numberOfEntries;
        private final int numberOfMessages;

        ProducerSender(final int id, final Cab<Long, Message> cab, final int numberOfEntries) {
            super(ProducerSender.class.getSimpleName() + "#" + id);
            this.id = id;
            this.cab = cab;

            this.numberOfEntries = numberOfEntries;
            this.numberOfMessages = numberOfEntries / MESSAGING_FACTOR;
        }

        int getNumberOfEntries() {
            return numberOfEntries;
        }

        int getNumberOfMessages() {
            return numberOfMessages;
        }

        public void run() {
            try {
                int messageCount = 0;
                for (int i = 0; i < numberOfEntries; i++) {
                    final long sequence = cab.producerNext();
                    cab.setEntry(sequence, sequence);
                    cab.producerCommit(sequence);

                    if (i % MESSAGING_FACTOR == 0) {
                        cab.send(new Message(id, messageCount++));
                    }
                }
            } catch (final ConsumerInterruptedException | InterruptedException e) {
                e.printStackTrace(System.err);
            }
        }
    }

    class ProducerSenderSet {
        private final ProducerSender[] set;

        private int totalNumberOfEntries;
        private int totalNumbersOfMessages;

        ProducerSenderSet(
            final Cab<Long, Message> cab,
            final int numberOfProducers,
            final int numberOfEntriesForEach) {

            this.set = new ProducerSender[numberOfProducers];
            for (int i = 0; i < set.length; i++) {
                final ProducerSender ps = new ProducerSender(i, cab, numberOfEntriesForEach);
                set[i] = ps;
                totalNumberOfEntries += ps.getNumberOfEntries();
                totalNumbersOfMessages += ps.getNumberOfMessages();
            }
        }

        int size() {
            return set.length;
        }

        int getTotalNumberOfEntries() {
            return totalNumberOfEntries;
        }

        int getTotalNumberOfMessages() {
            return totalNumbersOfMessages;
        }

        void start() {
            for (final ProducerSender ps : set) {
                ps.start();
            }
        }

        void join() throws InterruptedException {
            for (final ProducerSender ps : set) {
                ps.join();
            }
        }
    }

    class Consumer extends Thread {
        private final Cab<Long, Message> cab;

        private final int totalNumberOfEntries;
        private final int totalNumbersOfMessages;

        private final boolean isSlow;

        private final int[] lastReceivedMessageValues;
        private final int[] maxMessageValueDiff;

        private long lastReceivedEntryValue;
        private long maxEntryValueDiff;

        private int numberOfEntries = 0;
        private int numberOfMessages = 0;

        Consumer(
            final Cab<Long, Message> cab,
            final int numberOfSenders,
            final int totalNumberOfEntries,
            final int totalNumbersOfMessages,
            final boolean isSlow) {

            super(Consumer.class.getName());
            this.cab = cab;

            this.totalNumberOfEntries = totalNumberOfEntries;
            this.totalNumbersOfMessages = totalNumbersOfMessages;

            this.isSlow = isSlow;

            lastReceivedMessageValues = new int[numberOfSenders];
            for (int i = 0; i < lastReceivedMessageValues.length; i++) {
                lastReceivedMessageValues[i] = -1;
            }

            maxMessageValueDiff = new int[numberOfSenders];

            lastReceivedEntryValue = -1;

            maxEntryValueDiff = 0;
        }

        int getNumberOfEntries() {
            return numberOfEntries;
        }

        int getNumberOfMessages() {
            return numberOfMessages;
        }

        long getMaxEntryValueDiff() {
            return maxEntryValueDiff;
        }

        int getMaxMessageValueDiff() {
            int result = maxMessageValueDiff[0];
            for (int i = 1; i < maxMessageValueDiff.length; i++) {
                if (maxMessageValueDiff[i] > result) {
                    result = maxMessageValueDiff[i];
                }
            }
            return result;
        }

        public void run() {
            try {
                while (numberOfEntries < totalNumberOfEntries || numberOfMessages < totalNumbersOfMessages) {
                    final long sequence = cab.consumerNext();

                    if (sequence == Cab.MESSAGE_RECEIVED_SEQUENCE) {
                        numberOfMessages++;

                        final Message msg = cab.getMessage();

                        final int lastValue = lastReceivedMessageValues[msg.id()];
                        final int newValue = msg.value();

                        final int lastDiff = maxMessageValueDiff[msg.id()];
                        final int newDiff = newValue - lastValue;

                        if (newDiff > lastDiff) {
                            maxMessageValueDiff[msg.id()] = newDiff;
                        }

                        lastReceivedMessageValues[msg.id()] = newValue;
                    } else {
                        numberOfEntries++;

                        final Long entry = cab.getEntry(sequence);

                        final long newDiff = entry - lastReceivedEntryValue;

                        if (newDiff > maxEntryValueDiff) {
                            maxEntryValueDiff = newDiff;
                        }

                        lastReceivedEntryValue = entry;
                    }

                    cab.consumerCommit(sequence);

                    if (isSlow) {
                        LockSupport.parkNanos(1);
                    }
                }
            } catch (final ConsumerInterruptedException | InterruptedException e) {
                e.printStackTrace(System.err);
            }
        }
    }
}

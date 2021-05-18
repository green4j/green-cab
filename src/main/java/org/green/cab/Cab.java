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

import sun.misc.Unsafe;

import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.green.cab.Utils.CACHE_LINE_SIZE;
import static org.green.cab.Utils.nextPowerOfTwo;

abstract class CabPad0 {
    protected long p01, p02, p03, p04, p05, p06, p07;
    protected long p08, p09, p010, p011, p012, p013, p014, p015;
}

abstract class ConsumerSequence extends CabPad0 {
    protected volatile long consumerSequence;
}

abstract class CabPad1 extends ConsumerSequence {
    protected long p11, p12, p13, p14, p15, p16, p17;
    protected long p18, p19, p110, p111, p112, p113, p114, p115;
}

abstract class UncommittedProducersSequence extends CabPad1 {
    protected volatile long uncommittedProducersSequence;
}

abstract class CabPad2 extends UncommittedProducersSequence {
    protected long p21, p22, p23, p24, p25, p26, p27;
    protected long p28, p29, p210, p211, p212, p213, p214, p215;
}

abstract class Message extends CabPad2 {
    protected volatile Object message;
}

abstract class CabPad3 extends Message {
    protected long p31, p32, p33, p34, p35, p36, p37;
    protected long p38, p39, p310, p311, p312, p313, p314, p315;
}

abstract class MessageCache extends CabPad3 {
    protected Object messageCache; // used by Consumer only, no any membars required
}

abstract class CabPad4 extends MessageCache {
    protected long p41, p42, p43, p44, p45, p46, p47;
    protected long p48, p49, p410, p411, p412, p413, p414, p415;
}

/**
 * This class presents a pair of CSP-style Channel and Ring Buffer (CAB - Channel And Buffer). This structure aims to be
 * a building block of concurrent data processing applications.
 * <p>
 * The pattern of usage is the following: a number of data producing threads put their data entries into
 * the Ring Buffer, some controlling threads send their commands as messages to the Channel and ONLY ONE single
 * worker thread (the consumer) consumes all incoming data entries and messages and processes them one by one.
 * This makes the flow of entries and messages linearized.
 * Such technique may be very useful, for example, to implement an event loop in a thread of the consumer.
 * <p>
 * For example:
 * <p>
 * An entry producer:
 * <pre>
 *      Cab cab = ...
 *
 *      long sequence;
 *      try {
 *          sequence = cab.producerNext();
 *      } catch (ConsumerInterruptedException e) {
 *          // happens if the consumer was interrupted by consumerInterrupt() and cannot process incoming entries
 *      } catch (InterruptedException e) {
 *          // happens if the current thread was interrupted
 *      }
 *
 *      Object entry = cab.getEntry(sequence);
 *
 *      // ... modify the entry ...
 *
 *      cab.producerCommit(sequence);
 * </pre>
 * <p>
 * A message sender:
 * <pre>
 *      Cab cab = ...
 *
 *      try {
 *          cab.send(message);,
 *      } catch (ConsumerInterruptedException e) {
 *          // happens if the consumer was interrupted by consumerInterrupt() and cannot process incoming messages
 *      } catch (InterruptedException e) {
 *          // happens if the current thread was interrupted
 *      }
 * </pre>
 * <p>
 * A consumer:
 * <pre>
 *      Cab cab = ...
 *
 *      long sequence;
 *      try {
 *          sequence = cab.consumerNext();
 *      } catch (InterruptedException e) {
 *          // happens if the current thread was interrupted
 *      }
 *
 *      if (sequence == Cab.MESSAGE_RECEIVED_SEQUENCE) { // a message can be read from the Channel
 *          Object message = cab.getMessage();
 *
 *          // ... process the message ...
 *
 *      } else { // an entry can be read from the Ring Buffer
 *          Object entry = cab.getEntry(sequence);
 *
 *          // ... process the entry ...
 *      }
 *
 *      cab.consumerCommit(sequence);
 * </pre>
 *
 * @param <E> types of entries in the Ring Buffer
 * @param <M> type of message in the Channel
 */
public abstract class Cab<E, M> extends CabPad4 {

    enum WaitingStaregy {
        BUSY_SPINNING, YIELDING, BACKING_OFF, BLOCKING
    }

    public static final long MESSAGE_RECEIVED_SEQUENCE = Long.MAX_VALUE;

    public static final long CONSUMER_INTERRUPTED_SEQUENCE = Long.MIN_VALUE;

    private static final long INITIAL_SEQUENCE = -1;

    private static final int BACKING_OFF_INITIAL_STATE = 0;
    private static final int BACKING_OFF_SPINNING_STATE = 1;
    private static final int BACKING_OFF_YIELDING_STATE = 2;
    private static final int BACKING_OFF_WAIT_ON_MUTEX_STATE = 3;

    private static final String BUFFER_SIZE_MUST_NOT_BE_LESS_THAN_1_MESSAGE = "bufferSize must not be less than 1";
    private static final String CONSUMER_WAS_CLOSED_MESSAGE = "Consumer was closed";
    private static final String UNEXPECTED_INT_ELEMENT_SIZE_MESSAGE = "Unexpected int[] element size";
    private static final String UNEXPECTED_OBJECT_ELEMENT_SIZE_MESSAGE = "Unexpected Object[] element size";

    private static final Unsafe UNSAFE = Utils.getUnsafe();

    private static final int ENTRY_ARRAY_ELEMENT_SHIFT;
    private static final int ENTRY_ARRAY_PAD;
    private static final long ENTRY_ARRAY_BASE;

    private static final int STATE_ARRAY_ELEMENT_SHIFT;
    private static final int STATE_ARRAY_PAD;
    private static final long STATE_ARRAY_BASE;

    private static final long CONSUMER_SEQUENCE_OFFSET;
    private static final long UNCOMMITTED_PRODUCERS_SEQUENCE_OFFSET;
    private static final long MESSAGE_OFFSET;

    static {
        int scale;
        scale = UNSAFE.arrayIndexScale(Object[].class);
        if (4 == scale) {
            ENTRY_ARRAY_ELEMENT_SHIFT = 2;
        } else if (8 == scale) {
            ENTRY_ARRAY_ELEMENT_SHIFT = 3;
        } else {
            throw new IllegalStateException(UNEXPECTED_OBJECT_ELEMENT_SIZE_MESSAGE);
        }
        ENTRY_ARRAY_PAD = CACHE_LINE_SIZE * 2 / scale;
        ENTRY_ARRAY_BASE = UNSAFE.arrayBaseOffset(Object[].class) + (ENTRY_ARRAY_PAD * scale);

        scale = UNSAFE.arrayIndexScale(int[].class);
        if (4 == scale) {
            STATE_ARRAY_ELEMENT_SHIFT = 2;
        } else {
            throw new IllegalStateException(UNEXPECTED_INT_ELEMENT_SIZE_MESSAGE);
        }
        STATE_ARRAY_PAD = CACHE_LINE_SIZE * 2 / scale;
        STATE_ARRAY_BASE = UNSAFE.arrayBaseOffset(int[].class) + (STATE_ARRAY_PAD * scale);

        try {
            CONSUMER_SEQUENCE_OFFSET = UNSAFE.objectFieldOffset(
                    ConsumerSequence.class.getDeclaredField("consumerSequence"));
        } catch (final Exception e) {
            throw new Error(e);
        }
        try {
            UNCOMMITTED_PRODUCERS_SEQUENCE_OFFSET = UNSAFE.objectFieldOffset(
                    UncommittedProducersSequence.class.getDeclaredField("uncommittedProducersSequence"));
        } catch (final Exception e) {
            throw new Error(e);
        }
        try {
            MESSAGE_OFFSET = UNSAFE.objectFieldOffset(
                    Message.class.getDeclaredField("message"));
        } catch (final Exception ex) {
            throw new Error(ex);
        }
    }

    private final long indexMask;

    private final int bufferSize;
    private final Object[] entries;
    private final int[] entryStates;

    private final WaitingStaregy waitingStaregy;

    private final Object mutex = new Object();

    private final long maxSpins;
    private final long maxYields;

    protected Cab(
            final int bufferSize,
            final WaitingStaregy waitingStaregy,
            final long maxSpins,
            final long maxYields,
            final Supplier<E> supplier) {

        if (bufferSize < 1) {
            throw new IllegalArgumentException(BUFFER_SIZE_MUST_NOT_BE_LESS_THAN_1_MESSAGE);
        }
        final int normalizedBufferSize = nextPowerOfTwo(bufferSize);

        this.indexMask = normalizedBufferSize - 1;

        this.bufferSize = normalizedBufferSize;
        this.entries = new Object[normalizedBufferSize + 2 * ENTRY_ARRAY_PAD];
        this.entryStates = new int[normalizedBufferSize + 2 * STATE_ARRAY_PAD];

        this.waitingStaregy = waitingStaregy;
        this.maxSpins = maxSpins;
        this.maxYields = maxYields;

        UNSAFE.putLongVolatile(this, CONSUMER_SEQUENCE_OFFSET, INITIAL_SEQUENCE);
        UNSAFE.putLongVolatile(this, UNCOMMITTED_PRODUCERS_SEQUENCE_OFFSET, INITIAL_SEQUENCE);

        if (supplier != null) {
            for (int i = 0; i < normalizedBufferSize; i++) {
                setEntry(i, supplier.get());
            }
        }

        UNSAFE.putObjectVolatile(this, MESSAGE_OFFSET, null);
    }

    /**
     * Returns actual Ring Buffer's size which is the next power of two of a value passed to the constructor.
     *
     * @return actual buffer size
     */
    public int bufferSize() {
        return bufferSize;
    }

    /**
     * Returns a sequence for a producer thread to address the next available entry with getEntry(sequence),
     * setEntry(sequence) or removeEntry(sequence).
     *
     * @return sequence to address available entry
     * @throws ConsumerInterruptedException if the consumer was interrupted
     * @throws InterruptedException         if the current thread was interrupted
     */
    public long producerNext() throws ConsumerInterruptedException, InterruptedException {
        final long nextSequence = UNSAFE.getAndAddLong(
                this, UNCOMMITTED_PRODUCERS_SEQUENCE_OFFSET, 1L) + 1L; // fetch-and-add

        while (true) {
            final long consumerSequence = UNSAFE.getLongVolatile(this, CONSUMER_SEQUENCE_OFFSET);

            if (consumerSequence == CONSUMER_INTERRUPTED_SEQUENCE) {
                throw new ConsumerInterruptedException();
            }

            if (nextSequence - consumerSequence <= bufferSize) { // there is some free space in the buffer
                break;
            }

            // we are here because the buffer is full, so...
            LockSupport.parkNanos(1); // let's give a good chance to the consumer

            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }

        return nextSequence;
    }

    /**
     * Commits the sequence to make it available for the consumer thread to be read.
     *
     * @param sequence to be committed
     */
    public void producerCommit(final long sequence) {
        final long stateAddress = stateAddress(sequence);

        UNSAFE.putOrderedInt(entryStates, stateAddress, 1);

        switch (waitingStaregy) {
            case BUSY_SPINNING:
            case YIELDING:
                break;

            case BACKING_OFF:
            case BLOCKING:
                final Object mtx = mutex;

                synchronized (mtx) {
                    mtx.notifyAll();
                }
                break;

            default:
                throw new IllegalStateException();
        }
    }

    /**
     * Sends a message to the Channel.
     *
     * @param msg a message to be sent
     * @throws ConsumerInterruptedException if the consumer was interrupted
     * @throws InterruptedException         if the current thread was interrupted
     */
    public void send(final M msg) throws ConsumerInterruptedException, InterruptedException {
        long consumerSequence = UNSAFE.getLongVolatile(this, CONSUMER_SEQUENCE_OFFSET);
        if (consumerSequence == CONSUMER_INTERRUPTED_SEQUENCE) {
            throw new ConsumerInterruptedException();
        }

        switch (waitingStaregy) {
            case BUSY_SPINNING: {
                while (!UNSAFE.compareAndSwapObject(this, MESSAGE_OFFSET, null, msg)) {
                    consumerSequence = UNSAFE.getLongVolatile(this, CONSUMER_SEQUENCE_OFFSET);
                    if (consumerSequence == CONSUMER_INTERRUPTED_SEQUENCE) {
                        throw new ConsumerInterruptedException();
                    }

                    if (Thread.interrupted()) {
                        throw new InterruptedException();
                    }
                }
                break;
            }

            case YIELDING: {
                while (!UNSAFE.compareAndSwapObject(this, MESSAGE_OFFSET, null, msg)) {

                    Thread.yield();

                    consumerSequence = UNSAFE.getLongVolatile(this, CONSUMER_SEQUENCE_OFFSET);
                    if (consumerSequence == CONSUMER_INTERRUPTED_SEQUENCE) {
                        throw new ConsumerInterruptedException();
                    }

                    if (Thread.interrupted()) {
                        throw new InterruptedException();
                    }
                }
                break;
            }

            case BACKING_OFF: {
                int state = BACKING_OFF_INITIAL_STATE;
                long spins = 0;
                long yields = 0;

                final Object mtx = mutex;

                _endOfWaiting:
                while (!UNSAFE.compareAndSwapObject(this, MESSAGE_OFFSET, null, msg)) {
                    switch (state) {
                        case BACKING_OFF_INITIAL_STATE:
                            state = BACKING_OFF_SPINNING_STATE;
                            spins++;
                            break;

                        case BACKING_OFF_SPINNING_STATE:
                            if (++spins > maxSpins) {
                                state = BACKING_OFF_YIELDING_STATE;
                            }
                            break;

                        case BACKING_OFF_YIELDING_STATE:
                            if (++yields > maxYields) {
                                state = BACKING_OFF_WAIT_ON_MUTEX_STATE;
                            } else {
                                Thread.yield();
                            }
                            break;

                        case BACKING_OFF_WAIT_ON_MUTEX_STATE:
                            synchronized (mtx) {
                                while (!UNSAFE.compareAndSwapObject(this, MESSAGE_OFFSET, null, msg)) {

                                    mtx.wait();

                                    consumerSequence = UNSAFE.getLongVolatile(this, CONSUMER_SEQUENCE_OFFSET);
                                    if (consumerSequence == CONSUMER_INTERRUPTED_SEQUENCE) {
                                        throw new ConsumerInterruptedException();
                                    }
                                }
                                break _endOfWaiting;
                            }
                        default:
                            throw new IllegalStateException();
                    }

                    // we are here while spinning and yielding

                    consumerSequence = UNSAFE.getLongVolatile(this, CONSUMER_SEQUENCE_OFFSET);
                    if (consumerSequence == CONSUMER_INTERRUPTED_SEQUENCE) {
                        throw new ConsumerInterruptedException();
                    }

                    if (Thread.interrupted()) {
                        throw new InterruptedException();
                    }
                }

                synchronized (mtx) {
                    mtx.notifyAll();
                }
                break;
            }

            case BLOCKING: {
                final Object mtx = mutex;

                if (!UNSAFE.compareAndSwapObject(this, MESSAGE_OFFSET, null, msg)) {
                    synchronized (mtx) {
                        while (!UNSAFE.compareAndSwapObject(this, MESSAGE_OFFSET, null, msg)) {

                            mtx.wait();

                            consumerSequence = UNSAFE.getLongVolatile(this, CONSUMER_SEQUENCE_OFFSET);
                            if (consumerSequence == CONSUMER_INTERRUPTED_SEQUENCE) {
                                throw new ConsumerInterruptedException();
                            }
                        }
                    }
                }

                synchronized (mtx) {
                    mtx.notifyAll();
                }
                break;
            }

            default:
                throw new IllegalStateException();
        }

    }

    /**
     * Returns a sequence for the consumer thread to address next available message or entry.
     *
     * @return sequence to be read. If the value is MESSAGE_RECEIVED_SEQUENCE, a message is ready to be read
     * with getMessage(), otherwise new entry can be accessed with getEntry(sequence).
     * <p>
     * This method can be called from one single consumer thread only.
     * @throws InterruptedException if the current thread was interrupted
     */
    public long consumerNext() throws InterruptedException {
        long consumerSequence = UNSAFE.getLong(this, CONSUMER_SEQUENCE_OFFSET); // this thread owns the value,
        // so, no any membars required to read

        if (consumerSequence == CONSUMER_INTERRUPTED_SEQUENCE) {
            throw new IllegalStateException(CONSUMER_WAS_CLOSED_MESSAGE, new ConsumerInterruptedException());
        }

        // check the message first
        Object msg;

        msg = UNSAFE.getObjectVolatile(this, MESSAGE_OFFSET);
        if (msg != null) {
            messageCache = msg;
            return MESSAGE_RECEIVED_SEQUENCE;
        }

        // continue with the buffer and the message again
        consumerSequence++;

        final long stateAddress = stateAddress(consumerSequence);

        final int[] states = entryStates;

        switch (waitingStaregy) {
            case BUSY_SPINNING: {
                while (UNSAFE.getIntVolatile(states, stateAddress) == 0) {
                    if (Thread.interrupted()) {
                        throw new InterruptedException();
                    }

                    msg = UNSAFE.getObjectVolatile(this, MESSAGE_OFFSET);
                    if (msg != null) {
                        messageCache = msg;
                        return MESSAGE_RECEIVED_SEQUENCE;
                    }
                }
                break;
            }

            case YIELDING: {
                while (UNSAFE.getIntVolatile(states, stateAddress) == 0) {
                    Thread.yield();

                    if (Thread.interrupted()) {
                        throw new InterruptedException();
                    }

                    msg = UNSAFE.getObjectVolatile(this, MESSAGE_OFFSET);
                    if (msg != null) {
                        messageCache = msg;
                        return MESSAGE_RECEIVED_SEQUENCE;
                    }
                }
                break;
            }

            case BACKING_OFF: {
                int state = BACKING_OFF_INITIAL_STATE;
                long spins = 0;
                long yields = 0;

                _endOfBackingOff:
                while (UNSAFE.getIntVolatile(states, stateAddress) == 0) {
                    switch (state) {
                        case BACKING_OFF_INITIAL_STATE:
                            state = BACKING_OFF_SPINNING_STATE;
                            spins++;
                            break;

                        case BACKING_OFF_SPINNING_STATE:
                            if (++spins > maxSpins) {
                                state = BACKING_OFF_YIELDING_STATE;
                            }
                            break;

                        case BACKING_OFF_YIELDING_STATE:
                            if (++yields > maxYields) {
                                state = BACKING_OFF_WAIT_ON_MUTEX_STATE;
                            } else {
                                Thread.yield();
                            }
                            break;

                        case BACKING_OFF_WAIT_ON_MUTEX_STATE:
                            final Object mtx = mutex;

                            synchronized (mtx) {
                                while (true) {
                                    if (UNSAFE.getIntVolatile(states, stateAddress) == 0) {

                                        msg = UNSAFE.getObjectVolatile(this, MESSAGE_OFFSET);
                                        if (msg != null) {
                                            messageCache = msg;
                                            return MESSAGE_RECEIVED_SEQUENCE;
                                        }

                                        mtx.wait();

                                        continue;
                                    }
                                    break;
                                }
                            }
                            break _endOfBackingOff;

                        default:
                            throw new IllegalStateException();
                    }

                    // we are here while spinning and yielding

                    msg = UNSAFE.getObjectVolatile(this, MESSAGE_OFFSET);
                    if (msg != null) {
                        messageCache = msg;
                        return MESSAGE_RECEIVED_SEQUENCE;
                    }

                    if (Thread.interrupted()) {
                        throw new InterruptedException();
                    }
                }
                break;
            }

            case BLOCKING: {
                final Object mtx = mutex;

                synchronized (mtx) {
                    while (true) {
                        if (UNSAFE.getIntVolatile(states, stateAddress) == 0) {

                            msg = UNSAFE.getObjectVolatile(this, MESSAGE_OFFSET);
                            if (msg != null) {
                                messageCache = msg;
                                return MESSAGE_RECEIVED_SEQUENCE;
                            }

                            mtx.wait();

                            continue;
                        }
                        break;
                    }
                }
                break;
            }

            default:
                throw new IllegalStateException();
        }

        return consumerSequence;
    }

    /**
     * Commits the current consumer's sequence to signal the consumer ir ready to process next message or next entry.
     * <p>
     * This method can be called from one single consumer thread only.
     *
     * @param sequence to be committed
     */
    public void consumerCommit(final long sequence) {
        if (sequence == MESSAGE_RECEIVED_SEQUENCE) {
            UNSAFE.putOrderedObject(this, MESSAGE_OFFSET, null);
            switch (waitingStaregy) {
                case BUSY_SPINNING:
                case YIELDING:
                    break;

                case BACKING_OFF:
                case BLOCKING:
                    final Object mtx = mutex;

                    synchronized (mtx) {
                        mtx.notifyAll();
                    }
                    break;

                default:
                    throw new IllegalStateException();
            }
            return;
        }

        final long stateAddress = stateAddress(sequence);
        UNSAFE.putOrderedInt(entryStates, stateAddress, 0);

        UNSAFE.putOrderedLong(this, CONSUMER_SEQUENCE_OFFSET, sequence);
    }

    /**
     * Interrupts the consumer. Entry producers and message senders will get an {@link ConsumerInterruptedException}
     * after this call.
     */
    public void consumerInterrupt() {
        UNSAFE.putLongVolatile(this, CONSUMER_SEQUENCE_OFFSET, CONSUMER_INTERRUPTED_SEQUENCE);
    }

    /**
     * Returns an entry from the position identified by the sequence from the Ring Buffer.
     *
     * @param sequence identifier of the entry's position
     * @return the entry
     */
    @SuppressWarnings("unchecked")
    public E getEntry(final long sequence) {
        return (E) UNSAFE.getObjectVolatile(entries, entryAddress(sequence));
    }

    /**
     * Removes an entry from the position identified by the sequence from the Ring Buffer.
     *
     * @param sequence identifier of the entry's position
     * @return removed entry
     */
    @SuppressWarnings("unchecked")
    public E removeEntry(final long sequence) {
        final long entryAddress = entryAddress(sequence);
        final E result = (E) UNSAFE.getObjectVolatile(entries, entryAddress);
        UNSAFE.putObjectVolatile(entries, entryAddress, null);
        return result;
    }

    /**
     * Sets an entry to the position identified by the sequence in the Ring Buffer.
     *
     * @param sequence identifier of the entry's position
     * @param entry    to be set
     */
    public void setEntry(final long sequence, final E entry) {
        final long entryAddress = entryAddress(sequence);
        UNSAFE.putObjectVolatile(entries, entryAddress, entry);
    }

    /**
     * Returns currently available message from the Channel
     *
     * @return a message
     */
    @SuppressWarnings("unchecked")
    public M getMessage() {
        return (M) messageCache;
    }

    private long entryAddress(final long sequence) {
        return ENTRY_ARRAY_BASE + ((sequence & indexMask) << ENTRY_ARRAY_ELEMENT_SHIFT);
    }

    private long stateAddress(final long sequence) {
        return STATE_ARRAY_BASE + ((sequence & indexMask) << STATE_ARRAY_ELEMENT_SHIFT);
    }
}

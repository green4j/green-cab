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

import static org.green.cab.Utils.nextPowerOfTwo;

abstract class CabPad0 {
    protected long p1, p2, p3, p4, p5, p6, p7;
}

abstract class ConsumerSequence extends CabPad0 {
    protected volatile long consumerSequence;
}

abstract class CabPad1 extends ConsumerSequence {
    protected long p9, p10, p11, p12, p13, p14, p15;
}

abstract class UncommittedProducersSequence extends CabPad1 {
    protected volatile long uncommittedProducersSequence;
}

abstract class CabPad2 extends UncommittedProducersSequence {
    protected long p17, p18, p19, p20, p21, p22, p23;
}

abstract class Message extends CabPad2 {
    protected volatile Object message;
}

abstract class CabPad3 extends Message {
    protected long p25, p26, p27, p28, p29, p30, p31;
}

abstract class MessageCache extends CabPad3 {
    protected Object messageCache; // used by Consumer only, no any membars required
}

abstract class CabPad4 extends MessageCache {
    protected long p33, p34, p35, p36, p37, p38, p39;
}

/**
 * This class presents a pair of CSP-style Channel and Ring Buffer (CAB - Channel And Buffer). This structure aims to be
 * a building block of concurrent data processing applications.
 * <p>
 * The pattern of usage is the following: a number of data producing threads put their data entries into
 * the Ring Buffer, some controlling threads send their commands as messages to the Channel and ONLY ONE single
 * worker thread (the consumer) consumes all incoming data entries and messages and processes them one by one.
 * This makes the flow of entries and messages linearized.
 * Such technique may be very useful, for example, when a processing algorithm is going to be implemented
 * as a Finite State Machine (FSM).
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
 *          cab.send(message);
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

    private static final long INITIAL_SEQUENCE = -1;
    private static final String UNEXPECTED_OBJECT_ELEMENT_SIZE_MESSAGE = "Unexpected Object[] element size";
    private static final String UNEXPECTED_INT_ELEMENT_SIZE_MESSAGE = "Unexpected int[] element size";
    private static final String BUFFER_SIZE_MUST_NOT_BE_LESS_THAN_1_MESSAGE = "bufferSize must not be less than 1";

    private static final Unsafe UNSAFE = Utils.getUnsafe();

    private static final int CACHE_LINE_SIZE = 64;

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
        } catch (final Exception ex) {
            throw new Error(ex);
        }
        try {
            UNCOMMITTED_PRODUCERS_SEQUENCE_OFFSET = UNSAFE.objectFieldOffset(
                    UncommittedProducersSequence.class.getDeclaredField("uncommittedProducersSequence"));
        } catch (final Exception ex) {
            throw new Error(ex);
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
     * @throws InterruptedException if the current thread was interrupted
     */
    public long producerNext() throws InterruptedException {
        final long nextSequence = UNSAFE.getAndAddLong(
            this, UNCOMMITTED_PRODUCERS_SEQUENCE_OFFSET, 1L) + 1L; // fetch-and-add

        while (true) {
            final long cs = UNSAFE.getLongVolatile(this, CONSUMER_SEQUENCE_OFFSET);

            if (nextSequence - cs <= bufferSize) { // there is some free space in the buffer
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
        final long address = stateAddress(sequence);

        UNSAFE.putOrderedInt(entryStates, address, 1);

        switch (waitingStaregy) {
            case BUSY_SPINNING:
            case YIELDING:
                break;

            case BACKING_OFF:
            case BLOCKING:
                final Object m = mutex;

                synchronized (m) {
                    m.notifyAll();
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
     * @throws InterruptedException if the current thread was interrupted
     */
    public void send(final M msg) throws InterruptedException {
        switch (waitingStaregy) {
            case BUSY_SPINNING: {
                while (!UNSAFE.compareAndSwapObject(this, MESSAGE_OFFSET, null, msg)) {

                    Utils.onSpinWait();

                    if (Thread.interrupted()) {
                        throw new InterruptedException();
                    }
                }
                break;
            }

            case YIELDING: {
                while (!UNSAFE.compareAndSwapObject(this, MESSAGE_OFFSET, null, msg)) {

                    Thread.yield();

                    if (Thread.interrupted()) {
                        throw new InterruptedException();
                    }
                }
                break;
            }

            case BACKING_OFF: {
                int state = 0;
                long spins = 0;
                long yields = 0;

                final Object m = mutex;

                _endOfWaiting:
                while (!UNSAFE.compareAndSwapObject(this, MESSAGE_OFFSET, null, msg)) {
                    switch (state) {
                        case 0: // initial state
                            state = 1;
                            spins++;
                            break;

                        case 1: // spinning
                            Utils.onSpinWait();
                            if (++spins > maxSpins) {
                                state = 2;
                            }
                            break;

                        case 2: // yielding
                            if (++yields > maxYields) {
                                state = 3;
                            } else {
                                Thread.yield();
                            }
                            break;

                        case 3: // wait with the mutex
                            synchronized (m) {
                                while (!UNSAFE.compareAndSwapObject(this, MESSAGE_OFFSET, null, msg)) {

                                    m.wait();

                                }
                                break _endOfWaiting;
                            }
                        default:
                            throw new IllegalStateException();
                    }

                    if (Thread.interrupted()) {
                        throw new InterruptedException();
                    }
                }

                synchronized (m) {
                    m.notifyAll();
                }

                break;
            }

            case BLOCKING: {
                final Object m = mutex;

                if (!UNSAFE.compareAndSwapObject(this, MESSAGE_OFFSET, null, msg)) {
                    synchronized (m) {
                        while (!UNSAFE.compareAndSwapObject(this, MESSAGE_OFFSET, null, msg)) {

                            m.wait();

                        }
                    }
                }

                synchronized (m) {
                    m.notifyAll();
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
        // check the message first
        Object msg;

        msg = UNSAFE.getObjectVolatile(this, MESSAGE_OFFSET);
        if (msg != null) {
            messageCache = msg;
            return MESSAGE_RECEIVED_SEQUENCE;
        }

        // continue with the buffer and the message again
        long cs = UNSAFE.getLong(this, CONSUMER_SEQUENCE_OFFSET); // this thread owns the value,
        // so, no any membars required to read

        cs++;

        final long address = stateAddress(cs);

        final int[] states = entryStates;

        switch (waitingStaregy) {
            case BUSY_SPINNING: {
                while (UNSAFE.getIntVolatile(states, address) == 0) {

                    Utils.onSpinWait();

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
                while (UNSAFE.getIntVolatile(states, address) == 0) {

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
                int state = 0;
                long spins = 0;
                long yields = 0;

                _endOfWaiting:
                while (UNSAFE.getIntVolatile(states, address) == 0) {
                    switch (state) {
                        case 0: // initial state
                            state = 1;
                            spins++;
                            break;

                        case 1: // spinning
                            Utils.onSpinWait();
                            if (++spins > maxSpins) {
                                state = 2;
                            }
                            break;

                        case 2: // yielding
                            if (++yields > maxYields) {
                                state = 3;
                            } else {
                                Thread.yield();
                            }
                            break;

                        case 3: // wait with the mutex
                            final Object m = mutex;

                            synchronized (m) {
                                while (true) {
                                    if (UNSAFE.getIntVolatile(states, address) == 0) {

                                        msg = UNSAFE.getObjectVolatile(this, MESSAGE_OFFSET);
                                        if (msg != null) {
                                            messageCache = msg;
                                            return MESSAGE_RECEIVED_SEQUENCE;
                                        }
                                        m.wait();
                                    } else {
                                        break;
                                    }
                                }
                                break _endOfWaiting;
                            }
                        default:
                            throw new IllegalStateException();
                    }

                    // we are here while spinning and yielding

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

            case BLOCKING: {
                final Object m = mutex;

                synchronized (m) {
                    while (true) {
                        if (UNSAFE.getIntVolatile(states, address) == 0) {

                            msg = UNSAFE.getObjectVolatile(this, MESSAGE_OFFSET);
                            if (msg != null) {
                                messageCache = msg;
                                return MESSAGE_RECEIVED_SEQUENCE;
                            }
                            m.wait();
                        } else {
                            break;
                        }
                    }
                }

                break;
            }

            default:
                throw new IllegalStateException();
        }

        return cs;
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
                    final Object m = mutex;

                    synchronized (m) {
                        m.notifyAll();
                    }
                    break;

                default:
                    throw new IllegalStateException();
            }
            return;
        }

        final long address = stateAddress(sequence);
        UNSAFE.putOrderedInt(entryStates, address, 0);

        UNSAFE.putOrderedLong(this, CONSUMER_SEQUENCE_OFFSET, sequence);
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
        final int address = (int) entryAddress(sequence);
        final E result = (E) UNSAFE.getObjectVolatile(entries, entryAddress(sequence));
        UNSAFE.putObjectVolatile(entries, address, null);
        return result;
    }

    /**
     * Sets an entry to the position identified by the sequence in the Ring Buffer.
     *
     * @param sequence identifier of the entry's position
     * @param entry    to be set
     */
    public void setEntry(final long sequence, final E entry) {
        final int address = (int) entryAddress(sequence);
        UNSAFE.putObjectVolatile(entries, address, entry);
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
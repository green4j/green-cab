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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicReference;

public class CabPlayground {

    private final Cab<String, String> cab;
    private final Producer[] producers;
    private final Sender[] senders;
    private final Consumer consumer;

    public CabPlayground(final int bufferSize, final int producerCount, final int senderCount) {

        System.out.println("This playground supports the following commands:");
        System.out.println();
        System.out.println("\t p<N> ENTRY   - Put an entry to the buffer for the consumer by");
        System.out.println("\t                producer #N. For example:");
        System.out.println();
        System.out.println("\t                   p0 Hi");
        System.out.println();
        System.out.println("\t s<N> MESSAGE - Send a message to the consumer by");
        System.out.println("\t                sender #N. For example:");
        System.out.println();
        System.out.println("\t                   s0 My letter");
        System.out.println();
        System.out.println("\t bye          - Finish the game");
        System.out.println();

        cab = new CabBackingOff<>(bufferSize, 100, 1000);

        producers = new Producer[producerCount];
        for (int i = 0; i < producers.length; i++) {
            producers[i] = new Producer(i, cab);
        }

        senders = new Sender[senderCount];
        for (int i = 0; i < senders.length; i++) {
            senders[i] = new Sender(i, cab);
        }

        consumer = new Consumer(cab);

        for (final Producer producer : producers) {
            producer.start();
        }

        for (final Sender sender : senders) {
            sender.start();
        }

        consumer.start();
    }

    public void play() throws Exception {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            _bye:
            while ((line = in.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty()) {
                    continue;
                }

                if ("bye".equals(line)) {
                    break _bye;
                }

                final int wsIdx = line.indexOf(' ');
                int num = -1;
                String text = null;
                if (wsIdx > 1) {
                    try {
                        num = Integer.parseInt(line.substring(1, wsIdx));
                    } catch (final Exception e) {
                        num = -1;
                    }
                    text = line.substring(wsIdx + 1).trim();
                }

                switch (line.charAt(0)) {
                    case 'p':
                        if (num == -1 || text == null) {
                            System.out.println("Cannot recognize the instruction for a producer: " + line);
                            break;
                        }
                        if (num >= producers.length) {
                            System.out.println("Only " + producers.length + "(0-" + (producers.length - 1) +
                                    ") producers exist.");
                            break;
                        }
                        producers[num].produce(text);
                        break;
                    case 's':
                        if (num == -1 || text == null) {
                            System.out.println("Cannot recognize the instruction for a sender: " + line);
                            break;
                        }
                        if (num >= senders.length) {
                            System.out.println("Only " + senders.length + "(0-" + (senders.length - 1) +
                                    ") senders exist.");
                            break;
                        }
                        senders[num].send(text);
                        break;
                    default:
                        System.out.println("Unknown command: " + line);
                }
            }
        }

        for (final Producer producer : producers) {
            producer.interrupt();
        }

        for (final Sender sender : senders) {
            sender.interrupt();
        }

        consumer.interrupt();

        for (final Producer producer : producers) {
            producer.join();
        }

        for (final Sender sender : senders) {
            sender.join();
        }

        consumer.join();

        System.out.println("Bye-bye!");
    }

    class Producer extends Thread {
        private final AtomicReference<String> entryToProduce = new AtomicReference<>(null);
        private final Cab<String, String> cab;

        Producer(final int idx, final Cab<String, String> cab) {
            super(Producer.class.getSimpleName() + "#" + idx);
            this.cab = cab;
        }

        public void run() {
            System.out.println(getName() + " started");

            try {
                String entry;
                while (true) {
                    if ((entry = entryToProduce.get()) == null) {
                        synchronized (this) {
                            while ((entry = entryToProduce.get()) == null) {
                                wait();
                            }
                        }
                    }
                    final long sequence = cab.producerNext();
                    cab.setEntry(sequence, entry);
                    cab.producerCommit(sequence);
                    System.out.println(getName() + " has put new entry: " + entry);

                    entryToProduce.set(null);
                }
            } catch (final ConsumerInterruptedException | InterruptedException ignore) {
            }

            System.out.println(getName() + " finished");
        }

        void produce(final String entry) {
            entryToProduce.set(entry);
            synchronized (this) {
                notifyAll();
            }
        }
    }

    class Sender extends Thread {
        private final AtomicReference<String> messageToSend = new AtomicReference<>(null);
        private final Cab<String, String> cab;

        Sender(final int idx, final Cab<String, String> cab) {
            super(Sender.class.getSimpleName() + "#" + idx);
            this.cab = cab;
        }

        public void run() {
            System.out.println(getName() + " started");

            try {
                String message;
                while (true) {
                    if ((message = messageToSend.get()) == null) {
                        synchronized (this) {
                            while ((message = messageToSend.get()) == null) {
                                wait();
                            }
                        }
                    }
                    cab.send(message);
                    System.out.println(getName() + " has sent new message: " + message);

                    messageToSend.set(null);
                }
            } catch (final ConsumerInterruptedException | InterruptedException ignore) {
            }

            System.out.println(getName() + " finished");
        }

        void send(final String entry) {
            messageToSend.set(entry);
            synchronized (this) {
                notifyAll();
            }
        }
    }

    class Consumer extends Thread {
        private final Cab<String, String> cab;

        Consumer(final Cab<String, String> cab) {
            super(Consumer.class.getSimpleName());
            this.cab = cab;
        }

        public void run() {
            System.out.println(getName() + " started");

            try {
                while (true) {
                    final long sequence = cab.consumerNext();
                    if (sequence == Cab.MESSAGE_RECEIVED_SEQUENCE) {
                        System.out.println(getName() + " has received new message: " + cab.getMessage());
                    } else {
                        System.out.println(getName() + " has received new entry: " + cab.getEntry(sequence));
                    }
                    cab.consumerCommit(sequence);
                }
            } catch (final InterruptedException ignore) {
            }

            System.out.println(getName() + " finished");
        }
    }

    public static void main(final String[] args) throws Exception {
        new CabPlayground(5, 2, 2).play();
    }
}

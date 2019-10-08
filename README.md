# Green Cab

GC-free (green) low latency structure which combines a CSP-style Channel And a ring Buffer (CAB).

This structure aims to be a building block of concurrent data processing applications.

## How to build

Just run `gradlew` to build the library. But prefer to just drop the code into your own project and modify according to your own needs; don't forget about unit tests.

## Usage

The pattern of usage is the following: a number of data producing threads put their data entries into the Ring Buffer, some
controlling threads send their commands as messages to the Channel and ONLY ONE single worker thread (the consumer) consumes
all incoming data entries and messages and processes them one by one. This makes the flow of entries and messages linearized.
Such technique may be very useful, for example, to implement an event loop in a thread of the consumer.

For example:

An entry producer:
```java
    Cab cab = ...

    long sequence;
    try {
        sequence = cab.producerNext();
    } catch (InterruptedException e) {
        // happens if the current thread was interrupted
    }

    Object entry = cab.getEntry(sequence);

    // ... modify the entry ...

    cab.producerCommit(sequence);
```

A message sender:

```java
    Cab cab = ...

    try {
        cab.send(message);
    } catch (InterruptedException e) {
        // happens if the current thread was interrupted
    }
```

A consumer:

```java
    Cab cab = ...

    long sequence;
    try {
        sequence = cab.consumerNext();
    } catch (InterruptedException e) {
        // happens if the current thread was interrupted
    }

    if (sequence == Cab.MESSAGE_RECEIVED_SEQUENCE) { // a message can be read from the Channel
        Object message = cab.getMessage();

        // ... process the message ...

    } else { // an entry can be read from the Ring Buffer
        Object entry = cab.getEntry(sequence);

        // ... process the entry ...

    }

    cab.consumerCommit(sequence);
```

## Performance

Some synthetic tests for JMH can be found in the [jmh](https://github.com/anatolygudkov/green-cab/tree/master/jmh/src/main/java/org/green/jmh/cab) folder.

The result of the throughput benchmarking is the following:

```
Benchmark                                                    Mode  Cnt         Score         Error  Units
EntryProducerBenchmark.oneEntryProducerWithCabBackingOff    thrpt    9  12897793.460 ±  437701.321  ops/s
EntryProducerBenchmark.oneEntryProducerWithCabBlocking      thrpt    9   5883291.611 ±  577068.318  ops/s
EntryProducerBenchmark.oneEntryProducerWithCabYielding      thrpt    9  12483694.703 ± 1355017.878  ops/s
EntryProducerBenchmark.twoEntryProducersWithCabBackingOff   thrpt    9   6887992.941 ±  511990.970  ops/s
EntryProducerBenchmark.twoEntryProducersWithCabBlocking     thrpt    9   6966526.063 ±  446328.856  ops/s
EntryProducerBenchmark.twoEntryProducersWithCabYielding     thrpt    9  21944251.682 ±  218787.736  ops/s
MessageSenderBenchmark.oneMessageSenderWithCabBlocking      thrpt    9    228201.934 ±    3017.170  ops/s
MessageSenderBenchmark.oneMessageSenderWithCabBlockingOff   thrpt    9   4398047.582 ±  276940.837  ops/s
MessageSenderBenchmark.oneMessageSenderWithCabYielding      thrpt    9   3108094.687 ±  121039.393  ops/s
MessageSenderBenchmark.twoMessageSendersWithCabBlocking     thrpt    9    137979.280 ±    2860.427  ops/s
MessageSenderBenchmark.twoMessageSendersWithCabBlockingOff  thrpt    9   3687752.121 ±  336264.454  ops/s
MessageSenderBenchmark.twoMessageSendersWithCabYielding     thrpt    9   3026137.784 ±  162079.191  ops/s
```
The tests were made on a laptop with:
```
Intel Core i7-8750H CPU @ 2.20GHz + DDR4 16GiB @ 2667MHz
Linux 5.0.0-27-generic #28-Ubuntu SMP Tue Aug 20 19:53:07 UTC 2019 x86_64 x86_64 x86_64 GNU/Linux
JMH version: 1.21
VM version: JDK 1.8.0_161, Java HotSpot(TM) 64-Bit Server VM, 25.161-b12
VM options: -Xmx3072m -Xms3072m -Dfile.encoding=UTF-8 -Duser.country=US -Duser.language=en -Duser.variant
```

## License
The code is available under the terms of the [MIT License](http://opensource.org/licenses/MIT).

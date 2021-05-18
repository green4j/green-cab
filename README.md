# Green Cab

GC-free (green) low latency structure which combines a CSP-style Channel And a blocking ring Buffer (CAB).

This structure aims to be a building block of concurrent data processing applications. Depending on requirements to CPU usage and/or latency limitations, the structure can be configured to use blocking or lock-free algorithm under the hood.

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
    } catch (ConsumerInterruptedException e) {
        // happens if the consumer was interrupted by consumerInterrupt()
        // and cannot process incoming entries
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
    } catch (ConsumerInterruptedException e) {
        // happens if the consumer was interrupted by consumerInterrupt()
        // and cannot process incoming entries
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
        
        cab.consumerInterrupt(); // notify all that the consumer
        // is not able to process incoming entries anymore
        
        Thread.interrupt(); // re-set the interrupted flag canonically
        
        return; // just return for example,
                // since the consumer just stopped/interrupted and the cab isn't operational anymore
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

Entry processing throughput with one and two producer's threads:

```
Benchmark                                                   Mode  Cnt         Score         Error  Units
EntryProducerBenchmark.oneEntryProducerWithCabBackingOff   thrpt    9  11490914.693 ± 2650697.553  ops/s
EntryProducerBenchmark.oneEntryProducerWithCabBlocking     thrpt    9   6341016.539 ±  329880.081  ops/s
EntryProducerBenchmark.oneEntryProducerWithCabYielding     thrpt    9  11385864.355 ± 1145172.281  ops/s
EntryProducerBenchmark.twoEntryProducersWithCabBackingOff  thrpt    9   5402727.022 ±  274157.841  ops/s
EntryProducerBenchmark.twoEntryProducersWithCabBlocking    thrpt    9   5049559.367 ±  660839.674  ops/s
EntryProducerBenchmark.twoEntryProducersWithCabYielding    thrpt    9  21733518.532 ±  967500.637  ops/s
```

Message sending throughput with one and two sender's threads:

```
Benchmark                                                   Mode  Cnt        Score        Error  Units
MessageSenderBenchmark.oneMessageSenderWithCabBackingOff   thrpt    9  3951946.807 ±  87624.271  ops/s
MessageSenderBenchmark.oneMessageSenderWithCabBlocking     thrpt    9   226056.848 ±   4765.628  ops/s
MessageSenderBenchmark.oneMessageSenderWithCabYielding     thrpt    9  2642117.388 ± 259434.324  ops/s
MessageSenderBenchmark.twoMessageSendersWithCabBackingOff  thrpt    9  2880319.410 ± 122405.335  ops/s
MessageSenderBenchmark.twoMessageSendersWithCabBlocking    thrpt    9   130180.318 ±   3236.460  ops/s
MessageSenderBenchmark.twoMessageSendersWithCabYielding    thrpt    9  2449810.822 ± 204604.388  ops/s
```

The tests were done on a laptop with:
```
Intel Core i7-8750H CPU @ 2.20GHz + DDR4 16GiB @ 2667MHz
Linux 5.0.0-27-generic #28-Ubuntu SMP Tue Aug 20 19:53:07 UTC 2019 x86_64 x86_64 x86_64 GNU/Linux
# JMH version: 1.31
# VM version: JDK 11.0.11, OpenJDK 64-Bit Server VM, 11.0.11+9
# VM options: -Xmx3072m -Xms3072m -Dfile.encoding=UTF-8 -Duser.country=US -Duser.language=en -Duser.variant
```

## License

The code is available under the terms of the [MIT License](http://opensource.org/licenses/MIT).

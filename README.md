# Green Cab

GC-free (green) low latency structure which combines a CSP-style Channel And a ring Buffer (CAB).

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
EntryProducerBenchmark.oneEntryProducerWithCabBackingOff   thrpt    9  12237886.159 ±  777387.545  ops/s
EntryProducerBenchmark.oneEntryProducerWithCabBlocking     thrpt    9   5893786.822 ±  504748.345  ops/s
EntryProducerBenchmark.oneEntryProducerWithCabYielding     thrpt    9  12403693.354 ± 1184266.375  ops/s
EntryProducerBenchmark.twoEntryProducersWithCabBackingOff  thrpt    9   8540494.508 ±  560519.508  ops/s
EntryProducerBenchmark.twoEntryProducersWithCabBlocking    thrpt    9   7328716.085 ±  406898.987  ops/s
EntryProducerBenchmark.twoEntryProducersWithCabYielding    thrpt    9  22296090.484 ± 1341923.354  ops/s
```

Message sending throughput with one and two sender's threads:

```
Benchmark                                                    Mode  Cnt        Score        Error  Units
MessageSenderBenchmark.oneMessageSenderWithCabBlocking      thrpt    9   223982.624 ±   8336.329  ops/s
MessageSenderBenchmark.oneMessageSenderWithCabBackingOff    thrpt    9  4383103.475 ± 258791.607  ops/s
MessageSenderBenchmark.oneMessageSenderWithCabYielding      thrpt    9  3036892.261 ±  78552.003  ops/s
MessageSenderBenchmark.twoMessageSendersWithCabBlocking     thrpt    9   136337.447 ±   1713.674  ops/s
MessageSenderBenchmark.twoMessageSendersWithCabBackingOff   thrpt    9  3713929.483 ± 334039.100  ops/s
MessageSenderBenchmark.twoMessageSendersWithCabYielding     thrpt    9  3084831.713 ±  53135.594  ops/s
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

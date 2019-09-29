# green-cab

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

## License
The code is available under the terms of the [MIT License](http://opensource.org/licenses/MIT).

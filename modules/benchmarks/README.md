# atmosphere-benchmarks (Tier 1)

Pre-announcement confidence benchmarks for Atmosphere 4.0.x. This module is
**opt-in**: the default `./mvnw install` build does not include it. Activate
with the `perf` Maven profile.

## Build

```bash
./mvnw -Pperf -pl modules/benchmarks -am install -DskipTests
```

## Run JMH micro-benchmarks

```bash
# All benchmarks
java -jar modules/benchmarks/target/benchmarks.jar -rf json -rff target/jmh-tier1.json

# Single benchmark class
java -jar modules/benchmarks/target/benchmarks.jar CheckpointStoreBenchmark

# Single method, custom forks/iterations
java -jar modules/benchmarks/target/benchmarks.jar \
    CheckpointStoreBenchmark.saveHot -f 1 -wi 3 -i 5
```

Or use the helper script: `scripts/benchmarks/run-jmh.sh`.

## Coverage

| Tier 1 item | Class | Status |
|---|---|---|
| CheckpointStore at varying snapshot counts | `CheckpointStoreBenchmark` | Implemented |
| AgentRuntimeResolver lookup | `AgentRuntimeResolverBenchmark` | Implemented |
| AiInterceptor chain traversal | `AiInterceptorChainBenchmark` | Implemented |
| Broadcaster dispatch per subscriber | `BroadcasterDispatchBenchmark` | Implemented |
| Coordinator fan-out latency | `CoordinatorFanOutBenchmark` | Implemented |
| Streaming load test (wAsync) | `StreamingLoadTest` | Implemented |

## Streaming Load Test

The `StreamingLoadTest` is a standalone load test (not JMH) that uses the
wAsync Atmosphere client to open many concurrent WebSocket connections and
measure latency/throughput.

### Prerequisites

Start an Atmosphere server externally (e.g. the Spring Boot chat sample):

```bash
./mvnw -pl samples/spring-boot-chat spring-boot:run
```

### Run

```bash
# Using the helper script
scripts/benchmarks/run-streaming-load.sh --clients 100 --messages 10

# Or directly
java -cp modules/benchmarks/target/benchmarks.jar \
    org.atmosphere.benchmarks.load.StreamingLoadTest \
    --url ws://localhost:8080/atmosphere/chat \
    --clients 100 \
    --messages 10
```

The test reports: clients connected, total messages, p50/p95/p99 latency,
messages/sec throughput, and error count.

See the Tier 1 plan in the PR description for the full design.

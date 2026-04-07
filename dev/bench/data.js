window.BENCHMARK_DATA = {
  "lastUpdate": 1775521974902,
  "repoUrl": "https://github.com/Atmosphere/atmosphere",
  "entries": {
    "Atmosphere JMH Benchmarks": [
      {
        "commit": {
          "author": {
            "name": "jfarcand",
            "username": "jfarcand",
            "email": "jfarcand@apache.org"
          },
          "committer": {
            "name": "jfarcand",
            "username": "jfarcand",
            "email": "jfarcand@apache.org"
          },
          "id": "3ccc7a009a30459d1facdb6a353d98b534c088fc",
          "message": "fix(benchmarks): increase timeouts, reduce JMH iterations for CI budget",
          "timestamp": "2026-04-07T00:17:02Z",
          "url": "https://github.com/Atmosphere/atmosphere/commit/3ccc7a009a30459d1facdb6a353d98b534c088fc"
        },
        "date": 1775521974476,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 295079.9767879091,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 303806.3422948028,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 293064.59623144934,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 19603.36317740803,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 18654.53496820714,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.777999565845139,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8069873121477638,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.6759578755161955,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.60883461429399,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.483120564467075,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8569154236247241,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.861964232246526,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.058607178192882,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.976519873687117,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 26.60423471261113,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 57.900605917084164,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 37.867609849084104,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 5.8993666136908915,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 49.054120020245556,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 61.46842714214728,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 89.36833636044325,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 31.543747980873647,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 34.30810140033372,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 34.93983644422206,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 35.52643026900256,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 5.606074994809336,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 77.07285596707806,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2296.8030114766634,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      }
    ]
  }
}
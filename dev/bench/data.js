window.BENCHMARK_DATA = {
  "lastUpdate": 1776186266002,
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
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "185f3e22cb8b74e62542e0256d7542178ec49660",
          "message": "fix(benchmarks): increase JMH timeout to 60 min for CI runner variance",
          "timestamp": "2026-04-06T21:49:06-04:00",
          "tree_id": "de62ced4938c844e908d62f599b6338763bf4f6b",
          "url": "https://github.com/Atmosphere/atmosphere/commit/185f3e22cb8b74e62542e0256d7542178ec49660"
        },
        "date": 1775526783227,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 306742.1907494289,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 232691.56076776108,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 221264.4068577231,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 17844.35731294661,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 17934.339049418155,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7821624945891412,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7764245678457635,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5487097178004046,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.306291859549553,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.329829547052928,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8491957070519351,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.857120409758412,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.057740083690082,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.89437021321068,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.49589227165156,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 35.65617188844227,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 35.768520498602676,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 55.89532500980558,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 48.62207332096568,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.66037867503749,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 87.50675843416336,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 32.78738157592779,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 37.02146129768567,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 38.12738780387246,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 38.39579752286095,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 5.706805938074364,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 78.26411599040371,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2200.6284743777446,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "d6b2f28212321282fc66f11aed11adc28cba1505",
          "message": "fix(benchmarks): bundle servlet-api in uber JAR for Broadcaster benchmark",
          "timestamp": "2026-04-07T12:55:08-04:00",
          "tree_id": "10e99354bf2c5856c45827651cd4c060f5ac9ae6",
          "url": "https://github.com/Atmosphere/atmosphere/commit/d6b2f28212321282fc66f11aed11adc28cba1505"
        },
        "date": 1775581266232,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 14834942.795276418,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 13229977.574477708,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 3248658.9227282316,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 313035.6560850444,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 298935.9173228187,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 288725.56627548527,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 285123.73415749444,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.624757375226531,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9487212814184297,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.766582632687359,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8000988731924663,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.548070511560997,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.637937428163585,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.301237990947943,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8549195294248252,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.8955926491069532,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.058933125094928,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.867452546011796,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.51820866950834,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 7.9657945809263495,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.017030577001776,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 8.361257760229767,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 50.57110132898031,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 61.446728336197424,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 87.93077735167869,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 34.73499769362847,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 36.68294562285548,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 38.39618395268686,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.82053330069376,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.003142359860755,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 89.81967319087804,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2370.6076842936077,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "848981e12e99e9043b2972d889a7d1959c9606b7",
          "message": "fix(admin): null-guard AtmosphereAdmin when framework is unavailable\nPrevents NPE in FrameworkController/AgentController/AtmosphereHealth when framework is null (Quarkus fallback)",
          "timestamp": "2026-04-07T14:30:04-04:00",
          "tree_id": "ac01e40aac4513974d92db13b0b327dbcbedbe0a",
          "url": "https://github.com/Atmosphere/atmosphere/commit/848981e12e99e9043b2972d889a7d1959c9606b7"
        },
        "date": 1775588104422,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 12594907.246826872,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 12508866.270303704,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 2241520.921413418,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 300678.82336574927,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 305627.48171077383,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 308048.468984998,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 288993.3037746171,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.7054667530581938,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.8922047959894038,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7061004124968914,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8381831691570278,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.643742521278597,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.320267405043149,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.78806892161492,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8865418668425709,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 4.0072097635961486,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.028541086030194,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 12.16034302667453,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 29.093517729929257,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 10.043254307947501,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.155024685138102,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.5827705318206,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 54.564151493334556,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 67.37942068196422,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 98.4237759659511,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 28.1333422238966,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 30.584909914027566,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 31.5293265255836,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 32.57450218745442,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.183825055825048,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 80.69010820694852,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2567.509916310846,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "a36b6d893e9385a68751e5eddb7534e4a2e57b09",
          "message": "feat(cpr): add JettyHttp3AsyncSupport — native HTTP/3 via Jetty 12 QUIC connector",
          "timestamp": "2026-04-07T15:33:37-04:00",
          "tree_id": "453bbed2b32d9e6e9b9564afa3d30fee4d36b0c8",
          "url": "https://github.com/Atmosphere/atmosphere/commit/a36b6d893e9385a68751e5eddb7534e4a2e57b09"
        },
        "date": 1775590863286,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 14754928.47792373,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 13555374.974942327,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 2779378.8132515675,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 324434.3396394674,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 303009.3304332123,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 307800.9959386086,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 304357.8007860729,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6262900666773213,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9632359829407796,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7655594633626949,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8022895349012584,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5472047706943517,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.353176030626956,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.3616780148645,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.852189815325273,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.869549739083608,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.088192663627143,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.873164669294965,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.502220835244263,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.08107297255068,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 6.8451797582632565,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.509208650920946,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 49.06589506024468,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.00126755044793,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 93.21197866977822,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 33.613786085626714,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 37.12121191513993,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 37.94830583554625,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 40.535793024014005,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.142499227061927,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 87.33973933055627,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2380.27297700238,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "911516fbc9ca24a6217f6f7cf42e2addcc11ae0b",
          "message": "fix(cpr): use keytool for HTTP/3 self-signed cert, add jetty-http3 to embedded sample",
          "timestamp": "2026-04-07T15:45:48-04:00",
          "tree_id": "85af24152caef42596e668752a8c8c0a1e8c5ad5",
          "url": "https://github.com/Atmosphere/atmosphere/commit/911516fbc9ca24a6217f6f7cf42e2addcc11ae0b"
        },
        "date": 1775591912782,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 15658099.84949295,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 15367718.325217364,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 3380024.6481902855,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 418712.66368415445,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 301811.4101319395,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 300690.2954976559,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 290121.20485465846,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6250963511704564,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9510443361839883,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7692315708541783,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7995685933545653,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5465507331397,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.192148011156855,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.28959199747681,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8519202078616491,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.866183446477155,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.101507139048105,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.86150528692825,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.541476846450294,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.013914867150973,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.635583354464025,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 6.990873031743424,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 50.03685564079628,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.808797910242816,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 89.0435278971057,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 31.337951164058993,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 33.99868816848712,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 34.95365045674051,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 35.577281579683365,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.150204583531864,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 88.5015190979397,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2249.6022994011973,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "a133bfdc0be17126a812da16744585d8c9127ecf",
          "message": "refactor(webtransport): align Reactor Netty sidecar to AsyncSupport pattern",
          "timestamp": "2026-04-07T16:02:57-04:00",
          "tree_id": "4fe4e9b4d83d69f456951f4f5474f840e736fba0",
          "url": "https://github.com/Atmosphere/atmosphere/commit/a133bfdc0be17126a812da16744585d8c9127ecf"
        },
        "date": 1775592625978,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 15201554.153199924,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 9516357.330937823,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 3076131.0075854664,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 343122.7417237601,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 300728.80488387536,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 299563.4978360921,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 278810.0734941645,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.7056093703956555,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.8925505752072049,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7071026484886797,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8386860819948623,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.6571082683494276,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.331043200499872,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.89947709005725,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8763154791345423,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 4.005981333777206,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.0629085058055265,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 12.163111082653323,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 29.109109767225984,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.192665909259038,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 9.122792555780421,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.552100014602743,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 55.970927416473764,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 67.93756943160683,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 94.56240271335714,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 28.078383228830194,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 31.194736263884934,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 32.0022738276706,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 33.066354857878345,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.434613792116512,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 81.53198051647652,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2536.7132894514766,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "084b61eddc84caf89fe18986ffe250d3edbcc778",
          "message": "fix(cpr): load HTTP/3 AsyncSupport by name to avoid NoClassDefFoundError in native image",
          "timestamp": "2026-04-07T17:02:01-04:00",
          "tree_id": "22c0cf4b6b3297e0dbb7dfe5dfb6ea737085541d",
          "url": "https://github.com/Atmosphere/atmosphere/commit/084b61eddc84caf89fe18986ffe250d3edbcc778"
        },
        "date": 1775596064029,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 13867418.801531255,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 11818564.149292523,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 3379587.8722951137,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 176965.22161944114,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 296565.43321176985,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 291017.667609835,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 284088.76524381514,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.62600077918511,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.946781644034069,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7642561074382445,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8010990359242964,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5549595619846452,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.255926725458083,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.20109223707736,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8497892307010334,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.8788352584429426,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.049457749140245,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.843035863561637,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.57419299630635,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 7.767349967008344,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.535067351886548,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.033911691491594,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 49.40949933303617,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 59.76229499047156,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 88.69342552897906,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 30.968316382146565,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 33.11834859049514,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 34.295714277262356,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 35.445684627022736,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 5.942827571058291,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 91.05325319381912,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2307.0263840245784,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "633d8fa0cc7fb51f77f2609970c627c9e009f892",
          "message": "fix(cpr): use reflection for Jetty APIs in JettyHttp3AsyncSupport (GraalVM native)",
          "timestamp": "2026-04-07T17:21:36-04:00",
          "tree_id": "81223e230c2202e117a12ed810e3fea568a72192",
          "url": "https://github.com/Atmosphere/atmosphere/commit/633d8fa0cc7fb51f77f2609970c627c9e009f892"
        },
        "date": 1775597197045,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 9605644.212888775,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 8591077.114035944,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 3720949.75565809,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 367545.2515082813,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 335799.4061288698,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 306894.06107725756,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 285319.34488440526,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.578746764559813,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 1.0137174339344555,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.873245149200606,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.887135663743314,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 4.095172018313601,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.00416551337785,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 26.115390992522325,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 1.011037283075772,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.251198874432369,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 5.8452229019610185,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.894206971814159,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 27.77633336837683,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 6.94182039214428,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 6.924613814369683,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.694048542335307,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 52.52173097845216,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 59.95406565496444,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 92.70525033376812,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 25.280900526985942,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 26.394578207675195,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 26.03745293382099,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 32.220066426203594,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.4689673357068616,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 87.52310364145659,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2557.3274821124364,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "7a040aae5d67e7b153f5a9ca658d48ea669d5c78",
          "message": "chore(ci): rename workflows to categorized format (CI/Deploy/Release/Security)",
          "timestamp": "2026-04-07T20:25:32-04:00",
          "tree_id": "58c29615320db053492a85cea30c68e647414ef7",
          "url": "https://github.com/Atmosphere/atmosphere/commit/7a040aae5d67e7b153f5a9ca658d48ea669d5c78"
        },
        "date": 1775608244076,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 12377431.89382521,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 14299449.014859445,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 3029792.4589274917,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 280164.449538932,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 302375.5699212853,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 294550.5484598162,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 296395.13781678607,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.626225281742519,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9529190881400851,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7663975883762072,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8004138478155778,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.567796493135198,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.52821739206793,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.657476076437188,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.856801090685126,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.8975719412672185,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.086729412814761,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.86747902398067,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.634394570304693,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 9.224008237280325,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 7.410365914800419,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.248650712696119,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 49.44941730526781,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.30448131911785,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 91.85406711985816,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 35.42026057720839,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 38.17479470009963,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 38.58583624258167,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 38.22311181744756,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 5.693827602476304,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 92.40873529502386,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2468.3007159277504,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "actions@github.com",
            "name": "GitHub Actions",
            "username": "actions-user"
          },
          "committer": {
            "email": "actions@github.com",
            "name": "GitHub Actions",
            "username": "actions-user"
          },
          "distinct": true,
          "id": "a210ef5b8cbe1cde13eb76fc479ba45da132005e",
          "message": "chore: prepare for next development iteration 4.0.33-SNAPSHOT",
          "timestamp": "2026-04-08T14:58:22Z",
          "tree_id": "3b50296a0c467fd8e73b4d1dac46be939b839a11",
          "url": "https://github.com/Atmosphere/atmosphere/commit/a210ef5b8cbe1cde13eb76fc479ba45da132005e"
        },
        "date": 1775660570668,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 14016017.254751354,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 11585216.490252132,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 3022838.75350097,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 353732.3477595777,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 311656.85506461124,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 292807.9240254186,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 279465.8486690992,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.7071245757430774,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.8852981647429833,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7063498980007102,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8400400235024106,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.673673159366656,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.327914190509174,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 26.381488545682114,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.87827608374384,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 4.005835913871449,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.01171269648665,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 12.16564183051811,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 29.08232060870688,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.859320018709438,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.813182882736267,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 9.715196252518348,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 55.08002757846418,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 66.17832809056208,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 96.1655403142879,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 28.407281138110903,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 30.853919296636594,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 32.03450000904218,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 33.08946213970262,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.261621294692072,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 82.76051036576943,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2458.0390965630127,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "actions@github.com",
            "name": "GitHub Actions",
            "username": "actions-user"
          },
          "committer": {
            "email": "actions@github.com",
            "name": "GitHub Actions",
            "username": "actions-user"
          },
          "distinct": true,
          "id": "edb2a21ea7285c861c451d3909d1766a4fde8656",
          "message": "chore: prepare for next development iteration 4.0.34-SNAPSHOT",
          "timestamp": "2026-04-08T17:08:33Z",
          "tree_id": "6afb4f26e467fc242bce3279f9151231738a1f07",
          "url": "https://github.com/Atmosphere/atmosphere/commit/edb2a21ea7285c861c451d3909d1766a4fde8656"
        },
        "date": 1775668369788,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 10205100.061098257,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 10980489.5919101,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 2987487.37458609,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 207738.6633048535,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 318451.12875155656,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 299799.1723417445,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 299526.0457443401,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.5779922923037814,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 1.0124790795302034,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.867246012538471,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.9426424117025736,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 4.222280655148457,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.085902790131406,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 26.07696195842303,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 1.0121709280486182,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.2507736729921692,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 5.8844119653549845,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.882338933961497,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 27.836186912333915,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 7.169189420262428,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 6.8515611593875105,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.696468638983976,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 51.60411670566189,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.508391440877794,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 97.66825244437042,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 24.963744052569847,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 26.081045421783944,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 25.528283869442603,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 32.57301124699145,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.51394711915441,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 90.99109499848439,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2561.158323679727,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "fcdb2e7f5b9e529e1df24081cefd61fd69baec91",
          "message": "feat(coordinator): extract pluggable RetryPolicy from hardcoded backoff\nSealed RetryPolicy hierarchy (ExponentialBackoff, LinearBackoff, NoRetry) replaces hardcoded backoff in DefaultAgentProxy; backward-compatible via fromMaxRetries()",
          "timestamp": "2026-04-08T15:14:13-04:00",
          "tree_id": "1e92a54f6f1c36ac5c83c4fa943315269caa4ecc",
          "url": "https://github.com/Atmosphere/atmosphere/commit/fcdb2e7f5b9e529e1df24081cefd61fd69baec91"
        },
        "date": 1775675963700,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 16724216.699156389,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 9858702.52590534,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 2827495.779847205,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 177467.15815495898,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 309663.9784188185,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 335622.88682699244,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 328645.3126232891,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6257087011489663,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9536489404754898,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7689234108825952,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8012534997551197,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5459816525487464,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.205115452649904,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.25503490913809,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8507850528766939,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.869491842248706,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.047436309583266,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.858337190860235,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.500234855705504,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 7.387741102906951,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 7.421819832670437,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.936108610570514,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 50.733003364699734,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 58.87848508044569,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 94.47080529083127,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 32.61402568093968,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 34.750731169358566,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 35.36287598129173,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.614992208733966,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 5.977733098211816,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 89.36726094080511,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2456.9698461538446,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "24048c4a83590e654288700d3dcf536699ce8294",
          "message": "fix(samples): use correct A2A endpoint paths in multi-agent startup team",
          "timestamp": "2026-04-08T16:04:25-04:00",
          "tree_id": "0be918aeac5213364ab6e0f83fbe42174fd5f1a2",
          "url": "https://github.com/Atmosphere/atmosphere/commit/24048c4a83590e654288700d3dcf536699ce8294"
        },
        "date": 1775678922617,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 14428389.433974251,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 11698497.159479218,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 3524069.322063225,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 215871.38466780007,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 320540.42675624654,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 293342.75343346054,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 288318.8379310357,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6288357816571604,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9562745722037017,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.766412202614537,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7792232723929244,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5535650758633355,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.559712914611522,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.245853357191205,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8556121677217852,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.8610386044800062,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.06000548462355,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.830374418869303,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.51023183143995,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 7.244238136136498,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 7.584426685028269,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.735438616832364,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 50.84901128292382,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 59.883778134783235,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 89.53485123015965,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 35.007104971933394,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 36.32715969277621,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 37.336056564012836,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 37.204466835117955,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.003146272122417,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 88.841262059974,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2455.046692810457,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "e178c0d411dbbe0d5a3beb84c58b79dae6a83299",
          "message": "fix(test): increase WebSocket suspension wait for JDK 25/26 virtual thread timing",
          "timestamp": "2026-04-08T17:21:39-04:00",
          "tree_id": "05121c29433953c59ac79e7fb645642a3591d101",
          "url": "https://github.com/Atmosphere/atmosphere/commit/e178c0d411dbbe0d5a3beb84c58b79dae6a83299"
        },
        "date": 1775683638849,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 13299756.174754316,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 13172626.438421393,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 3121408.495173417,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 239911.70477008997,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 290801.18286461435,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 289877.7959999082,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 275982.2854701416,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.7063597986753661,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.8906903777444336,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7065367788454081,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.830766402356619,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.6477413157051557,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.324682663326927,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.828838514794132,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8752317867398389,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 4.0054139729161475,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.0158305405245756,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 12.126670907346252,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 29.140340021352774,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 9.364570412145627,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 9.45430105213824,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 9.753505116914683,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 55.218306527148634,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 66.70054714361409,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 95.92607991864595,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 27.1331869454538,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 30.723929494482388,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 30.747041750917315,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.10440407559038,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.299072735539909,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 81.38045310253155,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2489.844961857381,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "544032ff94bce9dc2167e94e92909b05a7891853",
          "message": "feat(coordinator): configurable A2A timeouts, SqliteCheckpointStore, QualityResultEvaluator\nA2aAgentTransport.Timeouts record replaces magic numbers; CO_LOCATED preset for same-host agents. SqliteCheckpointStore persists checkpoints to SQLite. QualityResultEvaluator scores on length/structure/errors.",
          "timestamp": "2026-04-08T18:02:07-04:00",
          "tree_id": "33abe13cfd9dabbebc1bde4b22a9ccf5132f8cca",
          "url": "https://github.com/Atmosphere/atmosphere/commit/544032ff94bce9dc2167e94e92909b05a7891853"
        },
        "date": 1775686324110,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 12721230.713650605,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 12922547.713654155,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 4216580.097062924,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 360738.43972131144,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 306331.9941714867,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 289009.91563496395,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 284153.11666807235,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6245172465585243,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9533118866660292,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7695608799210083,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7939105393421755,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.549958164010615,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.591777526915292,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.22678614949218,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8563143463528315,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.8628982166319403,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.046916104528839,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.858021775794269,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.52949055356369,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.892865870421684,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.332242922219299,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 8.042604707121958,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 50.040321407922505,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 59.86624076889333,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 88.75104392349903,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 35.89887437383715,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 37.57073261295544,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 37.24748926468532,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 37.580872041033864,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.429691015110664,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 89.69769318760295,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2408.326564102564,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "7ab6fa265b348ce4ee05d2c978dc9f47d612b695",
          "message": "feat(coordinator): add LlmResultEvaluator, rename QualityEvaluator to SanityCheck\nLLM judge uses active AgentRuntime; prompt from META-INF/skills/llm-judge/SKILL.md. Hardcoded evaluator renamed to SanityCheckEvaluator.",
          "timestamp": "2026-04-08T19:09:30-04:00",
          "tree_id": "5f42096138379e4d0aa655e969eef9e0e3686e92",
          "url": "https://github.com/Atmosphere/atmosphere/commit/7ab6fa265b348ce4ee05d2c978dc9f47d612b695"
        },
        "date": 1775690141540,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 12562442.828797972,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 9879134.47909126,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 3102204.0813143174,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 139797.95284055985,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 305404.9048403657,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 303233.0851672095,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 288732.2492034566,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.7069855840292169,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.8999164925247358,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7061091336976769,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8447313173188886,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.642616128753243,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.333976042592605,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.907407690246625,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.878525465858823,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 4.004233996385775,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.053382382227298,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 12.237390715687406,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 29.11694026642243,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 9.174993029770254,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 9.424321847341234,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.209947579076942,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 55.83634035974534,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 66.73697120822129,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 94.10573750773592,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 27.90434147474362,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 30.724135387302265,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 32.00226794327474,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.16594001583703,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.084006053451986,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 80.32529142642599,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2495.8111893687696,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "7f51e8c1214d1da84ce89609ff9d997f400983eb",
          "message": "fix(coordinator): remove embedded judge skill, load from classpath or atmosphere-skills\nLlmResultEvaluator searches META-INF/skills/llm-judge/SKILL.md and prompts/llm-judge-skill.md; warns with atmosphere-skills URL if not found",
          "timestamp": "2026-04-08T19:20:05-04:00",
          "tree_id": "f083c76f45e76808008b8e946d187165b9c8324c",
          "url": "https://github.com/Atmosphere/atmosphere/commit/7f51e8c1214d1da84ce89609ff9d997f400983eb"
        },
        "date": 1775690864709,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 14430806.281125328,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 13809102.953090906,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 2728172.2879215176,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 297265.2779568113,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 304108.33306540345,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 312519.6529358507,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 280845.41303671844,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6266406898775717,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9514795248941758,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7731206117496443,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.798394528309718,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5688979692939546,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.343055503382928,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.265812781036548,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8595480208273933,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.8634090601031907,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.049523220725114,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.865362759657659,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.483335430173867,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.42488375317094,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 9.233736395564113,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 6.971500944825645,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 50.902503076307774,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.729393265454576,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 91.16816542641162,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 33.46014397730174,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 36.99568045909762,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 38.24516497407121,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 38.53408108743063,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.234492144314172,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 89.73728735632187,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2432.7354558704465,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "d56642bfe1769a322a8663606c63920a18313019",
          "message": "feat(ai): SkillFileLoader with GitHub fallback + SHA-256 integrity, AgentRuntime.generate()\nPromptLoader.loadSkill() searches classpath -> disk cache -> GitHub with registry.json hash verification. skill: prefix in @Agent/@Coordinator skillFile. CollectingSession + generate() eliminate 4 duplicated sync adapters.",
          "timestamp": "2026-04-08T19:53:18-04:00",
          "tree_id": "92f1418558a9bf3bc09fde5c0d1adbb6c4cbbd7a",
          "url": "https://github.com/Atmosphere/atmosphere/commit/d56642bfe1769a322a8663606c63920a18313019"
        },
        "date": 1775692690207,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 13591624.09274291,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 13067733.79841202,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 2680420.0345381857,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 390119.71221965697,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 310143.7911238334,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 349399.69399116066,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 301422.6239519175,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6251911381495588,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9560930793159819,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7659413624363549,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7977345619943286,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.545270506189754,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.239402321665557,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.205004894377137,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8565817273717097,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.883925509103394,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.039545005203283,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.855884095961741,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.50194073875032,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 7.209871874691742,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 7.175184819084584,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.3785927654292065,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 49.9217410262987,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.86791150768693,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 95.15382445604187,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 32.010719856632335,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 34.22346642409418,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 35.16642142580199,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 35.3512050757655,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.328303880884663,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 93.6510821005224,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2497.6883657522862,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "388f3664259cfe1ed625ac8a7a7796314143823d",
          "message": "feat(samples): migrate all skills to atmosphere-skills registry, remove local prompts",
          "timestamp": "2026-04-08T20:35:08-04:00",
          "tree_id": "ed605c69e8badc33cb007a2f4d683564886fc84c",
          "url": "https://github.com/Atmosphere/atmosphere/commit/388f3664259cfe1ed625ac8a7a7796314143823d"
        },
        "date": 1775695170769,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 14422566.715162488,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 12699253.691786865,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 2185628.55147305,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 359385.48889823724,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 325105.78767993493,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 293874.45735515765,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 275053.96820907947,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.7054945542089639,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.8889416434247943,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7079834497213945,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8603561238859478,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.6723838706913967,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.32322136786926,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.87170802986775,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8753629979987073,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 4.015952212970256,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.009309772542067,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 12.13239118174963,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 29.112868925665015,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 9.294315393732006,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 9.469394834844165,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.480722300471786,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 54.609959475662926,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 66.71454913934986,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 94.67211801272249,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 27.817660134407877,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 30.850107833066385,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 31.901932844851487,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.17170681489216,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.005991902834009,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 82.13337313760458,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2493.0273432835816,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "e5d65cb0846d2f67e0f62e0cdc84d11eabaffe76",
          "message": "fix(coordinator): serialize eval calls to avoid LLM API rate limiting\nSingle-threaded eval executor prevents 5 concurrent Gemini judge calls from hitting the free tier 20 req/min limit",
          "timestamp": "2026-04-08T20:41:37-04:00",
          "tree_id": "4250513a4184026765f6275bf37bf16dfe3e71ea",
          "url": "https://github.com/Atmosphere/atmosphere/commit/e5d65cb0846d2f67e0f62e0cdc84d11eabaffe76"
        },
        "date": 1775695557300,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 15479437.806269832,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 11146792.527139915,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 2769323.9287047423,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 337685.49681857665,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 317825.9989139203,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 302143.95036999934,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 284487.767040097,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6245673752447173,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9549837948881378,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7660805605914179,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8020199901800158,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.55567600470798,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.242300194783446,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.25037090690798,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8537957280632792,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.8730453138554957,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.050383386098691,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.875942727193973,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.53382788599838,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.947309018498531,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 7.8005145396184155,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.6831905674576975,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 49.614358911111395,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 59.844210182379236,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 90.30430065024488,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 30.682991553194682,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 32.94515370412366,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 34.32331500334457,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 35.2004644763589,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 5.814598382431067,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 89.20563934816937,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2388.4832611464976,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "e28e682090c254a34568b2259b6b5b04ce9b6aaf",
          "message": "fix(ai): SkillFileLoader graceful degradation — warn instead of crash when skill unreachable",
          "timestamp": "2026-04-08T22:38:47-04:00",
          "tree_id": "a5c7802ce15b8148199abf4a35c93b3b8132bb29",
          "url": "https://github.com/Atmosphere/atmosphere/commit/e28e682090c254a34568b2259b6b5b04ce9b6aaf"
        },
        "date": 1775702615317,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 13686252.064701641,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 12180908.361990795,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 3526709.269894459,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 203180.2353309222,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 289773.89751092787,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 305997.3078649623,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 275353.4377929665,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.7054394665096453,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.8901632352170611,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7099971121920715,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8333026033608473,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.6705801253000954,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.326986902952344,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.91277728075889,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8755971789617721,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 4.005080934288041,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 5.999307185035835,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 12.155954177983219,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 29.05489686883877,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 9.944250948616572,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.795396218319828,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.582208628738126,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 55.463734698460684,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 67.31877887290624,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 97.23339164163083,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 27.655457025335192,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 31.103635143713785,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 31.309983424513188,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.856736712160576,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.026746551724135,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 81.23070755714626,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2457.238292722812,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "2968d0ba504be7be3e252d96e522b5a1602631ca",
          "message": "fix: remaining P0-P3 review fixes (generate await, executor leak, URL priority, timeout msg)\nP0: await before force-complete in generate(). P1: shared eval executor, remote URL checked first, cert hidden when not running. P2: negative skill cache. P3: per-agent timeout in error messages.",
          "timestamp": "2026-04-09T10:45:46-04:00",
          "tree_id": "ced0c9cd8b5f37083d38fbe4d6d8a296550357fe",
          "url": "https://github.com/Atmosphere/atmosphere/commit/2968d0ba504be7be3e252d96e522b5a1602631ca"
        },
        "date": 1775746445490,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 12097479.367151909,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 13981446.212263828,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 3125188.1358234645,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 265276.05976773385,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 308515.0541947219,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 286183.1102444535,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 296659.19656014565,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6244557971775769,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9574120952003567,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7698884774491748,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7555953753434954,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.547985322527465,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.409986455795975,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.256662758591194,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8584498844662228,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.892048305678165,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.048820138029224,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.930862701342305,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.617384280033946,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.8058790960698,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.506624658319863,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 8.57509924250626,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 49.04962441361912,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.876940911389305,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 90.31524445437925,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 35.86860968682398,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 36.00476147154368,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 38.157636940497135,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 37.86341093643677,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 5.922232884033019,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 87.50601079126201,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2491.6293509933776,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "50839d15ce48e0484bc7ebaff2311f8d446a2c9e",
          "message": "fix: address P0-P3 findings from review windows 2+3 (11 issues)\nP0: Responses API sends tool results on continuation. P1: circuit breaker probe counter increments, WebTransport lifecycle stops sidecar, executor ownership guard. P2: parallelCancellable deduplicates, responseIdCache bounded, resolveByName catches LinkageError. P3: circuit-open events in all paths.",
          "timestamp": "2026-04-09T11:17:23-04:00",
          "tree_id": "2991e68fb2bcb64a86f467cdd56debfa2149ea07",
          "url": "https://github.com/Atmosphere/atmosphere/commit/50839d15ce48e0484bc7ebaff2311f8d446a2c9e"
        },
        "date": 1775748711231,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 13825737.080197396,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 12629207.409469232,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 2834611.0067133238,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 351739.35952683236,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 310968.6325165578,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 292101.1941943135,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 292919.7144981724,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.7061655683162997,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.8926092102970148,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7062681819144839,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8431642220788003,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.6677751906751435,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.38878253174906,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.8611168903681,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8777100076723121,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 4.016052390068443,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.0367069411344545,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 12.13046808471389,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 29.22012474251918,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 9.85000153694454,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 9.599753069043137,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.512260198142564,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 61.692121408753906,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 67.0209692514031,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 94.97816797187136,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 28.440321844869544,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 31.440953676291183,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 31.976514102187153,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.506135360562844,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.033782137797603,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 81.8687845989774,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2541.184758679086,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "b21416490df2a3733d2d1b932273ba6ae0dcd88d",
          "message": "fix: admin lifecycle cleanup + removeBroadcasterListener API\nAdminEventProducer.uninstall() deregisters listeners on stop(). AtmosphereFramework.removeBroadcasterListener() added. Admin SmartLifecycle is now idempotent and reversible.",
          "timestamp": "2026-04-09T11:36:04-04:00",
          "tree_id": "2d394b291a04055fa012e7890899c1fca6f14d82",
          "url": "https://github.com/Atmosphere/atmosphere/commit/b21416490df2a3733d2d1b932273ba6ae0dcd88d"
        },
        "date": 1775749356589,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 14513459.936157258,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 10624993.042716434,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 3508703.0793912453,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 408972.69064238964,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 314759.17386607593,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 302565.6864672148,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 287682.9075459397,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6246921509687758,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9578800328888759,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7697176341022836,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8030250934045695,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5438769545822892,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.837442827105473,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.549166565883365,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8583375016305177,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.872617083688833,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.058047847128596,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.839813157323022,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.504791447667145,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 7.503748707556706,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 11.47643145922729,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 8.35702571075101,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 49.79711214007633,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.45019411155224,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 88.64212921347928,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 35.2233863743113,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 36.08685653561967,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 37.72518941808766,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 37.29548826437624,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.155874815179893,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 89.4377740954879,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2313.8297688751923,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "898d5cfd0f481531d4206d3d8ff2bebe6f11e986",
          "message": "fix(ai): merge dangling Javadoc comment in OpenAiCompatibleClient",
          "timestamp": "2026-04-09T11:48:29-04:00",
          "tree_id": "0699f963c0b05646d9245337444cf334844b8f65",
          "url": "https://github.com/Atmosphere/atmosphere/commit/898d5cfd0f481531d4206d3d8ff2bebe6f11e986"
        },
        "date": 1775750108593,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 16137108.176170172,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 13697080.838765973,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 3128850.3100832063,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 339130.121311337,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 306351.91275463236,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 292088.4228006269,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 284643.32949331513,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6273691268002907,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9642455112840338,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7661657063679056,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8012935875057439,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5441700921341024,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.711803317884149,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.24470048253282,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8567266051884487,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.8638356505990017,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.057445481310854,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.836711375411406,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.532934582958205,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.00641718936925,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 6.891643360577265,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 8.11134323923059,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 48.56343936416297,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.41549210008037,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 89.40164222485072,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 34.38952360371602,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 36.25682556976641,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 37.62094739071269,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 37.80499556102811,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.3391359181409035,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 90.1155909049951,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2345.5519999999988,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "45b1e4961122424d1ebcdc8dad76a44c07a0955a",
          "message": "fix: review findings Windows 4-6 (15 issues) + 6 regression tests\nP1: admin write guard, broadcaster queue-full, executor rejection. P2: checkpoint index dedup, parent chain hydration, Discord reconnect, timestamp validation, content-type case, agent metadata. P3: ThreadLocal cleanup, CI paths.",
          "timestamp": "2026-04-09T13:27:32-04:00",
          "tree_id": "f03f127ac81a6d85fd29d5488b5f1ffb01ab58bd",
          "url": "https://github.com/Atmosphere/atmosphere/commit/45b1e4961122424d1ebcdc8dad76a44c07a0955a"
        },
        "date": 1775756121578,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 6767001.208784886,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 2685418.4162857183,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 442748.58231848665,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 284567.9874112821,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 300780.1983262394,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 297313.2599474989,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 299264.58390842954,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.5783811758034805,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 1.0127147063033506,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8671809933454288,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 2.109728832731847,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 4.317532305988309,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 8.988064244624736,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 26.21855844893827,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 1.014096637492037,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.2505217439797263,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 5.991425238397539,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.8826795009998,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 27.939589444403172,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 7.300405426272216,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 7.182745173165784,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.1230186421403685,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 50.89269767793535,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.993510170837425,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 95.33726363052271,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 24.20215402631693,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 25.659305722137493,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 26.098135221610196,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 34.48860687557225,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.547932898357044,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 88.43445296825217,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2461.874086956521,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "68fdaf598d692c50f63800c3dee11f0eae08ba96",
          "message": "feat(quality): add validation gates for unchecked returns + no-op tests\nArchitectural validation now fails on unchecked offer() and expect(true).toBe(true). Fixed 4 offer() calls (use add() or check return). Replaced 4 no-op specs with test.skip().",
          "timestamp": "2026-04-09T14:28:16-04:00",
          "tree_id": "569c6290f06c5b1552bc1ea1c3748d6774cee48a",
          "url": "https://github.com/Atmosphere/atmosphere/commit/68fdaf598d692c50f63800c3dee11f0eae08ba96"
        },
        "date": 1775759630217,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 11141442.519643001,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 6499755.857012891,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 958014.3013768274,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 299072.51228577323,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 307117.0157848515,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 301755.1701024583,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 287215.31224680966,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.625393015685693,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.966149101152427,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7689567134735231,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7678529773327323,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.548109201891829,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.601460735030741,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.24306977484026,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8558312369809903,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.876384358281495,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.037172628831983,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.88125886708841,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.663611188306344,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.187040365823721,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 6.880833551159539,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.522813322283909,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 49.9936728550866,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.922528100667115,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 89.6494556975395,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 33.92789168797671,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 36.93204046140226,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 37.89561890762718,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 38.20266900848922,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.252852680034332,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 88.73245202760748,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2341.8233515198754,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "102e96348cd4383be79a84a97440e24605ced8a3",
          "message": "fix: Window 9 — buffer leak, bidi flag reset, memory cap hard-limit\nP1: byteAccumulator released in channelInactive/exceptionCaught; oversized frame drops release components. bidiStreamActive reset on stream close. P2: SlidingWindowCompaction hard-caps when all messages are system role.",
          "timestamp": "2026-04-09T15:05:53-04:00",
          "tree_id": "b860525d5d7a4a583a22ea1977d0674f5ee2aaeb",
          "url": "https://github.com/Atmosphere/atmosphere/commit/102e96348cd4383be79a84a97440e24605ced8a3"
        },
        "date": 1775762137425,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 12525857.35664387,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 6361445.612596892,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 810740.7019091599,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 305102.3567408262,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 294570.89232815255,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 291249.0278128833,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 277953.74794452667,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.7058400086120015,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.8925438999531412,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7065592904063629,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8314310317491964,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.644636294614211,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.345324002371816,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.825140474578845,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8862431879547349,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 4.013808727090144,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 5.995137319042619,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 12.179423366791019,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 29.05698652345781,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 9.546446052269223,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 9.73687304698535,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 9.402945475289144,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 57.47263969422462,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 67.65198917192319,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 95.39434063399807,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 26.950914179526496,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 30.672842054220705,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 31.550089673629984,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.99395093470884,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.050080553059961,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 83.38679839786386,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2512.02709958159,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "b3cb7ab4f3646a6d6f19221046b6d2f713f52cc2",
          "message": "fix(webtransport): QUIC token handler warning, configurable draft negotiation\nP2: InsecureQuicTokenHandler logged with awareness message. P3: draft setting and response header configurable via system properties; response mirrors client draft header.",
          "timestamp": "2026-04-09T15:20:00-04:00",
          "tree_id": "582eeda065f646fe71f962ea0ac1db7812a00496",
          "url": "https://github.com/Atmosphere/atmosphere/commit/b3cb7ab4f3646a6d6f19221046b6d2f713f52cc2"
        },
        "date": 1775762973608,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 9881316.277058335,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 6040989.413918513,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1288357.7048898113,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 277512.6645331118,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 302768.32204321993,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 312083.1546356894,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 301910.43506656674,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6256521637259974,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.956478985464369,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7773629048534664,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7946579507357996,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.548901831902875,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.685943756475751,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.272213829196602,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8648086165422887,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.8667608980661736,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.058973211328158,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.844630540237098,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.50604072371344,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 6.941756626147293,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 7.381057159334028,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.372850070557071,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 51.13685254800142,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.26734409944013,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 94.87381898598426,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 35.03485921113668,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 37.08520233082749,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 38.348675909825126,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 38.777791224753116,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.057538585280851,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 89.26056908295881,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2500.6301681931736,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "bdd16edeaf7907daaf0eaaea396184cbee54875a",
          "message": "fix(security): auth guards on spring-boot3 admin, ThreadLocal leak, swallowed exceptions",
          "timestamp": "2026-04-09T15:49:27-04:00",
          "tree_id": "2ddafe54541d197e2257bfdf5d6a46d46400669e",
          "url": "https://github.com/Atmosphere/atmosphere/commit/bdd16edeaf7907daaf0eaaea396184cbee54875a"
        },
        "date": 1775764466472,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 10670785.27996651,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 5923928.885919631,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 961172.9120018523,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 284757.78162188176,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 338727.06688674394,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 297287.92154974525,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 284878.22387694876,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.7067931170236498,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.8962463853762713,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7072888945135896,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8412052560047352,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.6444803808404163,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.354067705826173,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.89205046233415,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8865874373338771,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 4.015779736681675,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.003370502773222,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 12.269635254253052,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 29.262967030169268,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 9.274057577136249,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 7.651352404695456,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 8.71276351341794,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 55.801288021986835,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 68.07684878722732,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 97.03441279902552,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 28.73097943107337,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 31.527520168408657,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 32.12496276977197,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 37.850787629351665,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.299495762908801,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 81.58226388624571,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2496.2840664451824,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "actions@github.com",
            "name": "GitHub Actions",
            "username": "actions-user"
          },
          "committer": {
            "email": "actions@github.com",
            "name": "GitHub Actions",
            "username": "actions-user"
          },
          "distinct": true,
          "id": "d0979c2f49415e0b0e0649cfd296199845f8d2c8",
          "message": "chore: prepare for next development iteration 4.0.35-SNAPSHOT",
          "timestamp": "2026-04-10T03:12:28Z",
          "tree_id": "dc273a13c10adf4a7b89cc83a27df60e729af329",
          "url": "https://github.com/Atmosphere/atmosphere/commit/d0979c2f49415e0b0e0649cfd296199845f8d2c8"
        },
        "date": 1775791021243,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 12598724.663502887,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 6039991.204438527,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 929727.4096655283,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 287705.27182470955,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 302595.4481952614,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 295811.0862843714,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 289104.0237806822,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6250670586547322,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9598433058453549,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7687775062731262,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7970610583365696,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.553300324096295,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 8.813971724558709,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.28548435003739,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8538955743402693,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.863225575818962,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.055511538848098,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.833182934944771,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.53955082566539,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.45093887729589,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.69270391974832,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.605964639566667,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 49.575571395675944,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 59.902106482793954,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 90.0710223319832,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 31.461708425701072,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 33.449079143846106,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 34.28698871798663,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.49725916383602,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.758734610835113,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 84.67574555627777,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2390.8746284805084,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "7b6b65ddd621aec10fcff97b47dff513cd0e9677",
          "message": "fix(security): plexus-utils 4.0.3 override, CodeQL XSS sanitizer hints",
          "timestamp": "2026-04-10T11:30:37-04:00",
          "tree_id": "f77d6953ce89026b59527f5f33b04e025cf232d5",
          "url": "https://github.com/Atmosphere/atmosphere/commit/7b6b65ddd621aec10fcff97b47dff513cd0e9677"
        },
        "date": 1775838797705,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 10404540.320023585,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 5954346.963398253,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 986304.1116890289,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 283240.58644325106,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 295202.3847054042,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 286218.10042931745,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 277023.4316533254,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.7058238196074756,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.8987631346464938,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.714081055259399,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8406911737712306,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.6779587508914005,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.472182511799515,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 26.36721794163591,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8833913646993494,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 4.017174664491731,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.042798641615394,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 12.205765912532963,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 29.1156928495173,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.078844635617216,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.168367928837952,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.333869392729871,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 54.544951667723666,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 67.6543574629989,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 96.38842900227685,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 28.026073796735783,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 31.661122640036893,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 32.03554897822213,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.55763721867148,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.20348540713702,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 80.76943486930742,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2501.26110907577,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "actions@github.com",
            "name": "GitHub Actions",
            "username": "actions-user"
          },
          "committer": {
            "email": "actions@github.com",
            "name": "GitHub Actions",
            "username": "actions-user"
          },
          "distinct": true,
          "id": "c1daf329591481005199fcd5856340d0dedaabf3",
          "message": "chore: prepare for next development iteration 4.0.36-SNAPSHOT",
          "timestamp": "2026-04-10T19:26:05Z",
          "tree_id": "6ad99c24c433e22bd0b16580975ec0e8c28f431b",
          "url": "https://github.com/Atmosphere/atmosphere/commit/c1daf329591481005199fcd5856340d0dedaabf3"
        },
        "date": 1775849422884,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 12416708.29132889,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 6828674.559987605,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 966409.8211480104,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 270424.18846526014,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 298049.0200166753,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 311865.4354674294,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 298716.736445253,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6245137190640091,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9604163964636468,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7646201764806403,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7827067951183724,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5541392553110387,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.547054224693055,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.274025409806402,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.855454178576907,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.8686983331203084,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.0750947251227245,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.846296828857811,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.53294558459989,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.75104839893471,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.718826335274999,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.013837507680751,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 50.45708085416357,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.81756111159107,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 92.23713156067784,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 35.14077223489367,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 36.07624497120715,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 37.40848881252181,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.373980288402635,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.4646484318133925,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 91.31958208044894,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2440.5804711616556,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "5fb5aac966f2bc4c37eb2b800fc48b9ee4a62795",
          "message": "feat(ai): unify HITL approval path across runtime bridges (Phase 0)\n\nRoutes LC4j/Spring AI/Koog/Built-in through ToolExecutionHelper.executeWithApproval.",
          "timestamp": "2026-04-10T17:54:20-04:00",
          "tree_id": "f1676cfd1f30f3872fa28839b1161fbda92c8921",
          "url": "https://github.com/Atmosphere/atmosphere/commit/5fb5aac966f2bc4c37eb2b800fc48b9ee4a62795"
        },
        "date": 1775858649978,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 6321085.62812058,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 2949656.847234152,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 451479.7104255006,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 296108.22527895565,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 306480.76756504236,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 301750.7050092989,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 293834.91351010033,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.580128909984571,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 1.0119368079763627,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8669561744981952,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.9443088177953733,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 4.271809481556631,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 8.986849963962284,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 26.07640945777348,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 1.0133420273759333,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.445686229697255,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 5.947234462748111,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.93577260297095,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 27.886466152840285,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.911149602157659,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 6.770913211519748,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.037760127481941,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 51.011295970569584,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.01342522694879,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 90.95891135833982,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 25.470014138140442,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 26.065399184512415,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 26.342878419089413,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 35.419966373038335,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.799491666932216,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 107.6791654572886,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2584.709833190027,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "db7520a0e3b03e6bb2f067fd2d9645d48b430a11",
          "message": "docs: final cross-ref sweep for version drift and phantom samples\nAligns CHANGELOG/AGENTS with SB 4.0.5 / SF 6.2.8 / Quarkus 3.31.3 ground truth and drops references to non-existent samples.",
          "timestamp": "2026-04-10T18:44:31-04:00",
          "tree_id": "42fd559831c99ab06314b83a01d8048a14b9d29f",
          "url": "https://github.com/Atmosphere/atmosphere/commit/db7520a0e3b03e6bb2f067fd2d9645d48b430a11"
        },
        "date": 1775861641122,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 12334135.014011286,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 6595818.4670894025,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1150831.6569192696,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 311571.3079653714,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 292016.0053607332,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 301889.3534473243,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 284301.26664970967,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6249762546997192,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9487880113359118,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7644946629646207,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7958812166043918,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.546996017052445,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.068554135617783,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.266987344268454,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8512393978283198,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.939019385649678,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.047139075240696,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.866611751594654,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.569010340568557,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 9.136565750006016,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.386918782582358,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 11.39246132980328,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 50.982055598094576,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.36280481302419,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 88.96796592098089,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 31.4833782109673,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 33.485169186396206,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 33.90162398156986,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 35.51580154518823,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.293406602217192,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 86.40490560156996,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2391.0921783439494,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "0e01c5e3c7a0812aadd9a771b23b091f2ef085a2",
          "message": "feat(ai): add ExecutionHandle SPI for cooperative cancellation\n\nPhase 2; Spring AI wires Reactor Disposable; others keep completed default.",
          "timestamp": "2026-04-10T19:18:57-04:00",
          "tree_id": "2b1ee40fd3a89cda1176e3ff7c8c38aa97971801",
          "url": "https://github.com/Atmosphere/atmosphere/commit/0e01c5e3c7a0812aadd9a771b23b091f2ef085a2"
        },
        "date": 1775863741439,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 11284915.008257031,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 6398262.953125607,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 945544.8383125666,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 303520.6731891175,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 281171.3271407806,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 281528.7388099429,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 283416.03027876077,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.7053785919846728,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.8894573323962388,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7067504212127304,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8379336117898735,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.669077534553342,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.326739427538046,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.925556823327582,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8810099485849854,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 4.01277175363808,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.009758358628034,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 12.175866165678228,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 29.066755877438908,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 9.42812930677601,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 9.335383595302872,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 9.250979183543011,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 54.89709387960263,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 67.97148248137846,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 93.93055484982916,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 26.688350476737764,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 30.915407490435012,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 31.876567703138374,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.83973785508214,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 5.962721340228018,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 81.24392200254928,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2449.5248378158108,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "27cdc9c69d5a9db7b08d7f412a56c8f2993d25da",
          "message": "feat(ai): add AgentLifecycleListener SPI and fireXxx helpers\n\nPhase 3; listeners attached via context; AbstractAgentRuntime fires around execute.",
          "timestamp": "2026-04-10T19:51:38-04:00",
          "tree_id": "9f1da49bd4fd0a5ec050ea64667a409209c512e8",
          "url": "https://github.com/Atmosphere/atmosphere/commit/27cdc9c69d5a9db7b08d7f412a56c8f2993d25da"
        },
        "date": 1775865405726,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 11902785.772755353,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 6409568.021658999,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1287023.3964147058,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 258017.72872861577,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 298101.30528384895,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 295109.90858451143,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 280780.4421249687,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6251040487356327,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9589393995268467,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7695729520924258,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.79722029744771,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5678846138800746,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.314170593938691,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.243276077594587,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8528713243888587,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.863720703247768,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.044505399907764,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.832440926765699,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.519555694576965,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 10.284684128215773,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 16.238725242579562,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 8.217709476695028,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 49.98851081284172,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.913765178907035,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 88.73596672982656,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 35.158405191502425,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 35.914978152110045,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 38.12519039657709,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.93651444066389,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 5.977480084042265,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 89.25074918023029,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2402.5104384,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "392565fe68e8708c3f48e518dda5a5a65e913842",
          "message": "feat(ai): add StreamingSession.toolCallDelta SPI default sink\n\nPhase 5; default emits keyed metadata so existing consumers observe deltas without new events.",
          "timestamp": "2026-04-10T20:07:41-04:00",
          "tree_id": "001450dcdc283048e74b373832b9128cea5951eb",
          "url": "https://github.com/Atmosphere/atmosphere/commit/392565fe68e8708c3f48e518dda5a5a65e913842"
        },
        "date": 1775866328991,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 10289453.096940292,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 6322628.5536214,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 912431.2579016619,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 292856.0557491959,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 301917.53387089615,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 304941.5795190336,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 288389.81319138204,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.625060199028166,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9563353125133159,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7675924738267937,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.819813174657578,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.555648510912938,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.047612860877924,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.76942004936106,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8699264972126999,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.871300368817948,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.0551689044875046,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.831970570670952,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.50268603839574,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.025837586501964,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 7.587081571224709,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 8.213934066268656,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 50.28868314995511,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 59.86724680870346,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 89.06973041400126,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 31.14180806430174,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 33.63690833534931,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 34.49443222620852,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.177682721732175,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.166594527729512,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 89.28569308198583,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2364.8444822695037,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "c879ae1779233e78c318156d76f890ac780c6baf",
          "message": "feat(ai): EmbeddingRuntime SPI + ToolArgumentValidator + AgentRuntime.models()\n\nPhases 8/10/11; Phase 9 (RetryPolicy) was already in tree.",
          "timestamp": "2026-04-10T20:31:48-04:00",
          "tree_id": "54978aea4066c60d12563af65ae5dd004b9ffb17",
          "url": "https://github.com/Atmosphere/atmosphere/commit/c879ae1779233e78c318156d76f890ac780c6baf"
        },
        "date": 1775867806256,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 12075568.9452539,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 5939034.015959886,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1220289.2053661975,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 312270.25170595053,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 299918.73349883937,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 294948.5132320667,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 287859.561774497,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6267340629101247,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9524430961255769,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7683910493823157,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7634758680195866,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5467553819884015,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.093010916464126,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.215678775898652,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8575136081806473,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.875017598681305,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.076188777985828,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.868460018203765,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.579298763413263,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.580216526328593,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.460780594295203,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.079901042182175,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 49.88310259482834,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.02794213541754,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 87.57005825256272,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 31.247286505259297,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 33.89791303424199,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 34.743939663058164,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.54820156104493,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 5.993697710103875,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 87.08110238323326,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2373.2987551342817,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "a0ebcc6b6a40402da35153ced3a569cc481c59f9",
          "message": "feat(ai): D-1 AgentExecutionResult + D-6 ADK native cancel via AdkEventAdapter\n\ngenerateResult() returns text+usage+duration; ADK wires Disposable+whenDone into ExecutionHandle.",
          "timestamp": "2026-04-10T23:04:44-04:00",
          "tree_id": "b3c4e3613a4c1a6c4ffd5f04d7a8793addb32fa3",
          "url": "https://github.com/Atmosphere/atmosphere/commit/a0ebcc6b6a40402da35153ced3a569cc481c59f9"
        },
        "date": 1775876965561,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 12868108.888516182,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 6458741.611591377,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1040571.264267817,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 276399.5689734049,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 305184.25906190067,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 288498.5076765349,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 282638.8875025981,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6255327588846623,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9590328162552568,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7706478288792412,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.793904640164803,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.549254869752771,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.027473306549078,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.26518356537359,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8525789988968483,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.869519347912204,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.058072870099575,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.880056188217358,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.572743367769665,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 7.914965696152549,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.626419819595716,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 8.295608772155012,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 49.41808324377116,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 59.704414435355865,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 90.27800069121508,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 31.097602938808222,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 33.726745792948044,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 34.54693063541361,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.36044353721399,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.11911741013294,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 85.17443874708648,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2318.613694915254,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "19aa501d817b876f23f5b33f4cd4b5a1b31673dc",
          "message": "fix(ai): close AiPipeline HITL gap on @Agent/@Coordinator/channel paths\n\nPipeline threads ApprovalStrategy into 15-arg context; 14-arg shim deprecated-for-removal.",
          "timestamp": "2026-04-11T09:25:09-04:00",
          "tree_id": "ef5c22e0c0601fb8eddfdb9dccb100081e63e2ab",
          "url": "https://github.com/Atmosphere/atmosphere/commit/19aa501d817b876f23f5b33f4cd4b5a1b31673dc"
        },
        "date": 1775914186845,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 12573339.271615924,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 5051206.02543747,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 833346.3511104429,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 262654.80920148717,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 302118.46982293867,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 302503.2894412139,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 291027.7988509636,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.705897077492486,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.8920405605147789,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.705499520232474,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8404672524083647,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.6741654955696816,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.31388051364589,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.878717165641607,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8790497510732277,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 4.017877494637145,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.003905718240008,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 12.191622839637246,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 29.045072795385654,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 9.679878549192447,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 7.787242021017391,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 9.648228871673174,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 57.141508814595106,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 68.779190737332,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 97.1437168684411,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 27.93302388422396,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 31.000166912128915,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 31.84979145413703,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.480486274842896,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.284249028884091,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 80.96667635224547,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2450.8446068515505,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "6b41f23cfb0c93459a9907b9eed8fc80034942b9",
          "message": "refactor(ai): delete 14-arg AgentExecutionContext shim, thread approvalStrategy explicitly\n\nRemoves legacy back-compat constructor and the 12 @SuppressWarnings it needed.\nEvery caller now passes ApprovalStrategy (null when HITL-exempt) via 15-arg form.",
          "timestamp": "2026-04-11T10:20:42-04:00",
          "tree_id": "fabe4908169b4c86b3309c0e135ad9677767ae68",
          "url": "https://github.com/Atmosphere/atmosphere/commit/6b41f23cfb0c93459a9907b9eed8fc80034942b9"
        },
        "date": 1775917466446,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 12953739.168920727,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 6387997.268595531,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1133628.2974105508,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 280436.596631654,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 305639.8768720569,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 284023.20579783455,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 300137.9814314709,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6284773473053183,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9551246635453247,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7784999234291913,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7949576779152696,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5832806922080445,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.338378393034885,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.29268794830088,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8532631270937299,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.873001803996941,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.039167636877004,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.852325062750728,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.51997014937977,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 15.829955124066666,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.090922512595968,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 8.54977020219961,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 50.66600402428077,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.86253215369979,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 89.87440703152244,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 31.408942927152893,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 33.23409518193527,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 34.369752483228616,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 35.96888147398554,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.300857295694583,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 86.6934669408325,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2384.4408888888893,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "0487febe4db4dce736980b522613e03f4a6528f3",
          "message": "fix(ai): close HITL correctness gaps and wire guardrail/validator/policy across runtimes",
          "timestamp": "2026-04-11T11:55:11-04:00",
          "tree_id": "b85c3bb0587a3e2a8cb591d8fdda65f37f20b11d",
          "url": "https://github.com/Atmosphere/atmosphere/commit/0487febe4db4dce736980b522613e03f4a6528f3"
        },
        "date": 1775923210963,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 13322758.090285609,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 6291583.406946085,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1200495.6905755333,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 319536.67251641146,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 300415.0916698549,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 301537.1652775769,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 286514.03401444375,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.624819247767379,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9571476732397723,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7656741670941178,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7972813804540149,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.542832165135723,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.54503074416948,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.238307066228245,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8501921458372094,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.871575893490314,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.047906173701389,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.838654164712977,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.510612704230212,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.931282006082148,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 7.981256446512444,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.646479510290096,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 49.12200415082301,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 59.58947259695043,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 91.63148133606062,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 33.79517837955259,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 36.099064150663786,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 37.57439603124799,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 37.27098999409481,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.296572337237392,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 91.761447959371,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2358.6336954474095,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "6cba5ffc113bbd0d9a2ad2655d0244022a9bbcbb",
          "message": "revert(ai): split off token telemetry, soft-cancel, and policy wiring pending individual review",
          "timestamp": "2026-04-11T12:15:34-04:00",
          "tree_id": "368c3da3eaab6776f7c8c57e0d5a0a4496e900d1",
          "url": "https://github.com/Atmosphere/atmosphere/commit/6cba5ffc113bbd0d9a2ad2655d0244022a9bbcbb"
        },
        "date": 1775924350768,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 11569342.17854307,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 5068253.411602646,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 973868.3839646518,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 283598.4422389247,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 299422.06955098576,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 300534.62313727546,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 285852.21238809504,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6250067644218921,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9557510509910697,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.766141439420951,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7994459786819565,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5464513279971093,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.152974251976092,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.216848056859728,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.853515391390047,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.863171533931332,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.077189640232696,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.838955393686733,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.558883767934883,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.136365599482717,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.785065366476223,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 6.9432111892041375,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 51.02599897905478,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 59.540690732706615,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 91.12866254608919,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 31.640864028133493,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 33.600090880502286,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 34.4350691294408,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 35.624623143239454,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.071759030361223,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 85.67557353445311,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2300.0309227237944,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "bf8887c70c49d9d68cd1222f17543bc340226cec",
          "message": "feat(ai): add token telemetry, soft-cancel, policy plumbing, and A2A approval round-trip",
          "timestamp": "2026-04-11T12:43:20-04:00",
          "tree_id": "ca26a4b4b7649719438c93486e835414b93cfd31",
          "url": "https://github.com/Atmosphere/atmosphere/commit/bf8887c70c49d9d68cd1222f17543bc340226cec"
        },
        "date": 1775926017363,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 11901754.258165563,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 4634616.613976374,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1157529.302019742,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 230596.41473744434,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 311140.40016407636,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 297784.53047113965,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 293737.95397309336,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.630805283097483,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9741851058251685,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7630122525027843,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.795887057492939,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.551319086783566,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.503379844065334,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.220988627514785,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8528698893748762,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.876606668470528,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.069041286795766,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.891249522520132,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.603088116106154,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.233064474055933,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.112823887776395,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.238996005999791,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 50.135737341288085,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.82231471623629,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 89.11479849928072,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 34.642831128189954,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 35.54314188387067,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 37.56352026491644,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 37.38884457983101,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 5.973284806887624,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 90.0820705316911,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2424.792297253634,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "2cd6c272a4d5e72fc0780578bb5f6808d1c8c4e4",
          "message": "feat(ai): declare TOOL_APPROVAL on every tool-calling runtime and expose configured models",
          "timestamp": "2026-04-11T13:52:19-04:00",
          "tree_id": "1d2add718746cfce47e8a9ecfafb0cabb54b111e",
          "url": "https://github.com/Atmosphere/atmosphere/commit/2cd6c272a4d5e72fc0780578bb5f6808d1c8c4e4"
        },
        "date": 1775930149393,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 11184057.237178529,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 5275188.965532884,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1089130.6945903222,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 257560.3595543499,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 295932.42666414235,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 300149.3840435829,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 280302.3457992203,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.7059047782113849,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9000570312683275,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7064546553185398,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8409491876864477,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.6462497921455834,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.324490075553395,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.823281367068347,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8781016480722315,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 4.040388284826061,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.017181139202399,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 12.172416208316264,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 29.153156592209715,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 10.115105149781245,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 9.248598616293245,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.419279798477773,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 55.31780188247751,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 66.73363341182552,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 97.81406952590112,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 28.11515679342664,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 31.173424109759917,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 31.709224887035308,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.81356183458272,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.118461043494092,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 81.71173821989527,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2508.6923903252678,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "e8adc27a47a693097422aa5206a8fe7bc25f41f1",
          "message": "feat(ai): wire multi-modal ContentPart translation into Spring AI, LC4j, ADK, and Built-in",
          "timestamp": "2026-04-11T15:13:24-04:00",
          "tree_id": "7ed4ff257593160a207381a5dfd131f011b68e06",
          "url": "https://github.com/Atmosphere/atmosphere/commit/e8adc27a47a693097422aa5206a8fe7bc25f41f1"
        },
        "date": 1775935035932,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 11895825.406215921,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 6678686.911807875,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1049018.8939970199,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 194439.63748713327,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 298440.5171506178,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 312356.57579302974,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 289248.8420674993,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.624596699316133,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9685226506692937,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7678174481163781,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8011997324023925,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5459667582516956,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.236382002820807,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.28512312673593,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.851083750694604,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.8732528613235764,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.077448157551277,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.854552086588015,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.503855144560543,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.039687773700473,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.522159545006607,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 8.49080223031581,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 50.586751651472696,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.01179525242853,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 91.08648741736948,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 31.61091678767818,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 33.40973056969456,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 34.09761906478905,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.39985853605346,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 5.904495803744361,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 94.43329871439362,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2362.29801728201,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "3a92220a3da3ddc4b04b4b63cc22d6c66164b8b5",
          "message": "feat(ai): fire onToolCall/onToolResult and toolCallDelta from every runtime bridge",
          "timestamp": "2026-04-11T15:48:20-04:00",
          "tree_id": "3aa27aa4a23dc1b7518a074e1039fe2cfd7389a9",
          "url": "https://github.com/Atmosphere/atmosphere/commit/3a92220a3da3ddc4b04b4b63cc22d6c66164b8b5"
        },
        "date": 1775937116992,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 12517864.329049746,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 6625071.700447722,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 946093.1743910884,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 308604.939925843,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 293663.2651333561,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 289363.0237987672,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 279472.1639819661,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.7087134214851689,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.8906812540552034,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.708482547523627,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8234747917812932,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.6485426316793337,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.331771519233985,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.800065159412174,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8979576288720762,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 4.015825239353096,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.065529961921301,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 12.200408345603867,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 29.107478536855254,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 9.30668737289518,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 9.048649073214909,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.371448254914306,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 55.02871993893783,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 68.20025761828914,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 96.46566481296718,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 26.665624869241444,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 31.08096770605658,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 32.03658603774597,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.9169815751247,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.261436414079576,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 81.22084948274934,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2526.419684033613,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "cdeb9906de8c7129cc2cea6f25a269993d6cd963",
          "message": "feat(ai): wire prompt caching hints into Spring AI, LC4j, ADK, and Built-in\nCacheHint rides AgentExecutionContext.metadata; Spring AI/LC4j/Built-in emit prompt_cache_key on the OpenAI path. ADK's ContextCacheConfig is App-scoped so the per-request hint is logged and ignored.",
          "timestamp": "2026-04-11T16:40:10-04:00",
          "tree_id": "bf1023c7591bc98f91127c6d84e90a5e37ec2426",
          "url": "https://github.com/Atmosphere/atmosphere/commit/cdeb9906de8c7129cc2cea6f25a269993d6cd963"
        },
        "date": 1775940209056,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 11562624.313022485,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 5942959.478141588,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 948927.6269916892,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 298507.30266415776,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 300064.6291645974,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 293796.73109750816,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 278153.0219744506,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.7078680700022844,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9190523337747418,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7072306820701252,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8354350685422842,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.6378744714345,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.342820562292209,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.91540956740062,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8791823125763815,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 4.003662097752112,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.013028375599486,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 12.203295859848495,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 29.094296806913235,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 9.333731386118766,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 9.186850682027357,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 9.520733663102533,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 55.27428951554915,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 67.08296863502339,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 94.46556239466024,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 28.1202737410793,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 30.641178890134455,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 31.4662227912181,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.30246170060301,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.069530207130202,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 80.91109280579423,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2547.8405428329083,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "570fc594920e40b60fb8fffc0a26c5f76a8b3122",
          "message": "feat(ai): add EmbeddingRuntime SPI implementations for Spring AI, LC4j, and Built-in\nSPI was scaffold-only since Phase 8; this adds the three runtimes whose embedding APIs Atmosphere actually depends on, plus an EmbeddingRuntimeResolver and a cross-runtime contract test base. modules/rag is unchanged because both ContextProvider impls already operate at the higher-level retriever abstraction (Spring AI VectorStore, LC4j ContentRetriever) and never call EmbeddingModel directly.",
          "timestamp": "2026-04-11T17:16:33-04:00",
          "tree_id": "6fdd65caacd2baf612d9232b005488c5a8bbedee",
          "url": "https://github.com/Atmosphere/atmosphere/commit/570fc594920e40b60fb8fffc0a26c5f76a8b3122"
        },
        "date": 1775942399450,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 12631840.650873497,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 5824282.072457365,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 941329.605849038,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 302512.97174708813,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 295295.5439376018,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 294498.0880064822,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 278724.59994592244,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6281025110893242,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9640337153876688,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7643509470176694,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8009381090179737,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5844935271340503,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.569184414990362,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.350225749076106,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8529687724908536,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.9083913199619467,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.077925435631724,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.836741260610651,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.537897469793776,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.55112624324407,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 15.117161496083979,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 8.766418164319306,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 50.44426028796416,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.88685838876989,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 91.0953902454681,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 34.95223473534977,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 36.21827716915356,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 37.2656995301821,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 37.561966916974505,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.271729215580025,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 90.39729641359762,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2371.868489344908,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "ae0c2d06adaae996cba253f3e627b6bfee66af7e",
          "message": "feat(ai): thread per-request RetryPolicy through AgentExecutionContext to Built-in client\nRetryPolicy becomes the 19th canonical field on AgentExecutionContext (default RetryPolicy.DEFAULT) and the 12th on ChatCompletionRequest. BuiltInAgentRuntime threads it into OpenAiCompatibleClient.sendWithRetry as a per-request override; framework runtimes (Spring AI, LC4j, ADK, Koog, Embabel) inherit their native retry layers.",
          "timestamp": "2026-04-11T17:41:43-04:00",
          "tree_id": "ba81bd724d563ebc27193f04078f2a637afd533c",
          "url": "https://github.com/Atmosphere/atmosphere/commit/ae0c2d06adaae996cba253f3e627b6bfee66af7e"
        },
        "date": 1775943904492,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 13216561.986499513,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 4951654.523447008,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1190411.9619069158,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 290222.43180151394,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 305015.25392189465,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 287975.0733769007,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 287859.71450520307,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6244332381904361,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.963414339598263,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7675993027427795,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7973668573704806,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.587265687973925,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.46138990037135,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.258815712516498,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8526864288481111,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.86284540941711,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.123892981714306,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.83491306974325,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.50556239790677,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 6.7399549826608505,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 7.998698712670304,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 8.366883164325241,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 50.94912346461869,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 61.79235390861374,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 91.57478891019288,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 35.48894068508042,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 36.11331834131695,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 37.544507862206046,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 38.97733587734866,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.441557276030965,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 86.73783867607911,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2501.2690848585685,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "9cf46183943d345469350ceb242a4aad50b3b69a",
          "message": "fix(ai): restore client-level retry config when context carries default policy\nBuiltInAgentRuntime.buildRequest now skips threading context.retryPolicy() into the request when the field is reference-equal to RetryPolicy.DEFAULT — a caller who never touched the override falls through to the client-builder policy instead of shadowing it. New OpenAiCompatibleClientRetryBehaviorTest spins up a local HTTP 500 server and asserts that RetryPolicy.NONE override caps attempts at 1, absent override uses client-level policy (4 attempts), explicit override wins.",
          "timestamp": "2026-04-11T19:27:33-04:00",
          "tree_id": "d77deac668836e4f576b8059e5fc6b538d7f5857",
          "url": "https://github.com/Atmosphere/atmosphere/commit/9cf46183943d345469350ceb242a4aad50b3b69a"
        },
        "date": 1775950257075,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 11671487.559904391,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 6071646.057917956,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1019138.3750367938,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 282359.72243343067,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 311973.6251452034,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 308757.32536720467,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 297812.2785959703,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6258738359465432,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9513714248895506,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7685846367585037,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7665360820459026,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5497872131788437,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.32980484765045,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.31173438859633,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8556854397861305,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.8604504432120392,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.062610690004956,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.835364162567904,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.520221660945897,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 6.998536573956138,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.203457346914009,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 8.176231499663237,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 59.70879270900357,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.395460273973924,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 90.26342177764968,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 34.71405708895029,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 35.66606498625203,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 37.570498807150926,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 37.02467928995605,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 5.765477445132893,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 87.8502148828206,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2408.169025641025,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "5360d08e70de43dfa442a36b4a9077c0b49a74b6",
          "message": "chore(ai): close out Wave 5/6 review polish items\nEmbeddingRuntimeResolver.resetCache() becomes public with test-only Javadoc so cross-module TCK subclasses can rescan the classpath without module-info hacks. OpenAiCompatibleClient exposes a package-private retryPolicy() accessor so the builder-wiring test drops its reflection hack. AbstractEmbeddingRuntimeContractTest drops a tautological Integer.class assertSame check.",
          "timestamp": "2026-04-11T19:32:12-04:00",
          "tree_id": "57ac8c75b6e5e9e4c69e941333947765458ed622",
          "url": "https://github.com/Atmosphere/atmosphere/commit/5360d08e70de43dfa442a36b4a9077c0b49a74b6"
        },
        "date": 1775955660017,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 14337024.963480271,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 6653037.845889427,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1143501.9933969819,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 285326.05538100045,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 316472.1181587367,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 304277.0222855972,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 288061.79963078693,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.625021196236438,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9656535088438815,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7661508128741397,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7686804240203842,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.549935886691457,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.386793556155652,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.22095240020207,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.859589077447246,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.8674060015834955,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.06813750451716,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.852069506009927,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.599570624584178,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.62057959385207,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.474820786304738,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.255506427066948,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 48.45339843805894,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 61.27743392948749,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 89.90399073151282,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 34.89633835524061,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 36.85242356175234,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 37.49034515166405,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 37.9678060673703,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 5.891175464639885,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 89.38774321546647,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2383.676797458301,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "5b069ba9c485f92a7cef87fcb870e88d17d979cc",
          "message": "docs(ai): update capability matrix and runtime READMEs for Wave 1-6 close-out\nAdds TOOL_APPROVAL, VISION, MULTI_MODAL, PROMPT_CACHING columns to the capability matrix and a new EmbeddingRuntime SPI table in ai/README.md. Adds SK as 7th runtime row. Creates modules/semantic-kernel/README.md. Updates child READMEs (spring-ai, langchain4j, adk, koog, embabel) with new key classes. Fixes stale arg-count references in CacheHint.java and AgentExecutionContext.java Javadoc. atmosphere.github.io docs are maintained in a separate repo and flagged for a follow-up PR there.",
          "timestamp": "2026-04-12T07:32:40-04:00",
          "tree_id": "3440f26f41008c5065e1229fae2d53b18e5fed8c",
          "url": "https://github.com/Atmosphere/atmosphere/commit/5b069ba9c485f92a7cef87fcb870e88d17d979cc"
        },
        "date": 1775993784711,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 13041985.979732955,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 6747687.56628204,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1191806.9170910309,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 312166.379149129,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 297780.75108706276,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 290077.998177991,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 291572.91461988277,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6255222422592395,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9601914512062318,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7654242606220615,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7660037172023255,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5479115410214814,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.699390850356258,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.212416074710173,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8544437352435809,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.8623151882414306,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.074638899875946,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.840056467762757,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.56519170853889,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 7.694908937893604,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.346845379970395,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 8.806119782208711,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 51.2883232891136,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.14978776951554,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 90.7991156251065,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 34.95042192471566,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 37.21048187644734,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 37.80251897062428,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 38.91659922476361,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.098631070853957,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 87.9686467396087,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2387.464126984127,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "b9b1af4aff41fc53685300353f1aa71d1120efc3",
          "message": "fix(ai): thread ToolApprovalPolicy through all 5 runtime bridge call sites\nEvery bridge called the 5-arg executeWithApproval which defaults to annotated() — context.approvalPolicy() was never consumed. Now all 5 bridges (Built-in, Spring AI, LC4j, ADK, Koog) pass the policy through to the 6-arg form. ChatCompletionRequest gains approvalPolicy as 13th canonical field (12-arg shim preserved). Tool-loop reconstruction preserves the policy across rounds.",
          "timestamp": "2026-04-12T13:20:05-04:00",
          "tree_id": "0c3199fac9ff49b9505c5d20a4f6ede34741f82d",
          "url": "https://github.com/Atmosphere/atmosphere/commit/b9b1af4aff41fc53685300353f1aa71d1120efc3"
        },
        "date": 1776014686786,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 12072604.593933135,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 6098726.661454228,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 948195.4511370229,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 297542.67934442725,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 307052.25975491153,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 306168.59886317345,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 289932.53700260335,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.7060034187069233,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9017349869168614,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7064486855168178,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8333922845130355,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.656744433958386,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.323503606772865,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.764623300135426,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8837966754326851,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 4.0167262252985925,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.011537465380944,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 12.210013083148382,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 29.08290200148971,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 9.414354486726678,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 9.159639638381705,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.775642187989206,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 54.90943493015646,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 63.42825703191869,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 95.09554013021108,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 27.66481017363809,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 31.668764974943723,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 32.47605094450365,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 37.38643818985773,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.147364297115934,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 81.70797916496223,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2533.020276559865,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "40d616b6ee0d1867f4d9f29c4c6bf68a5ac21f54",
          "message": "fix(ai): make denyAll a true deny that short-circuits before the approval gate\nDenyAll.requiresApproval() returned true which entered the approval gate — if the strategy returned APPROVED, the tool still ran. Now executeWithApproval detects DenyAll and returns a cancelled-by-policy JSON immediately, never consulting the strategy. Matches the Javadoc promise: \"no invocation ever runs.\"",
          "timestamp": "2026-04-12T13:24:20-04:00",
          "tree_id": "79a1a04c7442b34ddbe8e5d5ef784f2565d3aa42",
          "url": "https://github.com/Atmosphere/atmosphere/commit/40d616b6ee0d1867f4d9f29c4c6bf68a5ac21f54"
        },
        "date": 1776014928979,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 11381972.216035746,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 7308855.489448793,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1027939.5706943242,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 283467.5793206833,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 294436.4364141379,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 287470.0391349688,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 280444.01546481857,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.626199882110862,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9561563952661527,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7680513642542484,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7983654210269586,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.6513675367672334,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.553815829857646,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.225258791861524,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8623551247338687,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.89379292628675,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.043462584868256,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.886416700647132,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.512742507350364,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.512938067391213,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.888806094510835,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 8.940178462516512,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 49.3243230456896,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 59.86557677799615,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 88.7734961108065,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 34.79651352338896,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 36.295875405624116,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 37.75140701851228,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.73335509862364,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.401547519659811,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 89.28921903174947,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2374.9417486166008,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "d4c11ca76a7de23ee26ab738b0a479d812da84f8",
          "message": "fix(ai): LC4j premature completion, ADK model override, cross-session approval\nLC4j doExecute now blocks on handle.whenDone() before returning — was firing lifecycle completion while the stream was still running. ADK buildRequestRunner honors context.model() when set. AiEndpointHandler cross-session approval fallback removed to preserve ownership guarantees.",
          "timestamp": "2026-04-12T13:42:39-04:00",
          "tree_id": "bf9dda207dd609f87522f471134c3cd5c77dbe7b",
          "url": "https://github.com/Atmosphere/atmosphere/commit/d4c11ca76a7de23ee26ab738b0a479d812da84f8"
        },
        "date": 1776016053938,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 12382061.031926604,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 4919016.666706842,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1101941.0823605182,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 284565.10244144726,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 298214.0167091786,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 307324.1835646611,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 278990.8129451873,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6258581798245167,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9542480858816337,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7645773903241905,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7978484291486143,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.546496645428217,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.227374801009413,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.248434352766395,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8709717528360487,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.8636120606748467,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.0468069337926496,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.863638624878305,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.502192696300938,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.976351448294556,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.421964762022741,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.388754457234043,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 49.133802242476285,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.73256569408681,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 87.72231763302268,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 31.786225891767454,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 33.929311416490584,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 34.20208031989186,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 35.686588386907054,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.038431020186648,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 88.94102126648446,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2350.5623536776216,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "ae732f8301c2f895ba44586b5e80f2f65b241b0d",
          "message": "feat(ai): ADK ContextCacheConfig bootstrap, Koog coroutine Job.cancel, resolver lazy scan\nADK Runners now build via App.Builder with ContextCacheConfig when setCacheConfig() is called at startup — enables Gemini prompt caching at the application level. Koog executeWithHandle stores the runBlocking coroutine Job and cancels it from ExecutionHandle.cancel() for immediate cancellation instead of soft-poll. EmbeddingRuntimeResolver re-scans on empty results to tolerate AiConfig startup-order races. SK README tightened on reactor-core 3.4.38 VT pinning risk.",
          "timestamp": "2026-04-12T14:41:44-04:00",
          "tree_id": "27f3b70b0ff7de6714a7ca9982a07fa0f71661c3",
          "url": "https://github.com/Atmosphere/atmosphere/commit/ae732f8301c2f895ba44586b5e80f2f65b241b0d"
        },
        "date": 1776019582805,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 11850348.41768893,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 6913148.450760081,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1117259.8943113028,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 266354.2742329717,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 312249.90748388,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 299511.332389679,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 275033.6478611223,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6267756528019321,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9561424303147937,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7696819801737013,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.801763194897318,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5489909694572552,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.244246818655183,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.39137748008994,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8511235641493209,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.9728778164495893,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.057540813871061,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.830299201522527,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.506222959282628,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 7.295998139591117,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 7.694159997591555,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 8.313218286796314,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 49.54594144601231,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 59.91764979521316,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 88.58634984557732,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 31.13648392045344,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 33.15437405354454,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 33.57029436900465,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 35.5562143057464,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 5.962685650056185,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 93.48949189728236,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2344.4679687987527,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "bd6e79437ecd2f448bca8b34a892f02f3880f1ae",
          "message": "feat: CLI cache-rebuild detection widened, samples showcase Wave 1-6 features\nCLI e2e test 9 now matches more rebuild indicators (downloading, resolving, BUILD SUCCESS, atmosphere-runtime marker) in addition to mismatch/building. spring-boot-ai-chat demonstrates promptCache=CONSERVATIVE on @AiEndpoint. spring-boot-ai-tools adds ToolAuditListener showcasing AgentLifecycleListener with onToolCall/onToolResult.",
          "timestamp": "2026-04-12T17:50:39-04:00",
          "tree_id": "893cc369dc17c6dfcb2ac1a8513e55b2542c27cb",
          "url": "https://github.com/Atmosphere/atmosphere/commit/bd6e79437ecd2f448bca8b34a892f02f3880f1ae"
        },
        "date": 1776030905728,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 12750011.988841983,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 5035711.876465463,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1047937.1369361589,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 311654.7643451675,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 306603.7250518342,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 305328.78836181265,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 293227.8560360302,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.7067271070868423,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.8954008310578344,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7060625847031732,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8345617983622322,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.6508635875071502,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.327447705902701,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.802635498433677,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8782576175883839,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 4.002615556772876,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 5.991512088396497,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 12.1828446270246,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 29.118502398635723,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 9.578821810211721,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 9.223734913153892,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.405961158351744,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 54.917358618000236,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 67.21149552078809,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 95.04478499456509,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 28.326189030144093,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 30.97589305377951,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 32.06132653672007,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 37.09355336444657,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.183998914959995,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 81.62382858231922,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2525.1530218487387,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "13cf557532b5de64f56599c605452fac5c434947",
          "message": "fix(ai): address review findings on ResponseCache + Ollama CI container init\nCacheKey now hashes Content.File parts (mime + length) alongside Image/Audio — closes F-C2 collision gap for file inputs. AiPipeline.streamText skips the cache when context.tools() is non-empty so text-only replays never silently drop tool round-trips (F-C3). ResponseCache Javadoc documents the concurrent cache-miss race as an accepted trade-off and the tool-call skip reasoning (F-C4). e2e-real-llm.yml drops the in-container curl healthcheck (Ollama image has no curl) and waits from the job step instead; matrix collapsed to a single real-llm-chat job since the spec is the only one wired today.",
          "timestamp": "2026-04-12T20:05:31-04:00",
          "tree_id": "a320a40374b8083802996f9b635d54c1a767aece",
          "url": "https://github.com/Atmosphere/atmosphere/commit/13cf557532b5de64f56599c605452fac5c434947"
        },
        "date": 1776038984602,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 11405223.738227269,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 5454206.211895723,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1064500.880277421,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 214684.21033291836,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 277438.8892564487,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 291548.9829299653,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 279649.49048292555,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.7075530578671163,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9026714761559568,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7067871871258525,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8248304020798223,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.6419041483858354,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.335765404728363,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.92407084301497,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8781488698894776,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 4.004048258334136,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.004404651904316,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 12.145841241246528,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 29.408698346429855,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 9.535075497147362,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 9.276750358996635,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 9.186851596451488,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 55.61510592634904,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 65.95987593839324,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 97.1208615520059,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 27.52215790705408,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 31.442686551411338,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 31.975532825518172,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.97831104607354,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.199402384820519,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 81.45232027850287,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2477.370882110471,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "9d25d77ad8b01f0f6dfd6888052d46f486d4d4a5",
          "message": "fix(ai): advertise PER_REQUEST_RETRY honestly and warn on silent overrides\nNew capability flag advertised by the Built-in runtime only — framework bridges inherit native retry layers and cannot be overridden per request. AbstractAgentRuntime.execute logs once per unique policy on runtimes that lack the capability so operators spot the gap in startup logs.",
          "timestamp": "2026-04-12T23:03:35-04:00",
          "tree_id": "40687d7d8d901bb78d2ff4f223873fa07e7879e2",
          "url": "https://github.com/Atmosphere/atmosphere/commit/9d25d77ad8b01f0f6dfd6888052d46f486d4d4a5"
        },
        "date": 1776049776801,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 13918814.726810755,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 6340841.110972307,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1082025.7300149591,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 206508.04206232075,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 290271.18038877466,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 302562.5294921686,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 289250.98473197257,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6254422428610079,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.949487403793334,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7651505750805248,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7996721658571417,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.549668521229652,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.730702093942307,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.270802644192344,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8544716377637473,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.875843576597197,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.05188414981875,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.826931921198922,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.647369949568667,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.799560184369843,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.172560220363058,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.412789667758514,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 50.58086407888492,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.54760930233247,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 88.57478724692078,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 34.689938370665196,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 37.03222265361091,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 37.60122155130978,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.27621095699302,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.42514385331763,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 95.80808996805104,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2458.4882487725044,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "4b4cf3eed1067b393c81888f88103ec9b8818a9a",
          "message": "fix(ai): Content records defensively clone byte[] on construction\nPrior behavior stored the caller-supplied reference directly, so a later mutation of the source array poisoned the record's state. Image/Audio/File now clone on input; the data() accessor still returns the internal array to avoid a second copy on every read.",
          "timestamp": "2026-04-12T23:21:44-04:00",
          "tree_id": "ec29dd30de2644284e6eb0f27738d6cf0f6a0395",
          "url": "https://github.com/Atmosphere/atmosphere/commit/4b4cf3eed1067b393c81888f88103ec9b8818a9a"
        },
        "date": 1776050779751,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 13015707.456161795,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 6650793.068740389,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 975934.2247767298,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 190853.9485144772,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 288905.733903783,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 283842.7693826012,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 279276.87907816493,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.7072819667179407,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9085233614798923,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7082704849636364,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8337192482027087,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.6394719301274776,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.328904259252296,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.8661419767847,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8774003028137362,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 4.011367361878086,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.015771883063717,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 12.197286151602768,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 29.197931615967434,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 9.667728998239637,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 9.428466424198504,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 8.446721385298643,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 55.20478853627873,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 67.7791620502516,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 97.02099321727422,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 33.25554074982907,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 31.56799592675379,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 32.11370251762703,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 37.84410886256967,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 5.945402365371205,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 84.89554952556297,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2516.9114510460236,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "f02dc3c4ea91d4c49dd861ed6bd9514b0c2eee11",
          "message": "feat(ai): InMemoryResponseCache.purgeExpired for eager TTL reclamation\nDocuments the LRU-vs-TTL interaction (expired entries occupy slots until touched) and adds a walk-once purgeExpired() so callers who schedule a background sweeper can reclaim dead slots eagerly. Default lazy-on-get path stays zero-overhead.",
          "timestamp": "2026-04-12T23:24:32-04:00",
          "tree_id": "81835debc4590f0a4196d6b9aa3a27ff69d93626",
          "url": "https://github.com/Atmosphere/atmosphere/commit/f02dc3c4ea91d4c49dd861ed6bd9514b0c2eee11"
        },
        "date": 1776050949037,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 14400822.497895846,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 5371932.879306593,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1351505.6396795218,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 305547.6085457054,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 288618.8662904404,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 305175.05777468946,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 292834.9319227242,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6261611166847997,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9505164871694318,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7653893470226597,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7983319999045515,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.547140280926172,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.543325279335177,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.329427415465926,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8587547954132573,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.8639180262073523,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.0646403933322794,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.860162694963515,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.656614097403303,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 11.735816835713047,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.73010404398453,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.297194511382139,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 50.62573541194413,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.2350412140546,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 90.9164866839114,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 35.040923579564605,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 36.545376650666306,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 37.65703516880586,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 37.60805694281908,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.373107321904774,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 88.66953016004506,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2395.492600638978,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "0670e2f8b3ca8022ccdbe73ad94954f44ec56408",
          "message": "fix(ai): CachingStreamingSession poisons capture on binary sendContent\nBinary Content (Image/Audio/File) cannot ride through a text-only StringBuilder, so the default sendContent throw would have bypassed the text capture. Override marks the session as errored so the pipeline's post-execute commit short-circuits — never caching a partial text-only response for a flow that emitted binary output.",
          "timestamp": "2026-04-12T23:28:37-04:00",
          "tree_id": "799a44e0ebc201c50a16d469652ea02cc3085803",
          "url": "https://github.com/Atmosphere/atmosphere/commit/0670e2f8b3ca8022ccdbe73ad94954f44ec56408"
        },
        "date": 1776051257718,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 11328381.67113699,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 4851163.613616598,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1097808.4268054115,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 276471.12786150293,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 312796.83513303066,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 303355.99826379685,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 296478.22239027254,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.7062133171855421,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.882812904813139,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7077386404437913,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8211865468351416,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.6584402250700627,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.35706847487805,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 26.03946730903976,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8838491222303578,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 4.0519537967305395,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.019735778403171,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 12.247040801070895,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 29.088064908444796,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 9.413808753990871,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 10.033145702204726,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.547404970337079,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 64.45749345384301,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 67.16637296120577,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 94.62007386447065,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 28.114556035187388,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 30.97229335870185,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 32.114087879882625,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.41696035056034,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.001627883441974,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 82.60422197080489,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2497.9544451827237,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
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
          "id": "0670e2f8b3ca8022ccdbe73ad94954f44ec56408",
          "message": "fix(ai): CachingStreamingSession poisons capture on binary sendContent\nBinary Content (Image/Audio/File) cannot ride through a text-only StringBuilder, so the default sendContent throw would have bypassed the text capture. Override marks the session as errored so the pipeline's post-execute commit short-circuits — never caching a partial text-only response for a flow that emitted binary output.",
          "timestamp": "2026-04-13T03:28:37Z",
          "url": "https://github.com/Atmosphere/atmosphere/commit/0670e2f8b3ca8022ccdbe73ad94954f44ec56408"
        },
        "date": 1776062507860,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 13266721.88261705,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 7056856.387703964,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1219249.8202566653,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 303135.5686863035,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 307611.16160528787,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 295711.5490356053,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 291924.5266811485,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6268787774565733,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9507086667659485,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7699632045928922,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7972442985615906,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5596057963054886,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.576874479354915,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.22894432754902,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.853300635610994,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.8645132349218883,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.0617896309690265,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.830678800845376,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.548947562764027,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 7.959694474185983,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 9.159011171169007,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.866250962376391,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 50.26873408377298,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.71553929069264,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 90.33092407524417,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 34.4058371431604,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 36.75230924962459,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 37.696276541839715,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 38.16030780771416,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.066113799869199,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 86.677369739392,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2434.88611993517,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "actions@github.com",
            "name": "GitHub Actions",
            "username": "actions-user"
          },
          "committer": {
            "email": "actions@github.com",
            "name": "GitHub Actions",
            "username": "actions-user"
          },
          "distinct": true,
          "id": "a420c864036c8a1b2d6b6d27dec9c678e84d4dae",
          "message": "chore: prepare for next development iteration 4.0.37-SNAPSHOT",
          "timestamp": "2026-04-13T14:05:11Z",
          "tree_id": "c1a702bfe93cbbc7442f5aeb7537ba07a63be9d0",
          "url": "https://github.com/Atmosphere/atmosphere/commit/a420c864036c8a1b2d6b6d27dec9c678e84d4dae"
        },
        "date": 1776089377641,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 11300107.9961367,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 5998951.026016407,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1001606.4101146092,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 324445.5393355092,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 290597.9476383211,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 299167.91339947726,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 282043.1213379733,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6245680019623303,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9554814590725297,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7661879438401195,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8059857334797187,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.552890507629242,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.33800175252544,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.212422962004013,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8650280980150914,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.864534825147875,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.060817680286779,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.875831144174084,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.582057708562605,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 7.824763828133141,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.180172234943258,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 8.162074638426729,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 50.07133655921918,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 59.87689472000375,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 89.1770599588657,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 31.42179932473869,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 33.6605731555777,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 33.96076448742008,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.01200248187755,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 5.975574244100845,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 90.62962526824857,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2313.1489946112383,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "49699333+dependabot[bot]@users.noreply.github.com",
            "name": "dependabot[bot]",
            "username": "dependabot[bot]"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "Jeanfrancois Arcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "28b57e6f8b5c963e3ac3d6fd45440e496c8d338e",
          "message": "chore(deps): bump org.apache.maven.plugins:maven-shade-plugin\n\nBumps [org.apache.maven.plugins:maven-shade-plugin](https://github.com/apache/maven-shade-plugin) from 3.5.3 to 3.6.2.\n- [Release notes](https://github.com/apache/maven-shade-plugin/releases)\n- [Commits](https://github.com/apache/maven-shade-plugin/compare/maven-shade-plugin-3.5.3...maven-shade-plugin-3.6.2)\n\n---\nupdated-dependencies:\n- dependency-name: org.apache.maven.plugins:maven-shade-plugin\n  dependency-version: 3.6.2\n  dependency-type: direct:production\n  update-type: version-update:semver-minor\n...\n\nSigned-off-by: dependabot[bot] <support@github.com>",
          "timestamp": "2026-04-13T12:55:36-04:00",
          "tree_id": "8ca6b521d532782d167177f3c8585a11ba1d66f7",
          "url": "https://github.com/Atmosphere/atmosphere/commit/28b57e6f8b5c963e3ac3d6fd45440e496c8d338e"
        },
        "date": 1776099622149,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 11375567.374899575,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 7314017.133581563,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 884368.8926132172,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 267681.74515366415,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 297964.79892726784,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 289164.9463523869,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 290824.6475827287,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6247637914474814,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9571625991159007,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.766417064608791,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.798026912112632,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5450705729030667,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 8.79432447764929,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.463407867847277,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8606994859897248,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.8684380001048067,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.051498025697756,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.866247414335232,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.56524623168296,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 7.979270958834632,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 6.930052004261117,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 8.527724791462182,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 50.064526778722815,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 63.304148703114514,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 89.41414304260952,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 31.094429801945463,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 33.870862361005955,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 34.54281797247047,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 35.95475610504988,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.550050795822947,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 88.19324425592077,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2414.6446816720268,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "49699333+dependabot[bot]@users.noreply.github.com",
            "name": "dependabot[bot]",
            "username": "dependabot[bot]"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "Jeanfrancois Arcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "f7d6d3fc5ff6326e2befee47a0f7763d8d79cc7a",
          "message": "chore(deps): bump actions/upload-artifact from 4 to 7\n\nBumps [actions/upload-artifact](https://github.com/actions/upload-artifact) from 4 to 7.\n- [Release notes](https://github.com/actions/upload-artifact/releases)\n- [Commits](https://github.com/actions/upload-artifact/compare/v4...v7)\n\n---\nupdated-dependencies:\n- dependency-name: actions/upload-artifact\n  dependency-version: '7'\n  dependency-type: direct:production\n  update-type: version-update:semver-major\n...\n\nSigned-off-by: dependabot[bot] <support@github.com>",
          "timestamp": "2026-04-13T12:56:15-04:00",
          "tree_id": "2855cffbee10fb88a0ebfdf26f55e4115055841d",
          "url": "https://github.com/Atmosphere/atmosphere/commit/f7d6d3fc5ff6326e2befee47a0f7763d8d79cc7a"
        },
        "date": 1776100139582,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 10847636.68218126,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 4708186.28642468,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1091508.2680849435,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 300602.9143599285,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 308827.7485476961,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 291575.4801111061,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 288618.3093939216,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.7064051169980469,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.8904968436422491,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7134705583813309,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8346126460026657,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.6400770952979684,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.339943648697695,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.861739371007445,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8840724914855523,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 4.024565689872891,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.0092504506882305,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 12.249938391635682,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 29.208314831402735,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 7.756946322071097,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.89384639534982,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.500631370226298,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 55.017951256870184,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 67.94096653963007,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 94.2164763341137,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 27.51799474223168,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 31.27750221563541,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 32.12233678834933,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.47077845826937,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 5.979607895333894,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 82.8842716151658,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2504.700586666667,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "49699333+dependabot[bot]@users.noreply.github.com",
            "name": "dependabot[bot]",
            "username": "dependabot[bot]"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "Jeanfrancois Arcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "95ded31a4f87423e92741e2bd436b201813b0f3f",
          "message": "chore(deps): bump actions/checkout from 4 to 6\n\nBumps [actions/checkout](https://github.com/actions/checkout) from 4 to 6.\n- [Release notes](https://github.com/actions/checkout/releases)\n- [Changelog](https://github.com/actions/checkout/blob/main/CHANGELOG.md)\n- [Commits](https://github.com/actions/checkout/compare/v4...v6)\n\n---\nupdated-dependencies:\n- dependency-name: actions/checkout\n  dependency-version: '6'\n  dependency-type: direct:production\n  update-type: version-update:semver-major\n...\n\nSigned-off-by: dependabot[bot] <support@github.com>",
          "timestamp": "2026-04-13T12:56:27-04:00",
          "tree_id": "6fed40bc8faa54ef743a230da670c223ae5a532a",
          "url": "https://github.com/Atmosphere/atmosphere/commit/95ded31a4f87423e92741e2bd436b201813b0f3f"
        },
        "date": 1776100238634,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 11319405.815797389,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 6353382.577495136,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1180421.3116513395,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 286808.2023919365,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 288140.02498810895,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 300880.6582874002,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 292804.9445422426,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6248699450407537,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9508006723256388,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7658082684686338,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7943085204579547,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5579476397113425,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.211224132108791,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.255549977360104,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8568115406305904,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.8990551505310083,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.070774940339466,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.860317217738192,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.624478568319518,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.598862383677861,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 7.254485299137404,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.218785933968218,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 50.742627092419404,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 59.44149797343699,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 88.48947678512162,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 31.07203122028628,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 33.14146795356492,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 33.95187480331197,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 35.31811050112049,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.10033784156776,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 89.01539221272556,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2377.7069200316705,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "a5b0cdf6c84244bfad21d73d5475636c5c055ea2",
          "message": "docs: add 4.0.36 CHANGELOG entry covering Waves 1-6 + fixes",
          "timestamp": "2026-04-13T13:45:14-04:00",
          "tree_id": "6866db4898b694275067b5fc6992dd72c26d071b",
          "url": "https://github.com/Atmosphere/atmosphere/commit/a5b0cdf6c84244bfad21d73d5475636c5c055ea2"
        },
        "date": 1776102929024,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 12407640.065276109,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 6259133.233932409,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1187172.9004819386,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 273032.5863836084,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 308626.21702016593,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 306643.58751440136,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 286723.48581224895,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6259734115355154,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9573612926541125,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7673608219819307,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8063565663895675,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.579827087959553,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.524900183483332,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.238998463728546,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8524364061450805,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.882183466197691,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.055670603907526,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.85826846768562,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.743820943027373,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 10.347251454068278,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 11.469447093403701,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 8.252122490376829,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 50.68593384121598,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.180486123334894,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 88.98075121367397,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 34.269226441197894,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 36.9519692271281,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 37.95640178837164,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 37.87324841769764,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 5.8855453364816945,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 92.24976510790249,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2278.158556482184,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "59a60832fea78b418b7eb46bb8f093d14c42e28a",
          "message": "feat(ai): runtime capability honesty pass — 7 runtimes declare honest capabilities",
          "timestamp": "2026-04-13T16:53:04-04:00",
          "tree_id": "3771f669ef8bc874ee7734a4784fa9a21d3a7658",
          "url": "https://github.com/Atmosphere/atmosphere/commit/59a60832fea78b418b7eb46bb8f093d14c42e28a"
        },
        "date": 1776113891169,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 10163524.366126116,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 5877930.836494736,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1220679.666340371,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 192619.04425241248,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 306323.4685730417,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 293562.21715941664,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 291828.7290843132,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6292058467323405,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9535189650678525,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7650747125642253,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7958538762363307,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5552987289771667,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.443623934450853,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.328318636946552,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8632497129183344,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.8678132857307723,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.04217947529725,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.839893107924723,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.51197192091405,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 7.288648120453753,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 7.470588480350956,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.0658814184629835,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 51.74470637858763,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 59.83538931136556,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 89.85192708687964,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 31.360675340475414,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 33.5623201645675,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 33.93506607917717,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.60393916049267,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.129437601567365,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 90.42519231117413,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2426.8568400646195,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "18f57e886ddf43808d649c8dd3667e1102147313",
          "message": "feat(ai): 4.0.36 e2e coverage sweep + framework fixes from audit\nTen gap specs, two samples, five framework fixes (checkpoint/cache/binary/capability/HITL).",
          "timestamp": "2026-04-13T19:33:13-04:00",
          "tree_id": "eb2fce5191d036b66fa2b06f82c4feeac1f25fdd",
          "url": "https://github.com/Atmosphere/atmosphere/commit/18f57e886ddf43808d649c8dd3667e1102147313"
        },
        "date": 1776123952373,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 12924223.546414144,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 6942089.52965333,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1160447.721644636,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 312963.7001896175,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 313512.3909234644,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 294940.6006102998,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 295262.2314368304,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.625303494410933,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.953440696998305,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7686144596547483,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7956248483040504,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5525848118790884,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.47548809508602,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.20207574924255,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8515680155828909,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.8658117030343395,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.054986599393954,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.844983944972725,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.593433092276786,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 9.044986025961256,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.584088690720309,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.789479174306372,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 48.88010915274291,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 59.946398688047935,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 89.36746577002351,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 34.97005205645644,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 36.96123563312687,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 37.759405078970595,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 37.8328705704732,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.017909687199367,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 89.48072214757076,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2361.7143144654083,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "actions@github.com",
            "name": "GitHub Actions",
            "username": "actions-user"
          },
          "committer": {
            "email": "actions@github.com",
            "name": "GitHub Actions",
            "username": "actions-user"
          },
          "distinct": true,
          "id": "2618d618bb0a1bb364a95bd3730d017d5c85ae81",
          "message": "chore: prepare for next development iteration 4.0.38-SNAPSHOT",
          "timestamp": "2026-04-14T01:22:56Z",
          "tree_id": "43a0f33af41ed7752d23b2f122e9dfd0c8e86bfd",
          "url": "https://github.com/Atmosphere/atmosphere/commit/2618d618bb0a1bb364a95bd3730d017d5c85ae81"
        },
        "date": 1776130058121,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 13355231.263080304,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 5931640.41900516,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 836640.7991542545,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 292835.80755329883,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 288562.6729821321,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 302365.0271434813,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 289100.61686266604,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6264073952022456,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9531196716144247,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7652157220655219,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8009462276952366,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5497579061620663,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.349871430797245,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.282483859744172,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8497129632550219,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.8644929219977446,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.074399122398788,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.830571778763646,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.70490249226803,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 8.220584275900436,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 7.374056774975142,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 6.915727093160242,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 50.42809219492708,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 59.936361055741905,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 89.72230577030992,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 35.19238117000804,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 36.20627868338456,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 37.383555248661345,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 36.211453037256376,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.066107365346211,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 88.02665084447061,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2381.0168183980963,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "68fe793f286cae7793a4921bd60f8f6b37e80550",
          "message": "fix(coordinator): bridge Spring CoordinationJournal beans into the processor\nCoordinatorProcessor.resolveJournal now reads framework properties first so spring-boot-starter can wire @Bean journals; bridged journals are externally managed (no double start) and a new auto-config closes the durable HITL gap.",
          "timestamp": "2026-04-14T07:25:00-04:00",
          "tree_id": "adb79fc4aa413826523130f44c6c304da1b84825",
          "url": "https://github.com/Atmosphere/atmosphere/commit/68fe793f286cae7793a4921bd60f8f6b37e80550"
        },
        "date": 1776167103944,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 11544872.702252502,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 4479425.1330800345,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 954090.4661289016,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 299465.08336124674,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 294605.16072421736,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 290049.30180277413,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 281167.31893794535,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.7075549157011424,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.8942747401229254,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7060918758829425,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8258965917186842,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.6441317725017477,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.34256926636723,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.80476934540978,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8854852336403831,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 4.008746026763122,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.007115245476428,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 12.178348947536543,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 29.038407861854097,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 9.276855620332908,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 9.517576124898197,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 9.126527724534293,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 54.63230769315826,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 67.64071373126025,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 95.34678337752518,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 27.64960319376807,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 30.42119911917248,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 31.33869416678328,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 37.426697614450205,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 5.977790085019752,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 82.12227701407518,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2517.6883886097153,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "30907539bbeecf4bf084c84e9d0e7468ad24ef4e",
          "message": "fix(spring-boot3-starter): port CoordinationJournal bridge auto-config",
          "timestamp": "2026-04-14T11:08:50-04:00",
          "tree_id": "9ada4a1d7634077208ae21c8c607ad924ca25009",
          "url": "https://github.com/Atmosphere/atmosphere/commit/30907539bbeecf4bf084c84e9d0e7468ad24ef4e"
        },
        "date": 1776180064758,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 10801120.54464897,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 7215036.989558678,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 905192.5922899577,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 307660.38238106767,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 300063.0111100702,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 294356.0699444862,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 297494.4880812008,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.6255467045350791,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.9676559622972533,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7675973485961354,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.7999188212451627,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.548392159623249,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.540675742437275,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.209217321083106,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8529360714375424,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.871034262393047,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.044160063576253,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.839618796452399,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.568648986720444,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 7.653251267693406,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 8.316294639953798,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 7.0003836740544,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 50.17703037752897,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 60.09280953221139,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 89.22536923444746,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 34.57785525421455,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 36.60514535623534,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 37.31407669787054,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 38.693860034930005,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 6.136184812552419,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 88.74793916799828,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 2362.734577498034,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "committer": {
            "email": "jfarcand@apache.org",
            "name": "jfarcand",
            "username": "jfarcand"
          },
          "distinct": true,
          "id": "6e75bf7d608cd2f87c9f34be12136a00c748397d",
          "message": "test(cpr): add tests for StringEscapeUtils, QueryStringDecoder, HeartbeatInterceptor",
          "timestamp": "2026-04-14T12:51:20-04:00",
          "tree_id": "fcc2435acf5535824419ab9fba4d0977c6e99f61",
          "url": "https://github.com/Atmosphere/atmosphere/commit/6e75bf7d608cd2f87c9f34be12136a00c748397d"
        },
        "date": 1776186264302,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1\"} )",
            "value": 16976066.167235013,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"10\"} )",
            "value": 5883915.696275011,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"100\"} )",
            "value": 1278112.015645479,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.BroadcasterDispatchBenchmark.broadcastToAll ( {\"subscriberCount\":\"1000\"} )",
            "value": 270756.02878487465,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"100\"} )",
            "value": 254240.463132123,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"1000\"} )",
            "value": 258765.70338439607,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.saveHot ( {\"snapshotCount\":\"10000\"} )",
            "value": 238900.3765163175,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveAllSorted",
            "value": 0.5498948604492581,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AgentRuntimeResolverBenchmark.resolveFirst",
            "value": 0.7191066269887201,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.5482337908120182,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.404562309206689,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 2.8298279268416398,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 7.247593880799978,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 20.184772272412907,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.6792245268272213,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.102668822796044,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 4.660040407324806,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.452014039539725,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 22.548360016118533,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"100\"} )",
            "value": 108.07526788201947,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"1000\"} )",
            "value": 107.8395821221568,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.forkChain ( {\"snapshotCount\":\"10000\"} )",
            "value": 9.020851135692553,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"100\"} )",
            "value": 43.89520173838503,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"1000\"} )",
            "value": 52.62904730797768,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.loadRandom ( {\"snapshotCount\":\"10000\"} )",
            "value": 73.32782763487569,
            "unit": "ns/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"2\"} )",
            "value": 22.626542151235498,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"4\"} )",
            "value": 24.61052330331029,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"8\"} )",
            "value": 24.933320329352643,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CoordinatorFanOutBenchmark.parallelFanOut ( {\"fanOutCount\":\"16\"} )",
            "value": 28.252973491663017,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"100\"} )",
            "value": 4.629570028873011,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"1000\"} )",
            "value": 62.4265071642786,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.CheckpointStoreBenchmark.listByCoordination ( {\"snapshotCount\":\"10000\"} )",
            "value": 1895.45436466877,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      }
    ]
  }
}
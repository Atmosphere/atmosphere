window.BENCHMARK_DATA = {
  "lastUpdate": 1775581267340,
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
      }
    ]
  }
}
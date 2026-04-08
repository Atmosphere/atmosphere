window.BENCHMARK_DATA = {
  "lastUpdate": 1775660571332,
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
      }
    ]
  }
}
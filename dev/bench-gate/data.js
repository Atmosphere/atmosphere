window.BENCHMARK_DATA = {
  "lastUpdate": 1785050050301,
  "repoUrl": "https://github.com/Atmosphere/atmosphere",
  "entries": {
    "Atmosphere JMH Stable Gate": [
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
          "id": "2a58ce36a692e1521b9f83fd38005e1791d3bfe7",
          "message": "feat(ai): Tier-3 polish — observability, TCK depth, invariants, security defaults, DX\nConsumers: percentile+cached-token meters and console tab on both runtimes; behavioural CANCELLATION TCK + capability meta-gate across 12 runtimes; session-map sweeper and MCP teardown; Anthropic cache_control/tool-deltas/model enumeration; JDBC schema versioning; A2A raw-handler admission; webhook dedup; Kotlin agent DSL; markdown/sentence chunkers; 20 e2e tests returned to live coverage",
          "timestamp": "2026-07-26T02:48:16-04:00",
          "tree_id": "6faabc9ad82e2e56a197889def7ca31c2022bdc6",
          "url": "https://github.com/Atmosphere/atmosphere/commit/2a58ce36a692e1521b9f83fd38005e1791d3bfe7"
        },
        "date": 1785050048141,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7664706003796328,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.80978430264924,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.429713743541054,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 8.158936288276694,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.31954071584586,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8519077855876127,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.744554721233017,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 5.9284689238037425,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.958984369147142,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.27186559313057,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.msAgentOsRuleMatch",
            "value": 0.09063722510881782,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionFlagged",
            "value": 0.818891244616446,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionSafe",
            "value": 4.796631100951461,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeAdmit",
            "value": 7.844033920273395,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeDeny",
            "value": 1.7950837914470288,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.semanticIntentScopeAdmit",
            "value": 0.09465311318824468,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          }
        ]
      }
    ]
  }
}
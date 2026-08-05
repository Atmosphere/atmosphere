window.BENCHMARK_DATA = {
  "lastUpdate": 1785899278970,
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
          "id": "86693b6e85dbfaca83e4c64639110dc353ecfc94",
          "message": "fix(spring-boot): install the dev inspector without requiring the coordinator\nThe bean sat inside the coordinator-gated config, so plain AI apps recorded nothing despite the flag",
          "timestamp": "2026-07-26T14:33:21Z",
          "url": "https://github.com/Atmosphere/atmosphere/commit/86693b6e85dbfaca83e4c64639110dc353ecfc94"
        },
        "date": 1785135867066,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7631019971271329,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8107135834972898,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.4328670442606564,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 8.161756202100822,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.324655010430522,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8503455357918946,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.7438783297654235,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 5.93121516561332,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.933328306963409,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.299493100394432,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.msAgentOsRuleMatch",
            "value": 0.08823518188763133,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionFlagged",
            "value": 0.8252558044085244,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionSafe",
            "value": 4.930878486714219,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeAdmit",
            "value": 8.463648499520911,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeDeny",
            "value": 1.7832075099205564,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.semanticIntentScopeAdmit",
            "value": 0.09575826099732059,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
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
          "id": "c0472dcb56101e1feddd05866025c5fa14bd73c1",
          "message": "fix(spring-boot): gate the dev inspector on its own type, not a neighbouring module\nTop-level placement broke servlet-only apps with NoClassDefFoundError; the coordinator gate silently skipped the recorder",
          "timestamp": "2026-08-02T23:45:54Z",
          "url": "https://github.com/Atmosphere/atmosphere/commit/c0472dcb56101e1feddd05866025c5fa14bd73c1"
        },
        "date": 1785740627530,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7055957678488778,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.845616081239541,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5558686877324583,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.271205648367605,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.94440923151322,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8903340206214739,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.869499686693557,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.012684159935979,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 11.96743569357527,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 28.204616781449452,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.msAgentOsRuleMatch",
            "value": 0.09631911129294632,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionFlagged",
            "value": 0.8286523062837376,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionSafe",
            "value": 4.869475968627405,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeAdmit",
            "value": 7.835169622089246,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeDeny",
            "value": 1.8660743114543679,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.semanticIntentScopeAdmit",
            "value": 0.09130645319402508,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
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
          "id": "25de92a1de4bef6abea2a40ef6641658f2607f57",
          "message": "ci(governance): pin MS YAML conformance diff to the last pre-ACS upstream commit\nUpstream 8149ebf93d deleted the policy-as-code examples for ACS manifests; the pin keeps the byte-for-byte claim testable",
          "timestamp": "2026-08-03T10:56:40-04:00",
          "tree_id": "b9d08adb4f836540f5af4519ed534abb0c4a9d3d",
          "url": "https://github.com/Atmosphere/atmosphere/commit/25de92a1de4bef6abea2a40ef6641658f2607f57"
        },
        "date": 1785772284062,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7056228131761108,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8479699294151462,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5488095225512724,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.275762904230758,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.937960951664774,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8882110896253248,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.872790840797168,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 5.990677971924417,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 12.15409671570622,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 28.35247172410731,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.msAgentOsRuleMatch",
            "value": 0.09413011769762392,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionFlagged",
            "value": 0.8254904159458258,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionSafe",
            "value": 4.890302663120286,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeAdmit",
            "value": 7.465376007157401,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeDeny",
            "value": 1.522842768817177,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.semanticIntentScopeAdmit",
            "value": 0.09081732572769755,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
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
          "id": "3730f055dda820fb972efb9bddc206c4ea337ca6",
          "message": "feat(ai): parse and enforce MS Agent Control Specification manifests\nYamlPolicyParser routes the ACS root key to intervention-point bindings with fail-closed opa-backed rego verdicts; weekly conformance diffs upstream's own contract fixtures on main, retiring the pre-ACS pin",
          "timestamp": "2026-08-03T14:35:52-04:00",
          "tree_id": "7e068889e680fb5668f1e4a2ce7046618db9c049",
          "url": "https://github.com/Atmosphere/atmosphere/commit/3730f055dda820fb972efb9bddc206c4ea337ca6"
        },
        "date": 1785783197845,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7598091767202122,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.78951400990579,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.4033113274134266,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 8.091089802132144,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.046811790844806,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8349696652363029,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.7271604367637052,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 5.891777930998171,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.87223764942178,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.192131407525558,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.msAgentOsRuleMatch",
            "value": 0.09283387566127697,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionFlagged",
            "value": 0.8198469882801854,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionSafe",
            "value": 4.9364506980834175,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeAdmit",
            "value": 7.7791057290919285,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeDeny",
            "value": 1.6415085044787978,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.semanticIntentScopeAdmit",
            "value": 0.09558252320448823,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
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
          "id": "bbcf64b5b41222e317bc98b514cfa581dd05cde9",
          "message": "feat(ai): response-cache parity on @AiEndpoint, CrewAI schema facets, ADK step cap\nConsumers: AiStreamingSession gate + ResponseCacheConfig install at AiEndpointProcessor; ToolBridgeUtils schema on the CrewAI sidecar wire; LlmAgent.maxSteps opt-in",
          "timestamp": "2026-08-03T20:36:41-04:00",
          "tree_id": "dfabc53e64007382b70a2cf51ddb1287d06348b9",
          "url": "https://github.com/Atmosphere/atmosphere/commit/bbcf64b5b41222e317bc98b514cfa581dd05cde9"
        },
        "date": 1785805711194,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.766709739172753,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8024803549607369,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.431304234354517,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 8.158315326376496,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.35624444452199,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8425195959466307,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.7470696732445368,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.022420290697876,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.931509296796559,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.301806031434285,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.msAgentOsRuleMatch",
            "value": 0.08903629956062728,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionFlagged",
            "value": 0.7671330818595076,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionSafe",
            "value": 4.860164867210085,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeAdmit",
            "value": 7.868023539524666,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeDeny",
            "value": 1.8898019755088171,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.semanticIntentScopeAdmit",
            "value": 0.0956066063414933,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
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
          "id": "c4977dc7b20046fc0bd53ad1026a7419fc541a75",
          "message": "test(e2e): wait for the console composer before sending in multi-client\nThe Connected badge reports socket state, not composer readiness; a send in that gap was discarded and the list stayed empty",
          "timestamp": "2026-08-03T21:39:28-04:00",
          "tree_id": "edaf90d75e5b9c31e89ec0d0bedc071737c71c73",
          "url": "https://github.com/Atmosphere/atmosphere/commit/c4977dc7b20046fc0bd53ad1026a7419fc541a75"
        },
        "date": 1785809053046,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.706293970008498,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.850658269308847,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5573943177207297,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.264209952091061,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.931157027565774,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8966804405521677,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.8733537778181932,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 5.987598114564369,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 11.956382568982278,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 28.213249219859716,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.msAgentOsRuleMatch",
            "value": 0.09610456335264925,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionFlagged",
            "value": 0.8088913399058149,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionSafe",
            "value": 5.147874360020276,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeAdmit",
            "value": 7.351518825782954,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeDeny",
            "value": 1.5633259794587542,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.semanticIntentScopeAdmit",
            "value": 0.09123013497986847,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
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
          "id": "ad2edefbc388db785bb723279b71cb494d96a50e",
          "message": "fix(samples): route keyless demo mode through the pipeline runtime across the AI samples\nDemoResponseProducer bypasses become DemoAgentRuntime strategies (agui/dentist/orchestration/ai-tools; quarkus documents its bridge-only truth); ai-tools drives real tools and the real approval registry via new AiStreamingSession.unwrap, with HITL/tool-activity specs strengthened to match",
          "timestamp": "2026-08-03T22:24:37-04:00",
          "tree_id": "c805f472de2c0693a305d775e913392654ef29e4",
          "url": "https://github.com/Atmosphere/atmosphere/commit/ad2edefbc388db785bb723279b71cb494d96a50e"
        },
        "date": 1785811612809,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7667091115413737,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8146846653228432,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.439584059197273,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 8.13887105649979,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.32266847143006,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8426856850396988,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.743819907447589,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 5.964457043073446,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.929039502467196,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.275325547284055,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.msAgentOsRuleMatch",
            "value": 0.09263155094016547,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionFlagged",
            "value": 1.0659190928631304,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionSafe",
            "value": 4.793152335057722,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeAdmit",
            "value": 7.7719095960915014,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeDeny",
            "value": 1.6280945737180208,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.semanticIntentScopeAdmit",
            "value": 0.09564717014916115,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
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
          "id": "9a4f1595146099f73c50586987d5e01c32301416",
          "message": "fix(ai): size the compaction budget to the endpoint model, not the app default\nCompactionConfig.resolve(cfg, model) had zero callers, so @AiEndpoint(model=) got the configured model window - 1M instead of 128k",
          "timestamp": "2026-08-04T15:39:18-04:00",
          "tree_id": "03b0b6843724e076326eb7c9912cda457217e8ea",
          "url": "https://github.com/Atmosphere/atmosphere/commit/9a4f1595146099f73c50586987d5e01c32301416"
        },
        "date": 1785874769490,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7657997497386495,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8000771240263407,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.4393707655120105,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 8.150998924621785,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.313170820552,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8457859402875352,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.7451346782801993,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 5.928629350401783,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.957684149821823,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.281626835802992,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.msAgentOsRuleMatch",
            "value": 0.09520550022741711,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionFlagged",
            "value": 0.8138899928663521,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionSafe",
            "value": 4.822512479207624,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeAdmit",
            "value": 8.469388986286557,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeDeny",
            "value": 1.666304997659519,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.semanticIntentScopeAdmit",
            "value": 0.09534572908395995,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "63293651+prodmanpd@users.noreply.github.com",
            "name": "Prodman Devokadev",
            "username": "prodmanpd"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "28572b3a751b1ad006a6247269ca37704ee35297",
          "message": "feat(ai): add LiteLLM proxy quick factory to OpenAiCompatibleClient\n\nlitellm(apiKey) targets a local proxy on localhost:4000/v1, litellm(baseUrl, apiKey) an explicit one; thin wrappers over builder(), additive only",
          "timestamp": "2026-08-04T16:56:55-04:00",
          "tree_id": "7aa490ef1abc2a7d42fd1b3240a5a743a9e62ea0",
          "url": "https://github.com/Atmosphere/atmosphere/commit/28572b3a751b1ad006a6247269ca37704ee35297"
        },
        "date": 1785877547626,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7068196028245438,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8542463212999512,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.7841478649140328,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.278063968870956,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.954665467113973,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.889280676147321,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.8685028434193405,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 5.991085685332312,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 11.9561851749858,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 28.20077313569815,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.msAgentOsRuleMatch",
            "value": 0.09522374849147264,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionFlagged",
            "value": 0.8315172861994687,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionSafe",
            "value": 5.109602174610904,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeAdmit",
            "value": 7.344999108816656,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeDeny",
            "value": 1.8454556448263575,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.semanticIntentScopeAdmit",
            "value": 0.090562200054662,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
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
          "id": "19615927f0b88cfb57835ae3253ea9b167202468",
          "message": "ci(e2e): make playwright-install retries wait for the dpkg lock instead of failing instantly\ntimeout 300 kills npx but not the sudo'd apt-get, which keeps holding the lock; DPkg::Lock::Timeout lets later attempts recover",
          "timestamp": "2026-08-04T17:10:09-04:00",
          "tree_id": "406acf0a66b5ee8f4c8945e565bdac890808b73f",
          "url": "https://github.com/Atmosphere/atmosphere/commit/19615927f0b88cfb57835ae3253ea9b167202468"
        },
        "date": 1785880321318,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7643183796361301,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8033130821663241,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.437276561857497,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 8.141796610806367,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.308748495713548,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8413107545921863,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.742467535635575,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 5.935614593754688,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.948204599888065,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.287188040903622,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.msAgentOsRuleMatch",
            "value": 0.09041728968627968,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionFlagged",
            "value": 0.8433691911277726,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionSafe",
            "value": 4.791998687480675,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeAdmit",
            "value": 7.8453088207488335,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeDeny",
            "value": 1.879656572465689,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.semanticIntentScopeAdmit",
            "value": 0.09528817267911088,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
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
          "id": "bea7ed968244f5ad9c4f46cfeba6a2bfcc122ec9",
          "message": "fix(ai): bound cost-ceiling tenant buckets and roll accrual over\nThe map was keyed by a caller-supplied MDC tag with no cap and never decayed; 500 tags made 500 entries and one breach blocked forever",
          "timestamp": "2026-08-04T17:52:19-04:00",
          "tree_id": "8d0fe5da7f64a379bb6b19485d73b0a626eca589",
          "url": "https://github.com/Atmosphere/atmosphere/commit/bea7ed968244f5ad9c4f46cfeba6a2bfcc122ec9"
        },
        "date": 1785881598370,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7761670551526156,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8072549207776785,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.4345381631124576,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 8.180321534202161,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.477306826532747,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8470315406386675,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.757587879710499,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 5.979002733017337,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.949225899647868,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.28547403275848,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.msAgentOsRuleMatch",
            "value": 0.08838228037342914,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionFlagged",
            "value": 0.8216057905407117,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionSafe",
            "value": 4.723415055866484,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeAdmit",
            "value": 7.8624136891176875,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeDeny",
            "value": 1.9884039888015685,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.semanticIntentScopeAdmit",
            "value": 0.09626490228101252,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
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
          "id": "67caf884b40fdd4eb40fcffaef293077c88f0967",
          "message": "fix(samples): ai-tools cost badge rides beforeCompletion without clobbering the router\nrouting.* moves off the dead postProcess path and yields to RoutingLlmClient's genuine values; reset copy no longer narrates success before the approval verdict; e2e asserts the rendered chips",
          "timestamp": "2026-08-04T18:43:04-04:00",
          "tree_id": "67338fe09babeb91a57e37a5b6861f84f7d8d2c3",
          "url": "https://github.com/Atmosphere/atmosphere/commit/67caf884b40fdd4eb40fcffaef293077c88f0967"
        },
        "date": 1785885020487,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7046523109336886,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8433277879829675,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5586064255330085,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.334533174267213,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.92466789530038,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8862505071770244,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.891873578000781,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.016443339429205,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 11.964764060756755,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 28.202240545652934,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.msAgentOsRuleMatch",
            "value": 0.0981334884453861,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionFlagged",
            "value": 0.8281788932668231,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionSafe",
            "value": 5.149914627409013,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeAdmit",
            "value": 7.360133572210157,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeDeny",
            "value": 1.7752462402849372,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.semanticIntentScopeAdmit",
            "value": 0.09138996889659878,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
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
          "id": "0964b189b23f2889d3478c87ac26fa9f78a56de4",
          "message": "docs: align Quarkus version mentions with the 3.36.3 pin",
          "timestamp": "2026-08-04T19:16:46-04:00",
          "tree_id": "8a2431301364f51a70fa685ef59435ed4c4a9d26",
          "url": "https://github.com/Atmosphere/atmosphere/commit/0964b189b23f2889d3478c87ac26fa9f78a56de4"
        },
        "date": 1785888222293,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7057222770540557,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8628377905191527,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.5594193190734975,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 9.3189791605509,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 26.101501603307725,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.8886660495980669,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.8739585851735945,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 6.0218198962483225,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 11.949125791308735,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 28.180868804501056,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.msAgentOsRuleMatch",
            "value": 0.09859274380494532,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionFlagged",
            "value": 0.7865099295489733,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionSafe",
            "value": 5.117858604409086,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeAdmit",
            "value": 7.387158287230939,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeDeny",
            "value": 1.4913519694867072,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.semanticIntentScopeAdmit",
            "value": 0.09109358353404219,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
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
          "id": "a13e3ae74c9d83e80a381c4789ab8d1436986509",
          "message": "ci: bump actions/setup-node to v7 and action-gh-release to v3",
          "timestamp": "2026-08-04T22:38:49-04:00",
          "tree_id": "32c03265280f5b01175e8116bc7e3c94841c83fe",
          "url": "https://github.com/Atmosphere/atmosphere/commit/a13e3ae74c9d83e80a381c4789ab8d1436986509"
        },
        "date": 1785899275999,
        "tool": "jmh",
        "benches": [
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.7670017817591732,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 1.8119247354592496,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 3.433547391758082,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 8.184635340098053,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.postProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 24.370725775441393,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"0\"} )",
            "value": 0.847522222222989,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"1\"} )",
            "value": 3.7421868027451772,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"4\"} )",
            "value": 5.927556042683306,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"16\"} )",
            "value": 10.92502197330695,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.AiInterceptorChainBenchmark.preProcessChain ( {\"chainLength\":\"64\"} )",
            "value": 25.309180954293478,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.msAgentOsRuleMatch",
            "value": 0.08864189274530254,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionFlagged",
            "value": 0.8119448509867894,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedInjectionSafe",
            "value": 4.9273512221085465,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeAdmit",
            "value": 7.894523241883841,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.ruleBasedScopeDeny",
            "value": 1.641110654290984,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "org.atmosphere.benchmarks.jmh.PolicyEvalBenchmark.semanticIntentScopeAdmit",
            "value": 0.09545102019426425,
            "unit": "us/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          }
        ]
      }
    ]
  }
}
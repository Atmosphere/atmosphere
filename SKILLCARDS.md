# Atmosphere Skill Card Catalog

Generated artifact — regenerate with `./scripts/regen-skillcards.sh`.
This file is the index for every per-runtime `SKILLCARD.yaml` shipped
in this repository. Each row is a verifiable trust manifest plus its
OpenSSF Model Signing signature when one has been produced (tag-time
sigstore-keyless via `.github/workflows/sign-skillcards.yml`).

Distribution model: git itself is the daily sync — every push to
`main` propagates the cards (and any signatures committed alongside)
to every clone. Released signatures are also attached to the GitHub
release as workflow artifacts; the bundled jars carry the same files
at `META-INF/atmosphere/SKILLCARD.yaml[.sig]`, so downstream consumers
can verify integrity without re-fetching from this repository.

Inspired by NVIDIA's verified-agent-skills catalog
(<https://developer.nvidia.com/blog/nvidia-verified-agent-skills-provide-capability-governance-for-ai-agents/>);
the signature envelope is the same OpenSSF Model Signing format
NVIDIA uses (`https://github.com/sigstore/model-transparency`).

| Runtime | Module | Language | Capabilities | Card | Contract test | Signature |
|---------|--------|----------|--------------|------|---------------|-----------|
| `AdkAgentRuntime` | `modules/adk` | java | 19 | [card](modules/adk/SKILLCARD.yaml) | [test](modules/adk/src/test/java/org/atmosphere/ai/adk/AdkRuntimeContractTest.java) | unsigned |
| `AgentScopeAgentRuntime` | `modules/agentscope` | java | 17 | [card](modules/agentscope/SKILLCARD.yaml) | [test](modules/agentscope/src/test/java/org/atmosphere/ai/agentscope/AgentScopeRuntimeContractTest.java) | unsigned |
| `AnthropicAgentRuntime` | `modules/anthropic` | java | 16 | [card](modules/anthropic/SKILLCARD.yaml) | [test](modules/anthropic/src/test/java/org/atmosphere/ai/anthropic/AnthropicRuntimeContractTest.java) | unsigned |
| `BuiltInAgentRuntime` | `modules/ai-test` | java | 18 | [card](modules/ai/SKILLCARD.yaml) | [test](modules/ai-test/src/test/java/org/atmosphere/ai/test/BuiltInRuntimeContractTest.java) | unsigned |
| `CohereAgentRuntime` | `modules/cohere` | java | 16 | [card](modules/cohere/SKILLCARD.yaml) | [test](modules/cohere/src/test/java/org/atmosphere/ai/cohere/CohereRuntimeContractTest.java) | unsigned |
| `CrewAiAgentRuntime` | `modules/crewai` | java | 9 | [card](modules/crewai/SKILLCARD.yaml) | [test](modules/crewai/src/test/java/org/atmosphere/ai/crewai/CrewAiRuntimeContractTest.java) | unsigned |
| `EmbabelAgentRuntime` | `modules/embabel` | kotlin | 15 | [card](modules/embabel/SKILLCARD.yaml) | [test](modules/embabel/src/test/kotlin/org/atmosphere/ai/embabel/EmbabelRuntimeContractTest.kt) | unsigned |
| `KoogAgentRuntime` | `modules/koog` | kotlin | 19 | [card](modules/koog/SKILLCARD.yaml) | [test](modules/koog/src/test/kotlin/org/atmosphere/ai/koog/KoogRuntimeContractTest.kt) | unsigned |
| `LangChain4jAgentRuntime` | `modules/langchain4j` | java | 17 | [card](modules/langchain4j/SKILLCARD.yaml) | [test](modules/langchain4j/src/test/java/org/atmosphere/ai/langchain4j/LangChain4jRuntimeContractTest.java) | unsigned |
| `SemanticKernelAgentRuntime` | `modules/semantic-kernel` | java | 15 | [card](modules/semantic-kernel/SKILLCARD.yaml) | [test](modules/semantic-kernel/src/test/java/org/atmosphere/ai/sk/SemanticKernelRuntimeContractTest.java) | unsigned |
| `SpringAiAgentRuntime` | `modules/spring-ai` | java | 17 | [card](modules/spring-ai/SKILLCARD.yaml) | [test](modules/spring-ai/src/test/java/org/atmosphere/ai/spring/SpringAiRuntimeContractTest.java) | unsigned |
| `SpringAiAlibabaAgentRuntime` | `modules/spring-ai-alibaba` | java | 16 | [card](modules/spring-ai-alibaba/SKILLCARD.yaml) | [test](modules/spring-ai-alibaba/src/test/java/org/atmosphere/ai/spring/alibaba/SpringAiAlibabaRuntimeContractTest.java) | unsigned |

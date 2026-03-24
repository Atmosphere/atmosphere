# Atmosphere CLI

Run samples, scaffold projects, and explore the Atmosphere framework from your terminal.

## Quick Install

```bash
curl -fsSL https://raw.githubusercontent.com/Atmosphere/atmosphere/main/cli/install.sh | sh
```

Or with Homebrew:

```bash
brew install Atmosphere/tap/atmosphere
```

## Usage

### Interactive Install (recommended)

```bash
atmosphere install              # Browse all samples, pick one
atmosphere install --tag ai     # Filter to AI samples
atmosphere install --category tools
```

The interactive picker shows samples grouped by category with descriptions. Pick a number, then choose to:
1. **Run it now** — downloads the pre-built JAR and starts it
2. **Install source code** — clones the sample source into your current directory

If [fzf](https://github.com/junegunn/fzf) is installed, you get a fuzzy-search picker instead of the numbered menu.

### List Samples

```bash
atmosphere list                # All samples
atmosphere list --tag ai       # AI samples only
atmosphere list --category tools
```

### Run a Sample

```bash
atmosphere run spring-boot-chat
atmosphere run spring-boot-ai-chat --env LLM_API_KEY=your-key
atmosphere run spring-boot-dentist-agent --port 9090
```

The CLI downloads pre-built JARs from GitHub Releases and caches them in `~/.atmosphere/cache/`.

### Create a New Project

```bash
atmosphere new my-chat-app
atmosphere new my-ai-app --template ai-chat
atmosphere new my-rag-app --template rag --group org.mycompany
```

Or with npx (zero install):

```bash
npx create-atmosphere-app my-chat-app
npx create-atmosphere-app my-ai-app --template ai-chat
```

### Sample Info

```bash
atmosphere info spring-boot-ai-chat
```

## Available Templates

| Template | Description |
|----------|-------------|
| `chat` | Basic real-time WebSocket chat |
| `ai-chat` | AI-powered streaming chat (OpenAI/Gemini/Ollama) |
| `ai-tools` | AI tool calling with LangChain4j |
| `rag` | RAG chat with vector store |
| `quarkus-chat` | Real-time chat with Quarkus |

## Requirements

- **Java 21+** — `brew install openjdk@21` or [SDKMAN](https://sdkman.io)
- **JBang** (optional, for full template support) — [jbang.dev/download](https://www.jbang.dev/download/)

## Architecture

```
cli/
├── atmosphere          # POSIX shell CLI script
├── samples.json        # Sample registry with metadata
├── install.sh          # curl | sh installer
├── npx/                # create-atmosphere-app npm package
│   ├── package.json
│   └── index.js
└── homebrew/
    └── atmosphere.rb   # Homebrew formula (for Atmosphere/homebrew-tap)
```

The CLI downloads sample fat JARs from GitHub Releases on first run and caches them in `~/.atmosphere/cache/v{version}/`. The release workflow (`.github/workflows/release-samples.yml`) builds all sample JARs automatically when a GitHub Release is published.

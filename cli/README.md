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
atmosphere new my-fleet --template multi-agent
atmosphere new my-classroom --template classroom
```

Available templates: `chat`, `ai-chat`, `ai-tools`, `mcp-server`, `rag`, `agent`, `koog`, `multi-agent`, `classroom`. Each template sparse-clones the matching sample from `cli/samples.json` into a directory you name.

Or with npx (zero install):

```bash
npx create-atmosphere-app my-chat-app
npx create-atmosphere-app my-ai-app --template ai-chat
```

### Sample Info

```bash
atmosphere info spring-boot-ai-chat
```

### Import a Skill from GitHub

Turn any skill file into a running Atmosphere agent:

```bash
# From Anthropic's official skills
atmosphere import https://github.com/anthropics/skills/blob/main/skills/frontend-design/SKILL.md

# From Antigravity community (1,200+ skills)
atmosphere import https://github.com/sickn33/antigravity-awesome-skills/blob/main/skills/customer-support/SKILL.md

# From a local file
atmosphere import ./my-skill.md

# Custom project name
atmosphere import --name my-agent https://github.com/anthropics/skills/blob/main/skills/pdf/SKILL.md

# Headless agent (A2A/MCP only, no WebSocket UI)
atmosphere import --headless https://example.com/SKILL.md --trust
```

The import command:
- Downloads the skill file (GitHub blob URLs auto-normalized to raw)
- Parses YAML frontmatter for `name:` and `description:`
- Extracts `## Tools` sections → generates `@AiTool` method stubs
- Scaffolds a complete Spring Boot project
- Places the skill at `META-INF/skills/{name}/SKILL.md` for auto-discovery

### Skills Registry

Browse and run curated skills from [atmosphere-skills](https://github.com/Atmosphere/atmosphere-skills):

```bash
atmosphere skills list                # List all skills
atmosphere skills search medical      # Search by keyword
atmosphere skills run dentist-agent   # Scaffold and run
```

### Trusted Sources

Remote imports are restricted to trusted GitHub organizations by default:

| Trusted Source | Repository |
|---------------|-----------|
| `Atmosphere/*` | [atmosphere-skills](https://github.com/Atmosphere/atmosphere-skills) |
| `anthropics/skills` | [Official Anthropic skills](https://github.com/anthropics/skills) |
| `sickn33/antigravity-awesome-skills` | [1,200+ community skills](https://github.com/sickn33/antigravity-awesome-skills) |
| `K-Dense-AI/*` | [Scientific/research skills](https://github.com/K-Dense-AI/claude-scientific-skills) |
| `agentskills/*` | [Agent Skills spec](https://github.com/agentskills/agentskills) |

To import from an untrusted source, use `--trust`:

```bash
atmosphere import --trust https://example.com/custom/SKILL.md
```

### Import Plugins

The `import` command supports plugins for custom project scaffolding targets. Plugins are shell scripts at `~/.atmosphere/plugins/import-<target>.sh`:

```bash
atmosphere import --target kotlin https://example.com/SKILL.md   # uses import-kotlin.sh
atmosphere plugins                                                # list installed plugins
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

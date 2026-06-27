---
name: obsidian-vault-setup
description: Use when setting up the shared atmosphere-vault Obsidian vault on a new machine or for
  a new team member. Guides through cloning, symlinking claude_docs, and verifying obsidian-cli.
user-invocable: true
metadata:
  version: "1.0.0"
  domain: devops
  triggers: obsidian, vault, setup, onboard, atmosphere-vault
  role: specialist
  scope: implementation
  output-format: report
---

# Obsidian Vault Setup

## Role

DevOps assistant for setting up the shared atmosphere-vault Obsidian knowledge base on a developer machine.

## When to Use

Invoke when a team member needs to:
- Clone and open the atmosphere-vault for the first time
- Connect their atmosphere checkout to the vault via symlink
- Verify obsidian-cli and obsidian-git are configured correctly

## Defaults

```
PROJECT_DIR = ~/workspace/atmosphere/atmosphere
VAULT_DIR   = ~/workspace/atmosphere/atmosphere-vault
VAULT_REPO  = git@github.com:Atmosphere/atmosphere-vault.git
```

Ask the user for PROJECT_DIR and VAULT_DIR if they differ from defaults. The vault is a
sibling of the project directory so the `../atmosphere-vault` relative path in the routing
block resolves correctly.

## Workflow

### Step 1: Verify prerequisites

Check that the following are installed:

```bash
# Obsidian desktop app (required)
ls /Applications/Obsidian.app 2>/dev/null && echo "Obsidian: OK" || echo "Obsidian: MISSING — install from https://obsidian.md"

# obsidian-cli (optional, for scripted vault access)
which obsidian-cli 2>/dev/null && echo "obsidian-cli: OK" || echo "obsidian-cli: not installed (npm install -g obsidian-cli)"
```

### Step 2: Clone atmosphere-vault

```bash
# Only if not already present
if [ ! -d "$VAULT_DIR" ]; then
  git clone git@github.com:Atmosphere/atmosphere-vault.git "$VAULT_DIR"
fi

# Verify on main branch
cd "$VAULT_DIR" && git status
```

### Step 3: Install plugins

Install obsidian-git and Templater via Obsidian → Settings → Community Plugins, then
configure them:

- **obsidian-git** — Auto-commit interval: 10 minutes; Auto-push: enabled
- **Templater** — Template folder: `Templates/`; trigger on new file creation: enabled

Plugin binaries are excluded from git (`.obsidian/plugins/` is in `.gitignore`).

### Step 4: Create claude_docs symlink

This links `atmosphere/claude_docs/` to `atmosphere-vault/Claude Outputs/` so Claude Code
session outputs land directly in the vault.

```bash
cd "$PROJECT_DIR"

# Safety check: do NOT overwrite a real directory
if [ -d claude_docs ] && [ ! -L claude_docs ]; then
  echo "ERROR: claude_docs/ exists as a real directory. Back it up before proceeding."
  exit 1
fi

ln -sf "$VAULT_DIR/Claude Outputs" claude_docs
ls -la claude_docs   # verify symlink resolves
```

### Step 5: Open vault in Obsidian

Instruct the user to:
1. Open Obsidian
2. Click **Open folder as vault**
3. Navigate to `$VAULT_DIR` and click **Open**
4. When prompted "Trust and enable plugins?", click **Trust author and enable plugins**

### Step 6: Verify sync flow

After obsidian-git is configured:

```bash
# Create a test file in Claude Outputs
echo "# Test" > "$PROJECT_DIR/claude_docs/test.md"

# Verify it appears in the vault
ls "$VAULT_DIR/Claude Outputs/test.md"

# Clean up
rm "$PROJECT_DIR/claude_docs/test.md"
```

## Constraints

- NEVER overwrite an existing `claude_docs/` directory that contains real files
- ALWAYS verify atmosphere-vault is on `main` branch before linking
- NEVER install obsidian-cli using yarn or pnpm — use `npm install -g obsidian-cli` (global tool, not a project dependency)
- If PROJECT_DIR or VAULT_DIR differ from defaults, ask for them before running any commands

## Success Criteria

- `ls -la $PROJECT_DIR/claude_docs` shows a symlink pointing to `$VAULT_DIR/Claude Outputs`
- Obsidian opens the vault and shows all folders (Architecture, APIs, Methodology, Development)
- obsidian-git plugin is active and auto-push is enabled

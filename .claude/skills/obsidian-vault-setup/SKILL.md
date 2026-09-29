---
name: obsidian-vault-setup
description: Use when setting up the shared atmosphere-vault Obsidian vault on a new machine or for
  a new team member. Guides through cloning, installing plugins, symlinking claude_docs, and
  verifying the Obsidian CLI.
user-invocable: true
metadata:
  version: "1.1.0"
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
- Verify the Obsidian CLI and the vault's commit timer are configured correctly

## Defaults

```
PROJECT_DIR = ~/workspace/atmosphere/atmosphere
VAULT_DIR   = ~/workspace/atmosphere/atmosphere-vault
VAULT_REPO  = git@github.com:Atmosphere/atmosphere-vault.git
```

Ask the user for PROJECT_DIR and VAULT_DIR if they differ from defaults. The vault is a
sibling of the project directory: the routing block reaches it as `../atmosphere-vault`,
and the vault's `scripts/sync-claude-memory.sh` reaches the checkout as `../atmosphere`.

## Workflow

### Step 1: Verify prerequisites

Check that the following are installed:

```bash
# Obsidian desktop app (required)
ls /Applications/Obsidian.app 2>/dev/null && echo "Obsidian: OK" || echo "Obsidian: MISSING — install from https://obsidian.md"

# The app's own CLI (Settings → General → enable the command line interface)
command -v obsidian   # must print /Applications/Obsidian.app/Contents/MacOS/obsidian
```

If `command -v obsidian` prints an npm path, the unrelated `obsidian-cli` npm package is
shadowing the real CLI — `npm uninstall -g obsidian-cli`.

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

Downloads obsidian-git and Templater and writes their pre-configured settings.

```bash
cd "$VAULT_DIR"
./scripts/install-plugins.sh
```

This script:
- Downloads `main.js` + `manifest.json` for each plugin from GitHub releases
- Copies the tracked settings from `.obsidian/plugin-configs/` into each plugin's
  `data.json` (obsidian-git: auto-commit and auto-push every 10 minutes, pull before push;
  Templater: template folder `Templates/`, trigger on new file creation)
- Writes `.obsidian/community-plugins.json` to enable both plugins

Plugin binaries are excluded from git (`.obsidian/plugins/` is in `.gitignore`).
Re-running the script is safe — it overwrites existing files idempotently.

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

The vault's commit timer is obsidian-git's, configured by Step 3 to commit and push every
10 minutes. Run exactly one per machine: if the machine already runs another commit timer of
its own for the vault (nothing in this repo or the vault ships one), set obsidian-git's
auto-commit and auto-push intervals to 0 there.

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
- NEVER install the npm `obsidian-cli` package — it shadows the app's own `obsidian` CLI
- If PROJECT_DIR or VAULT_DIR differ from defaults, ask for them before running any commands

## Success Criteria

- `ls -la $PROJECT_DIR/claude_docs` shows a symlink pointing to `$VAULT_DIR/Claude Outputs`
- Obsidian opens the vault and shows all folders (Architecture, APIs, Methodology, Development)
- `command -v obsidian` resolves to the app bundle
- Exactly one commit timer commits into the vault on this machine (Step 5)

#!/usr/bin/env node
// create-atmosphere-app — npx launcher for Atmosphere project scaffolding
// Delegates to the `atmosphere` CLI's `new` command.
// Usage: npx create-atmosphere-app [project-name] [options]
//
// Copyright 2008-2026 Async-IO.org — Apache License 2.0

'use strict';

const { execSync, spawnSync } = require('child_process');
const fs = require('fs');

const VERSION = require('./package.json').version;
const BOLD = '\x1b[1m';
const CYAN = '\x1b[36m';
const GREEN = '\x1b[32m';
const YELLOW = '\x1b[33m';
const RED = '\x1b[31m';
const RESET = '\x1b[0m';

// Keep this list in sync with the shell CLI's `cmd_new` template map in
// cli/atmosphere. Every entry must resolve to a sample in cli/samples.json.
const TEMPLATES = {
  'chat':        'Real-time WebSocket chat',
  'ai-chat':     'AI streaming chat (Spring AI / LangChain4j / Gemini / Ollama)',
  'ai-tools':    'AI chat with @AiTool function calling',
  'mcp-server':  'MCP server exposing tools, resources, and prompts',
  'rag':         'RAG chat with vector store',
  'agent':       '@Agent skill-file driven agent (Dr. Molar dentist demo)',
  'koog':            'Koog @AIAgent chat integration',
  'embabel':         'Embabel GOAP planning (Kotlin, SB 3.5)',
  'multi-agent':     'Multi-agent fleet — 5 independent @Agents via A2A protocol',
  'classroom':       'AI classroom — shared streaming responses (web + Expo RN)',
};

function die(msg) { console.error(`${RED}error:${RESET} ${msg}`); process.exit(1); }
function warn(msg) { console.error(`${YELLOW}!${RESET} ${msg}`); }

function printHelp() {
  console.log(`
${BOLD}create-atmosphere-app${RESET} v${VERSION}

${BOLD}Usage:${RESET}
  npx create-atmosphere-app <project-name> [options]

${BOLD}Options:${RESET}
  --template, -t <name>     Template to use (default: chat)
  --runtime, -r <name>      AI runtime adapter to inject (builtin | spring-ai |
                            langchain4j | adk | koog | embabel | semantic-kernel)
  --skill-file, -s <path>   Scaffold an @Agent that loads this skill file
  --list-templates          List available templates
  --help, -h                Show this help

${BOLD}Templates:${RESET}`);
  for (const [name, desc] of Object.entries(TEMPLATES)) {
    console.log(`  ${GREEN}${name.padEnd(16)}${RESET} ${desc}`);
  }
  console.log(`
${BOLD}Examples:${RESET}
  npx create-atmosphere-app my-chat
  npx create-atmosphere-app my-ai-app --template ai-chat
  npx create-atmosphere-app my-fleet --template multi-agent
  npx create-atmosphere-app my-classroom --template classroom

${BOLD}Requires:${RESET} the ${BOLD}atmosphere${RESET} CLI on PATH.
  Install: ${CYAN}brew install Atmosphere/tap/atmosphere${RESET}
  Or:      ${CYAN}curl -fsSL https://raw.githubusercontent.com/Atmosphere/atmosphere/main/cli/install.sh | sh${RESET}
`);
}

function hasCommand(cmd) {
  try {
    execSync(`command -v ${cmd}`, { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
}

function main() {
  const args = process.argv.slice(2);
  let projectName = null;
  let template = 'chat';
  let skillFile = null;
  let runtime = null;

  for (let i = 0; i < args.length; i++) {
    switch (args[i]) {
      case '--help': case '-h':
        printHelp();
        process.exit(0);
        break;
      case '--list-templates':
        console.log(`\n${BOLD}Available templates:${RESET}\n`);
        for (const [name, desc] of Object.entries(TEMPLATES)) {
          console.log(`  ${GREEN}${name.padEnd(16)}${RESET} ${desc}`);
        }
        console.log('');
        process.exit(0);
        break;
      case '--template': case '-t':
        template = args[++i];
        if (!template) die('--template requires a value');
        break;
      case '--runtime': case '-r':
        runtime = args[++i];
        if (!runtime) die('--runtime requires a value');
        break;
      case '--skill-file': case '-s':
        skillFile = args[++i];
        if (!skillFile) die('--skill-file requires a value');
        break;
      case '--group': case '-g':
        warn('--group is no longer supported. Samples ship with their own groupId.');
        i++;
        break;
      default:
        if (args[i].startsWith('-')) die(`Unknown option: ${args[i]}`);
        if (projectName !== null) die(`Unexpected argument: ${args[i]}`);
        projectName = args[i];
    }
  }

  if (!projectName) {
    printHelp();
    die('Project name is required');
  }

  if (!TEMPLATES[template]) {
    die(`Unknown template: ${template}. Use --list-templates to see options.`);
  }

  if (fs.existsSync(projectName)) {
    die(`Directory '${projectName}' already exists`);
  }

  if (!hasCommand('atmosphere')) {
    console.error(`${RED}error:${RESET} the ${BOLD}atmosphere${RESET} CLI is required but is not on PATH.`);
    console.error('');
    console.error('Install it with one of:');
    console.error(`  ${CYAN}brew install Atmosphere/tap/atmosphere${RESET}`);
    console.error(`  ${CYAN}curl -fsSL https://raw.githubusercontent.com/Atmosphere/atmosphere/main/cli/install.sh | sh${RESET}`);
    console.error('');
    console.error(`Then re-run: ${BOLD}npx create-atmosphere-app ${projectName} --template ${template}${RESET}`);
    process.exit(1);
  }

  console.log(`\n${BOLD}Creating Atmosphere project:${RESET} ${projectName}\n`);

  const cliArgs = ['new', projectName, '--template', template];
  if (skillFile) cliArgs.push('--skill-file', skillFile);
  if (runtime) cliArgs.push('--runtime', runtime);

  const result = spawnSync('atmosphere', cliArgs, { stdio: 'inherit' });
  if (result.error) {
    die(`failed to exec atmosphere CLI: ${result.error.message}`);
  }
  if (result.status !== 0) {
    process.exit(result.status || 1);
  }
}

main();

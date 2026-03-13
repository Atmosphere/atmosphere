#!/usr/bin/env node
// create-atmosphere-app — npx launcher for Atmosphere project scaffolding
// Usage: npx create-atmosphere-app [project-name] [options]
//
// Copyright 2008-2026 Async-IO.org — Apache License 2.0

'use strict';

const { execSync, spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

const VERSION = '4.0.14';
const BOLD = '\x1b[1m';
const CYAN = '\x1b[36m';
const GREEN = '\x1b[32m';
const YELLOW = '\x1b[33m';
const RED = '\x1b[31m';
const RESET = '\x1b[0m';

const TEMPLATES = {
  'chat':           'Basic real-time WebSocket chat',
  'ai-chat':        'AI-powered streaming chat (OpenAI/Gemini/Ollama)',
  'ai-tools':       'AI tool calling with LangChain4j',
  'rag':            'RAG chat with vector store',
  'quarkus-chat':   'Real-time chat with Quarkus',
};

function info(msg)  { console.log(`${CYAN}→${RESET} ${msg}`); }
function ok(msg)    { console.log(`${GREEN}✓${RESET} ${msg}`); }
function warn(msg)  { console.log(`${YELLOW}!${RESET} ${msg}`); }
function die(msg)   { console.error(`${RED}error:${RESET} ${msg}`); process.exit(1); }

function printHelp() {
  console.log(`
${BOLD}create-atmosphere-app${RESET} v${VERSION}

${BOLD}Usage:${RESET}
  npx create-atmosphere-app <project-name> [options]

${BOLD}Options:${RESET}
  --template, -t <name>   Template to use (default: chat)
  --group, -g <id>        Maven groupId (default: com.example)
  --list-templates        List available templates
  --help, -h              Show this help

${BOLD}Templates:${RESET}`);
  for (const [name, desc] of Object.entries(TEMPLATES)) {
    console.log(`  ${GREEN}${name.padEnd(18)}${RESET} ${desc}`);
  }
  console.log(`
${BOLD}Examples:${RESET}
  npx create-atmosphere-app my-chat
  npx create-atmosphere-app my-ai-app --template ai-chat
  npx create-atmosphere-app my-rag-app -t rag -g org.mycompany
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

function hasJava21() {
  try {
    const output = execSync('java -version 2>&1', { encoding: 'utf8' });
    const match = output.match(/"(\d+)/);
    return match && parseInt(match[1], 10) >= 21;
  } catch {
    return false;
  }
}

function tryJBang(projectName, template, groupId) {
  if (!hasCommand('jbang')) return false;

  info('Using JBang generator for full template support...');

  const jbangUrl = 'https://raw.githubusercontent.com/Atmosphere/atmosphere/main/generator/AtmosphereInit.java';
  const args = [jbangUrl, '--name', projectName, '--group', groupId, '--template', template];

  try {
    execSync(`jbang ${args.join(' ')}`, { stdio: 'inherit' });
    return true;
  } catch {
    return false;
  }
}

function tryAtmosphereCli(projectName, template, groupId) {
  if (!hasCommand('atmosphere')) return false;

  info('Using Atmosphere CLI...');
  try {
    execSync(`atmosphere new ${projectName} --template ${template} --group ${groupId}`, { stdio: 'inherit' });
    return true;
  } catch {
    return false;
  }
}

function createMinimalProject(projectName, template, groupId) {
  info('Creating minimal project (install JBang for full templates)...');

  const artifactId = path.basename(projectName);
  const pkgPath = groupId.replace(/\./g, '/');
  const srcDir = path.join(projectName, 'src/main/java', pkgPath);
  const resDir = path.join(projectName, 'src/main/resources');

  fs.mkdirSync(srcDir, { recursive: true });
  fs.mkdirSync(resDir, { recursive: true });

  // Determine dependencies based on template
  let extraDeps = '';
  let extraConfig = '';

  if (template === 'ai-chat' || template === 'ai-tools') {
    extraDeps = `
        <dependency>
            <groupId>org.atmosphere</groupId>
            <artifactId>atmosphere-ai</artifactId>
            <version>\${atmosphere.version}</version>
        </dependency>`;
  }
  if (template === 'rag') {
    extraDeps = `
        <dependency>
            <groupId>org.atmosphere</groupId>
            <artifactId>atmosphere-ai</artifactId>
            <version>\${atmosphere.version}</version>
        </dependency>
        <dependency>
            <groupId>org.atmosphere</groupId>
            <artifactId>atmosphere-rag</artifactId>
            <version>\${atmosphere.version}</version>
        </dependency>`;
  }

  const pom = `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.2</version>
    </parent>

    <groupId>${groupId}</groupId>
    <artifactId>${artifactId}</artifactId>
    <version>0.1.0-SNAPSHOT</version>

    <properties>
        <java.version>21</java.version>
        <atmosphere.version>${VERSION}</atmosphere.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.atmosphere</groupId>
            <artifactId>atmosphere-spring-boot-starter</artifactId>
            <version>\${atmosphere.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>${extraDeps}
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>`;

  const appJava = `package ${groupId};

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ChatApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }
}`;

  const appYml = `server:
  port: 8080

atmosphere:
  packages: ${groupId}
`;

  fs.writeFileSync(path.join(projectName, 'pom.xml'), pom);
  fs.writeFileSync(path.join(srcDir, 'ChatApplication.java'), appJava);
  fs.writeFileSync(path.join(resDir, 'application.yml'), appYml);

  // Copy Maven wrapper if we can find one
  if (hasCommand('mvn')) {
    try {
      execSync(`cd ${projectName} && mvn wrapper:wrapper -Dmaven=3.9.9`, { stdio: 'ignore' });
    } catch { /* ignore */ }
  }

  return true;
}

// ── Main ──────────────────────────────────────────────────────────────────

function main() {
  const args = process.argv.slice(2);
  let projectName = null;
  let template = 'chat';
  let groupId = 'com.example';

  for (let i = 0; i < args.length; i++) {
    switch (args[i]) {
      case '--help': case '-h':
        printHelp();
        process.exit(0);
      case '--list-templates':
        console.log(`\n${BOLD}Available templates:${RESET}\n`);
        for (const [name, desc] of Object.entries(TEMPLATES)) {
          console.log(`  ${GREEN}${name.padEnd(18)}${RESET} ${desc}`);
        }
        console.log('');
        process.exit(0);
      case '--template': case '-t':
        template = args[++i];
        if (!template) die('--template requires a value');
        break;
      case '--group': case '-g':
        groupId = args[++i];
        if (!groupId) die('--group requires a value');
        break;
      default:
        if (args[i].startsWith('-')) die(`Unknown option: ${args[i]}`);
        projectName = args[i];
    }
  }

  if (!projectName) {
    printHelp();
    die('Project name is required');
  }

  if (fs.existsSync(projectName)) {
    die(`Directory '${projectName}' already exists`);
  }

  if (!TEMPLATES[template]) {
    die(`Unknown template: ${template}. Use --list-templates to see options.`);
  }

  console.log(`\n${BOLD}Creating Atmosphere project:${RESET} ${projectName}\n`);

  // Try in order: JBang > Atmosphere CLI > minimal fallback
  const created = tryJBang(projectName, template, groupId)
    || tryAtmosphereCli(projectName, template, groupId)
    || createMinimalProject(projectName, template, groupId);

  if (!created) {
    die('Failed to create project');
  }

  ok(`Project created: ${projectName}/`);

  if (!hasJava21()) {
    warn('Java 21+ not found — you\'ll need it to build and run');
    console.log(`\n  Install: brew install openjdk@21`);
    console.log(`  Or use SDKMAN: https://sdkman.io\n`);
  }

  console.log(`\n${BOLD}Next steps:${RESET}\n`);
  console.log(`  cd ${projectName}`);
  console.log(`  ./mvnw spring-boot:run`);
  console.log(`\n  Then open ${CYAN}http://localhost:8080${RESET}\n`);
}

main();

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

  const staticDir = path.join(resDir, 'static');
  fs.mkdirSync(staticDir, { recursive: true });

  fs.writeFileSync(path.join(projectName, 'pom.xml'), pom);
  fs.writeFileSync(path.join(srcDir, 'ChatApplication.java'), appJava);
  fs.writeFileSync(path.join(resDir, 'application.yml'), appYml);

  // Generate handler files based on template
  if (template === 'ai-chat') {
    fs.writeFileSync(path.join(srcDir, 'AiChat.java'), `package ${groupId};

import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.StreamingSession;

@AiEndpoint(path = "/ai/chat",
            systemPrompt = "You are a helpful assistant.",
            conversationMemory = true)
public class AiChat {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
`);
  } else {
    // Default chat handler + Message + encoders
    fs.writeFileSync(path.join(srcDir, 'Message.java'), `package ${groupId};

public class Message {
    private String message;
    private String author;
    private long time;

    public Message() { this("", ""); }

    public Message(String author, String message) {
        this.author = author;
        this.message = message;
        this.time = System.currentTimeMillis();
    }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public long getTime() { return time; }
    public void setTime(long time) { this.time = time; }
}
`);

    fs.writeFileSync(path.join(srcDir, 'JacksonEncoder.java'), `package ${groupId};

import tools.jackson.databind.ObjectMapper;
import org.atmosphere.config.managed.Encoder;

public class JacksonEncoder implements Encoder<Message, String> {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String encode(Message m) {
        return mapper.writeValueAsString(m);
    }
}
`);

    fs.writeFileSync(path.join(srcDir, 'JacksonDecoder.java'), `package ${groupId};

import tools.jackson.databind.ObjectMapper;
import org.atmosphere.config.managed.Decoder;

public class JacksonDecoder implements Decoder<String, Message> {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Message decode(String s) {
        return mapper.readValue(s, Message.class);
    }
}
`);

    fs.writeFileSync(path.join(srcDir, 'Chat.java'), `package ${groupId};

import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Inject;

@ManagedService(path = "/atmosphere/chat")
public class Chat {

    private final Logger logger = LoggerFactory.getLogger(Chat.class);

    @Inject
    private AtmosphereResource r;

    @Inject
    private AtmosphereResourceEvent event;

    @Ready
    public void onReady() {
        logger.info("Client {} connected via {}", r.uuid(), r.transport());
    }

    @Disconnect
    public void onDisconnect() {
        logger.info("Client {} disconnected", event.getResource().uuid());
    }

    @org.atmosphere.config.service.Message(
            encoders = {JacksonEncoder.class},
            decoders = {JacksonDecoder.class})
    public Message onMessage(Message message) {
        logger.info("{}: {}", message.getAuthor(), message.getMessage());
        return message;
    }
}
`);
  }

  // index.html — minimal but functional chat UI
  const wsPath = template === 'ai-chat' ? '/ai/chat' : '/atmosphere/chat';
  const title = template === 'ai-chat' ? 'AI Chat' : 'Chat';
  fs.writeFileSync(path.join(staticDir, 'index.html'), generateIndexHtml(title, wsPath));

  // Copy Maven wrapper if we can find one
  if (hasCommand('mvn')) {
    try {
      execSync(`cd ${projectName} && mvn wrapper:wrapper -Dmaven=3.9.9`, { stdio: 'ignore' });
    } catch { /* ignore */ }
  }

  return true;
}

function generateIndexHtml(title, wsPath) {
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${title} — Atmosphere</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: system-ui, -apple-system, sans-serif; background: #0f172a; color: #e2e8f0; height: 100vh; display: flex; flex-direction: column; }
  header { padding: 1rem 1.5rem; border-bottom: 1px solid #1e293b; display: flex; align-items: center; gap: 0.75rem; }
  header h1 { font-size: 1.25rem; font-weight: 600; }
  header span { font-size: 0.75rem; color: #64748b; background: #1e293b; padding: 0.25rem 0.5rem; border-radius: 9999px; }
  #messages { flex: 1; overflow-y: auto; padding: 1.5rem; display: flex; flex-direction: column; gap: 0.75rem; }
  .msg { max-width: 70%; padding: 0.75rem 1rem; border-radius: 1rem; line-height: 1.5; }
  .msg.mine { background: #3b82f6; align-self: flex-end; border-bottom-right-radius: 0.25rem; }
  .msg.theirs { background: #1e293b; align-self: flex-start; border-bottom-left-radius: 0.25rem; }
  .msg .author { font-size: 0.75rem; color: #94a3b8; margin-bottom: 0.25rem; }
  .msg.mine .author { color: #bfdbfe; }
  #input-bar { padding: 1rem 1.5rem; border-top: 1px solid #1e293b; display: flex; gap: 0.75rem; }
  #author { width: 120px; padding: 0.625rem 0.75rem; background: #1e293b; border: 1px solid #334155; border-radius: 0.5rem; color: #e2e8f0; font-size: 0.875rem; }
  #msg { flex: 1; padding: 0.625rem 0.75rem; background: #1e293b; border: 1px solid #334155; border-radius: 0.5rem; color: #e2e8f0; font-size: 0.875rem; }
  #send { padding: 0.625rem 1.25rem; background: #3b82f6; color: white; border: none; border-radius: 0.5rem; font-weight: 600; cursor: pointer; }
  #send:hover { background: #2563eb; }
  #send:disabled { background: #334155; cursor: not-allowed; }
  #status { font-size: 0.75rem; padding: 0.25rem 1.5rem; color: #64748b; }
</style>
</head>
<body>
  <header><h1>${title}</h1><span>Atmosphere</span></header>
  <div id="status">Connecting...</div>
  <div id="messages"></div>
  <div id="input-bar">
    <input id="author" placeholder="Your name" />
    <input id="msg" placeholder="Type a message..." />
    <button id="send" disabled>Send</button>
  </div>
<script>
(function() {
  var messages = document.getElementById('messages');
  var msgInput = document.getElementById('msg');
  var authorInput = document.getElementById('author');
  var sendBtn = document.getElementById('send');
  var status = document.getElementById('status');
  var ws;
  function connect() {
    var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    ws = new WebSocket(proto + '//' + location.host + '${wsPath}');
    ws.onopen = function() { status.textContent = 'Connected'; sendBtn.disabled = false; };
    ws.onmessage = function(e) {
      try { var d = JSON.parse(e.data); if (d.author || d.message) addMsg(d); } catch(err) {}
    };
    ws.onclose = function() { status.textContent = 'Disconnected — reconnecting...'; sendBtn.disabled = true; setTimeout(connect, 2000); };
  }
  function addMsg(d) {
    var mine = d.author === authorInput.value;
    var div = document.createElement('div');
    div.className = 'msg ' + (mine ? 'mine' : 'theirs');
    div.innerHTML = '<div class="author">' + esc(d.author) + '</div>' + esc(d.message);
    messages.appendChild(div);
    messages.scrollTop = messages.scrollHeight;
  }
  function esc(s) { var d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }
  function send() {
    var a = authorInput.value.trim() || 'Anonymous';
    var m = msgInput.value.trim();
    if (!m || !ws || ws.readyState !== 1) return;
    ws.send(JSON.stringify({ author: a, message: m }));
    msgInput.value = '';
    msgInput.focus();
  }
  sendBtn.onclick = send;
  msgInput.onkeydown = function(e) { if (e.key === 'Enter') send(); };
  authorInput.value = 'User-' + Math.floor(Math.random() * 1000);
  connect();
})();
</script>
</body>
</html>`;
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

import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { WebSocket } from 'ws';
import * as fs from 'fs';
import * as path from 'path';

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-dentist-agent']);
});

test.afterAll(async () => {
  await server?.stop();
});

/** Send a message via WebSocket and collect the streamed response. */
function sendAndCollect(
  baseUrl: string,
  path: string,
  message: string,
  timeoutMs = 20_000,
): Promise<{ texts: string[]; fullText: string }> {
  return new Promise((resolve, reject) => {
    const wsUrl = baseUrl.replace('http://', 'ws://') + path;
    const ws = new WebSocket(wsUrl);
    const texts: string[] = [];
    let opened = false;
    const timer = setTimeout(() => {
      ws.close();
      resolve({ texts, fullText: texts.join('') });
    }, timeoutMs);

    ws.on('open', () => { opened = true; ws.send(message); });
    ws.on('message', (data) => {
      const raw = data.toString();
      const parts = raw.split('|');
      for (const part of parts) {
        const trimmed = part.trim();
        if (trimmed && !trimmed.match(/^\d+$/) && trimmed !== 'X') {
          texts.push(trimmed);
        }
      }
    });
    ws.on('close', () => { clearTimeout(timer); resolve({ texts, fullText: texts.join('') }); });
    ws.on('error', (err) => {
      clearTimeout(timer);
      if (!opened) reject(new Error(`WebSocket failed: ${err.message}`));
      else resolve({ texts, fullText: texts.join('') });
    });
  });
}

test.describe('Dentist Agent', () => {
  // ── Agent registration ──

  test('agent registered at /atmosphere/agent/dentist', async () => {
    const res = await fetch(`${server.baseUrl}/atmosphere/agent/dentist`);
    expect(res.status).not.toBe(404);
  });

  test('server logs confirm agent with tools and commands', () => {
    const output = server.getOutput();
    expect(output).toContain("Agent 'dentist' registered");
    expect(output).toContain('commands: 3');
    expect(output).toContain('tools: 2');
  });

  test('MCP endpoint registered', () => {
    const output = server.getOutput();
    expect(output).toContain('/atmosphere/agent/dentist/mcp');
  });

  // ── Slash commands ──

  test('/firstaid returns first-aid steps', async () => {
    const result = await sendAndCollect(server.baseUrl,
      '/atmosphere/agent/dentist', '/firstaid', 10_000);
    expect(result.fullText).toContain('Broken Tooth First Aid');
    expect(result.fullText).toContain('Rinse your mouth');
    expect(result.fullText).toContain('cold compress');
  });

  test('/urgency returns triage guidance', async () => {
    const result = await sendAndCollect(server.baseUrl,
      '/atmosphere/agent/dentist', '/urgency', 10_000);
    expect(result.fullText).toContain('GO TO ER NOW');
    expect(result.fullText).toContain('SEE DENTIST TODAY');
    expect(result.fullText).toContain('SEE DENTIST WITHIN');
  });

  test('/pain returns pain management tips', async () => {
    const result = await sendAndCollect(server.baseUrl,
      '/atmosphere/agent/dentist', '/pain', 10_000);
    expect(result.fullText).toContain('Pain Management');
    expect(result.fullText).toContain('ibuprofen');
    expect(result.fullText.toLowerCase()).toContain('cold compress');
  });

  // ── Streaming response (demo mode — no API key) ──

  test('streams a response to a dental question', async () => {
    const result = await sendAndCollect(server.baseUrl,
      '/atmosphere/agent/dentist', 'I chipped my tooth', 15_000);
    expect(result.texts.length).toBeGreaterThan(0);
    expect(result.fullText.length).toBeGreaterThan(10);
  });

  // ── Console UI ──

  test('console page loads', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
  });

  // ── Channel webhooks registered ──

  test('telegram and slack webhooks registered', () => {
    const output = server.getOutput();
    expect(output).toContain('Registered telegram channel at /webhook/telegram');
    expect(output).toContain('Registered slack channel at /webhook/slack');
  });

  // ── Behavioral depth tests ──

  test('unknown slash command returns help text', async () => {
    const result = await sendAndCollect(server.baseUrl,
      '/atmosphere/agent/dentist', '/unknown', 10_000);
    // The agent should gracefully handle unknown commands by returning
    // help information or listing available commands
    const text = result.fullText.toLowerCase();
    expect(
      text.includes('help') ||
      text.includes('available') ||
      text.includes('command') ||
      text.includes('firstaid') ||
      text.includes('unknown'),
    ).toBe(true);
    expect(result.fullText.length).toBeGreaterThan(0);
  });

  test('/help lists all available commands', async () => {
    const result = await sendAndCollect(server.baseUrl,
      '/atmosphere/agent/dentist', '/help', 10_000);
    const text = result.fullText.toLowerCase();
    // Should list the 3 known commands
    expect(text).toContain('firstaid');
    expect(text).toContain('urgency');
    expect(text).toContain('pain');
  });

  // ── Agent Card fidelity (Phase 3) ──

  /**
   * Parse the {@code ## Skills} bullet list from a SKILL.md file the exact same
   * way {@code SkillFileParser.listItems("Skills")} does on the server: pick
   * section lines starting with "- " and strip the prefix. Mirrors
   * {@code modules/agent/src/main/java/org/atmosphere/agent/skill/SkillFileParser.java}.
   */
  function parseSkillMdSection(contents: string, sectionName: string): string[] {
    const lines = contents.split('\n');
    const items: string[] = [];
    let inSection = false;
    let inCodeBlock = false;
    for (const line of lines) {
      const trimmed = line.trim();
      if (trimmed.startsWith('```')) {
        inCodeBlock = !inCodeBlock;
        continue;
      }
      if (inCodeBlock) continue;
      if (line.startsWith('## ')) {
        inSection = line.substring(3).trim() === sectionName;
        continue;
      }
      if (inSection && trimmed.startsWith('- ')) {
        items.push(trimmed.substring(2).trim());
      }
    }
    return items;
  }

  test('Agent Card skills match SKILL.md bullets and @Command registrations', async () => {
    // Fetch the real Agent Card served at /.well-known/agent.json. The Spring
    // Boot starter's WellKnownAgentFilter emits either a single card object
    // (when only one A2A handler is registered) or a JSON array (when more
    // than one is registered) — the dentist sample has exactly one @Agent, so
    // we expect the single-object form, but accept either shape to insulate
    // the assertion from sample plumbing churn.
    const res = await fetch(`${server.baseUrl}/.well-known/agent.json`);
    expect(res.status).toBe(200);
    const body = await res.json() as Record<string, unknown>
      | Array<Record<string, unknown>>;

    const cards: Array<Record<string, unknown>> = Array.isArray(body) ? body : [body];
    const dentistCard = cards.find((c) => c.name === 'dentist');
    expect(dentistCard, 'expected a dentist card in /.well-known/agent.json').toBeDefined();

    const skills = dentistCard!.skills as Array<{
      id: string;
      name: string;
      description: string;
      tags?: string[];
    }>;
    expect(Array.isArray(skills)).toBe(true);

    // Read the real SKILL.md from the classpath source on disk — the same file
    // the server parses at startup via SkillFileParser. Path is relative to
    // the spec file (modules/integration-tests/e2e → modules/skills/...).
    const skillMdPath = path.resolve(
      __dirname,
      '../../skills/src/main/resources/META-INF/skills/dentist-agent/SKILL.md',
    );
    const skillMdContents = fs.readFileSync(skillMdPath, 'utf8');
    const expectedSkillBullets = parseSkillMdSection(skillMdContents, 'Skills');

    // Sanity — the SKILL.md must actually have a Skills section; otherwise
    // the assertion below would trivially pass and hide drift.
    expect(expectedSkillBullets.length).toBeGreaterThan(0);

    // AgentProcessor.buildSkills splits skills into two groups:
    //  1. One entry per "## Skills" bullet, tags = [] (no "command" tag).
    //  2. One entry per @Command method, tagged "command".
    // Partition the card's skills list the same way and compare exactly.
    const commandSkills = skills.filter((s) => (s.tags ?? []).includes('command'));
    const markdownSkills = skills.filter((s) => !(s.tags ?? []).includes('command'));

    // Every markdown skill's `name` field must match a SKILL.md bullet, and
    // the counts must match exactly — no drift in either direction.
    const markdownSkillNames = markdownSkills.map((s) => s.name).sort();
    const expectedSkillNamesSorted = [...expectedSkillBullets].sort();
    expect(markdownSkillNames).toEqual(expectedSkillNamesSorted);

    // AgentProcessor slugifies each bullet into the skill id via
    // text.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "").
    // Mirror Java's replaceAll("^-|-$", "") exactly — note the single
    // leading/trailing dash, not "-+", since [^a-z0-9]+ already collapses runs
    // of non-alphanumerics into a single dash.
    const slugify = (text: string) =>
      text.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
    for (const bullet of expectedSkillBullets) {
      const match = markdownSkills.find((s) => s.name === bullet);
      expect(match, `missing markdown skill for bullet "${bullet}"`).toBeDefined();
      expect(match!.id).toBe(slugify(bullet));
      // AgentProcessor passes the bullet text as both name and description.
      expect(match!.description).toBe(bullet);
    }

    // The @Command-tagged skills must match the 3 known dentist commands.
    // This pins the spec's "commands: 3" log assertion to the actual card.
    const commandSkillNames = commandSkills.map((s) => s.name).sort();
    expect(commandSkillNames).toEqual(['/firstaid', '/pain', '/urgency']);
    // Command skill ids use the "command" + prefix.replace("/", "_") convention.
    for (const cmd of commandSkills) {
      expect(cmd.id).toBe('command_' + cmd.name.substring(1));
      expect((cmd.tags ?? [])).toEqual(['command']);
    }

    // Total skill count = markdown bullets + @Command methods, nothing extra.
    expect(skills.length).toBe(expectedSkillBullets.length + commandSkills.length);
  });
});

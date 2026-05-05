import { test } from '@playwright/test';
import { startSample, SAMPLES, type SampleConfig, type SampleServer } from './fixtures/sample-server';
import { assertCrossTabIsolation, type CrossTabIsolationOptions } from './fixtures/cross-tab-isolation';

/**
 * Cross-tab isolation regression matrix.
 *
 * <p>Every {@code @AiEndpoint} or {@code @Agent} sample must keep two open
 * Console tabs isolated — tab A's prompt must never reach tab B. This pins
 * the regression class fixed in commit 1fbb0958f0
 * ({@code fix(ai): isolate @AiEndpoint prompts to the originating client}).
 * Pre-fix, {@code AiEndpointHandler.onRequest} called
 * {@code broadcaster.broadcast(msg)} on every POST/frame, fanning the
 * prompt out to every subscriber on the per-path broadcaster — so two tabs
 * meant two LLM invocations and tab B receiving tab A's response.</p>
 *
 * <p>The matrix below is intentionally exhaustive: a per-sample test means a
 * regression in any single sample (or a future sample that ships a custom
 * dispatch path) lights up its own row in CI rather than hiding behind
 * "this one sample broke." The shared {@link assertCrossTabIsolation}
 * helper drives all of them through identical Console UI steps.</p>
 *
 * <p><b>Out of scope:</b> samples whose endpoint is intentionally
 * broadcast-shared ({@code @ManagedService} chats — {@code spring-boot-chat},
 * {@code spring-boot-mcp-server}, {@code spring-boot-otel-chat},
 * {@code spring-boot-channels-chat}, {@code spring-boot-ai-classroom},
 * {@code chat}, {@code embedded-jetty-chat}, {@code quarkus-chat}). Their
 * fan-out is the design, not a bug; the multi-client broadcast contract is
 * pinned by {@code WAsyncChatIntegrationTest} and {@code multi-client.spec}.</p>
 */

interface IsolationCase {
  sampleKey: keyof typeof SAMPLES;
  /** Human-readable description of the sample's endpoint shape. */
  shape: string;
  /** Per-sample overrides for the helper. */
  opts?: CrossTabIsolationOptions;
  /**
   * Per-case env override merged on top of the SAMPLES fixture's env. Use
   * to disable auth for the isolation test on samples whose shared fixture
   * forces auth on for unrelated specs (notably {@code spring-boot-ai-chat}
   * which is wired into auth-token / oauth-jwt tests). The Console UI
   * doesn't supply an auth header, and reusing the admin-dashboard's
   * WebSocket-patching init script for every sample would be a fragile
   * cross-cutting concern; turning auth off matches the sample's OOTB
   * default and keeps the test focused on the regression.
   */
  envOverride?: Record<string, string>;
}

const cases: IsolationCase[] = [
  // Default Spring Boot AI chat — @AiEndpoint at /atmosphere/ai-chat. The
  // canonical reproducer for the regression: this sample's two-tab leak in
  // chrome-devtools triggered the original investigation.
  {
    sampleKey: 'spring-boot-ai-chat',
    shape: '@AiEndpoint /atmosphere/ai-chat (built-in runtime)',
    envOverride: { ATMOSPHERE_AUTH_ENABLED: 'false' },
  },

  // RAG @Agent — broadcaster is /atmosphere/agent/rag-assistant. Different
  // path template than ai-chat, same dispatch path through AiEndpointHandler.
  { sampleKey: 'spring-boot-rag-chat', shape: '@Agent rag-assistant with @AiTool dispatch' },

  // @AiEndpoint with @AiTool registry. The unused @PathParam("room") field
  // was removed in the same commit (the path template lacked a {room}
  // segment, so room was always null) — kept here as a smoke that the
  // sample still boots cleanly post-cleanup.
  { sampleKey: 'spring-boot-ai-tools', shape: '@AiEndpoint with @AiTool dispatch (langchain4j)' },

  // No user @AiEndpoint — spring-boot-starter auto-registers a default one
  // at /atmosphere/ai-chat. AG-UI POST-SSE controller sits next to it but
  // the Console connects to the default; isolation regression is on that
  // default endpoint.
  { sampleKey: 'spring-boot-agui-chat', shape: 'Default @AiEndpoint (auto-registered) + AG-UI POST-SSE' },

  // @Coordinator + 4 @Agent fleet at /atmosphere/agent/ceo. The bug
  // manifested as duplicated DISPATCH/DONE journal entries across tabs;
  // post-fix the journal events render exactly once.
  { sampleKey: 'spring-boot-multi-agent-startup-team', shape: '@Coordinator (CEO) routing to 4 @Agent fleet' },

  // Sandbox-backed @Agent. Console path is REST-only for sandbox flows
  // but /atmosphere/console/ still attaches to the default AI endpoint.
  { sampleKey: 'spring-boot-coding-agent', shape: '@Agent with sandbox provider + Console UI' },

  // Personal assistant @Agent — handoff + delegation primitives.
  { sampleKey: 'spring-boot-personal-assistant', shape: '@Agent primary-assistant with handoff' },

  // Support-desk orchestration @Agent + handoff.
  { sampleKey: 'spring-boot-orchestration-demo', shape: '@Agent support with approval gates' },

  // Dental @Agent with channel routing.
  { sampleKey: 'spring-boot-dentist-agent', shape: '@Agent dentist with channel adapters' },

  // Reattach harness @AiEndpoint at /atmosphere/agent/harness.
  { sampleKey: 'spring-boot-reattach-harness', shape: '@AiEndpoint reattach harness' },

  // Plan-and-Verify @Agent (Meijer guarded email). Reduced observation
  // window — verifier runs are slow, but the leak (if any) shows up
  // immediately on the broadcaster, before any verifier output.
  {
    sampleKey: 'spring-boot-guarded-email-agent',
    shape: '@Agent guarded-email (Plan-and-Verify)',
    opts: { observationMs: 6_000 },
  },
];

for (const c of cases) {
  test.describe(`Cross-tab isolation — ${c.sampleKey} (${c.shape})`, () => {
    let server: SampleServer;

    test.beforeAll(async () => {
      const base: SampleConfig = SAMPLES[c.sampleKey];
      const cfg: SampleConfig = c.envOverride
        ? { ...base, env: { ...(base.env ?? {}), ...c.envOverride } }
        : base;
      server = await startSample(cfg);
    });

    test.afterAll(async () => {
      await server?.stop();
    });

    test(`@smoke tab B receives no leaked prompt from tab A (${c.sampleKey})`, async ({ browser }) => {
      await assertCrossTabIsolation(browser, server, c.opts ?? {});
    });
  });
}

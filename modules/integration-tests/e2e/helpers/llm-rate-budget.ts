/*
 * Copyright 2008-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
import { promises as fs } from 'fs';
import * as path from 'path';
import * as os from 'os';

/**
 * Rolling-window rate limiter for real-LLM e2e tests firing against a
 * rate-capped provider — specifically the Gemini free tier, which allows only
 * `generate_content_free_tier_requests` = 5 requests/min/model. Without this,
 * the paid-nightly Gemini leg 429s: the recovery/multi-turn specs fire several
 * prompts back-to-back and the server relays the 429 as an in-stream error
 * frame, failing the assertions.
 *
 * NO-OP unless `LLM_RPM` is set to a positive integer — so the Ollama lane
 * (local, unmetered) and the OpenAI leg (higher limits) run unthrottled.
 *
 * FILE-BACKED on purpose: a paid leg runs two sequential Playwright steps
 * (`--project=real-llm-chat` then `--project=quarkus-ai-chat`) as SEPARATE
 * processes that share the provider's per-project quota. An in-memory limiter
 * would reset between them and let step 2 open on an already-spent minute. The
 * shared file (LLM_BUDGET_FILE, default in the OS temp dir) carries the window
 * across both. Playwright runs these with workers:1 and the steps are
 * sequential, so only one process touches the file at a time — no locking.
 */
const RPM = Number.parseInt(process.env.LLM_RPM || '0', 10);
const WINDOW_MS = 60_000;
const BUDGET_FILE = process.env.LLM_BUDGET_FILE
  || path.join(os.tmpdir(), 'atmosphere-llm-budget.jsonl');

async function readStamps(): Promise<number[]> {
  try {
    const raw = await fs.readFile(BUDGET_FILE, 'utf8');
    return raw.split('\n')
      .map(s => Number.parseInt(s, 10))
      .filter(n => Number.isFinite(n));
  } catch {
    return []; // first call — no window file yet
  }
}

/**
 * Block until firing one more LLM request keeps the trailing 60s window at or
 * below `LLM_RPM`, then record this request's timestamp. Returns immediately
 * when LLM_RPM is unset/non-positive.
 */
export async function llmBudget(nowFn: () => number = Date.now): Promise<void> {
  if (!Number.isFinite(RPM) || RPM <= 0) {
    return;
  }
  // Bounded spins: even fully saturated, a slot frees within one window.
  for (let attempt = 0; attempt < 240; attempt++) {
    const now = nowFn();
    const recent = (await readStamps()).filter(t => now - t < WINDOW_MS);
    if (recent.length < RPM) {
      recent.push(now);
      await fs.writeFile(BUDGET_FILE, recent.join('\n') + '\n');
      return;
    }
    // Wait for the oldest in-window request to age out (+250ms slack).
    const waitMs = WINDOW_MS - (now - Math.min(...recent)) + 250;
    await new Promise(r => setTimeout(r, Math.max(250, waitMs)));
  }
  throw new Error(`llmBudget: could not acquire a slot within the window (LLM_RPM=${RPM})`);
}

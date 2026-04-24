/*
 * Copyright 2011-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import type { PolicyDenial } from './types';

/**
 * Server emits these exact prefixes on {@code SecurityException} messages
 * from {@code AiPipeline} and {@code PolicyAdmissionGate}:
 * - {@code "Request denied by policy <name>: <reason>"} — GovernancePolicy deny
 * - {@code "Denied by policy '<name>': <reason>"}         — PolicyAdmissionGate deny (sample path)
 * - {@code "Request blocked: <reason>"}                   — AiGuardrail block
 * - {@code "Policy <name> evaluation failed: <reason>"}   — policy evaluate() threw
 *
 * Kept as a single regex-driven parser so wire-protocol shape changes are
 * one-line edits here, not scattered across dispatch sites.
 */

// Capture: policy name (word chars, dots, dashes, slashes), then reason after colon.
const REQUEST_DENIED_RE = /^Request denied by policy ([^:]+?):\s*(.+)$/s;
const GATE_DENIED_RE = /^Denied by policy ['"]?([^'"]+?)['"]?:\s*(.+)$/s;
const GUARDRAIL_BLOCKED_RE = /^Request blocked:\s*(.+)$/s;
const POLICY_EVAL_FAILED_RE = /^Policy ([^:]+?) evaluation failed:\s*(.+)$/s;

/**
 * Classify a server-emitted error string as a governance denial, or return
 * null when it's a generic transport / runtime error.
 */
export function parsePolicyDenial(raw: string | undefined | null): PolicyDenial | null {
  if (!raw || typeof raw !== 'string') return null;

  const trimmed = raw.trim();

  const denied = REQUEST_DENIED_RE.exec(trimmed) ?? GATE_DENIED_RE.exec(trimmed);
  if (denied) {
    return {
      kind: 'policy',
      policyName: denied[1].trim(),
      reason: denied[2].trim(),
      raw,
    };
  }

  const blocked = GUARDRAIL_BLOCKED_RE.exec(trimmed);
  if (blocked) {
    return {
      kind: 'guardrail',
      reason: blocked[1].trim(),
      raw,
    };
  }

  const evalFailed = POLICY_EVAL_FAILED_RE.exec(trimmed);
  if (evalFailed) {
    return {
      kind: 'policy',
      policyName: evalFailed[1].trim(),
      reason: 'evaluation failed: ' + evalFailed[2].trim(),
      raw,
    };
  }

  return null;
}

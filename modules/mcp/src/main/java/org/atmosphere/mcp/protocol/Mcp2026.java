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
package org.atmosphere.mcp.protocol;

/**
 * Wire constants for the MCP {@code 2026-07-28} release candidate — the
 * stateless protocol core (SEP-2567, SEP-2575).
 *
 * <p>Every string here is pinned verbatim from the canonical RC schema
 * ({@code modelcontextprotocol/modelcontextprotocol} {@code schema/draft/schema.ts},
 * which declares {@code LATEST_PROTOCOL_VERSION = "2026-07-28"}) and the
 * Streamable-HTTP transport spec — not inferred. Keep this the single source of
 * truth so the dialect code never embeds a magic string that can drift from the
 * spec.</p>
 *
 * <p>The {@code _meta} keys carry what the removed {@code initialize} handshake
 * used to exchange once: on the stateless model the client repeats its protocol
 * version, identity, and capabilities inside {@code params._meta} on
 * <em>every</em> request.</p>
 */
public final class Mcp2026 {

    private Mcp2026() {}

    /**
     * The stateless protocol revision this dialect speaks
     * ({@code schema.ts}: {@code export const LATEST_PROTOCOL_VERSION = "2026-07-28"}).
     */
    public static final String VERSION = "2026-07-28";

    // ── _meta keys (RequestMetaObject in schema.ts) ──────────────────────────

    /**
     * Required {@code params._meta} key carrying the protocol version for this
     * request. For HTTP it MUST equal the {@code MCP-Protocol-Version} header.
     */
    public static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";

    /** Required {@code params._meta} key carrying the client {@code Implementation} (name/version). */
    public static final String META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo";

    /** Required {@code params._meta} key carrying the client's per-request {@code ClientCapabilities}. */
    public static final String META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";

    // ── W3C Trace Context in _meta (SEP-414) ─────────────────────────────────
    // These three keys are an explicit exception to the reverse-DNS prefix rule
    // for _meta keys (per docs/specification/draft/basic/index.mdx): they carry
    // standard W3C Trace Context verbatim so OpenTelemetry propagators interop.

    /** {@code params._meta} key carrying the W3C {@code traceparent}. */
    public static final String META_TRACEPARENT = "traceparent";

    /** {@code params._meta} key carrying the W3C {@code tracestate}. */
    public static final String META_TRACESTATE = "tracestate";

    /** {@code params._meta} key carrying W3C {@code baggage}. */
    public static final String META_BAGGAGE = "baggage";

    // ── Result envelope (Result.resultType in schema.ts) ─────────────────────

    /** Result discriminator field; servers on this revision MUST include it. */
    public static final String RESULT_TYPE = "resultType";

    /** {@link #RESULT_TYPE} value for a terminal, fully-populated result. */
    public static final String RESULT_TYPE_COMPLETE = "complete";

    /** {@link #RESULT_TYPE} value for an {@code InputRequiredResult} (multi round-trip, SEP-2322). */
    public static final String RESULT_TYPE_INPUT_REQUIRED = "input_required";

    // ── Cacheable result (CacheableResult in schema.ts, SEP-2549) ────────────

    /**
     * Required freshness window, in milliseconds, on a {@code CacheableResult}
     * (list/read/discover). A value of {@code 0} tells the client to revalidate
     * on every use.
     */
    public static final String CACHE_TTL_MS = "ttlMs";

    /** Required cache scope discriminator on a {@code CacheableResult}. */
    public static final String CACHE_SCOPE = "cacheScope";

    /** {@link #CACHE_SCOPE} value for a result that is shareable across users. */
    public static final String CACHE_SCOPE_PUBLIC = "public";

    /** {@link #CACHE_SCOPE} value for a result scoped to the calling principal. */
    public static final String CACHE_SCOPE_PRIVATE = "private";

    // ── Operability headers (Streamable HTTP transport, SEP-2243) ────────────

    /**
     * HTTP header echoing the JSON-RPC {@code method} so a load balancer can
     * route without parsing the body. Servers reject header/body mismatches.
     */
    public static final String HEADER_MCP_METHOD = "Mcp-Method";

    /**
     * HTTP header echoing the target tool/resource/prompt name from
     * {@code params} for the same body-free routing.
     */
    public static final String HEADER_MCP_NAME = "Mcp-Name";

    // ── Error codes (schema.ts constants) ────────────────────────────────────

    /**
     * {@code UNSUPPORTED_PROTOCOL_VERSION} — the server does not speak the
     * version the client pinned in {@code _meta}; the error data carries the
     * {@code supported} list and the {@code requested} value.
     */
    public static final int UNSUPPORTED_PROTOCOL_VERSION = -32004;

    /**
     * {@code MISSING_REQUIRED_CLIENT_CAPABILITY} — the request needs a
     * capability the client did not declare in {@code clientCapabilities}.
     */
    public static final int MISSING_REQUIRED_CLIENT_CAPABILITY = -32003;
}

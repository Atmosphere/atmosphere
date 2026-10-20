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
package org.atmosphere.ai.resume;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.regex.Pattern;

/**
 * The run id a client mints for its prompt and sends with it, so the prompt's
 * turn has an id before the server answered anything: a page reloaded before
 * the server received the prompt, or before the server's
 * {@code X-Atmosphere-Run-Id} frame reached it, reattaches by it, and sends
 * the prompt again under the same id when the server never saw it. The server
 * adopts the id as the run's ({@code AiEndpointHandler}): a prompt under an
 * id whose run it already has is a reattach to that run, never a second run.
 *
 * <p>On the wire the prompt is wrapped in an envelope, a JSON object with
 * exactly two fields:</p>
 * <pre>{"clientRunId":"3f2c…-…","prompt":"what is 2+2?"}</pre>
 * <p>{@code prompt} is what the client would have sent alone: a string, or the
 * JSON object it sends otherwise (a multi-modal {@code content} envelope, a
 * routing-hints envelope), which the server then reads as before. A body that
 * is not such an envelope is the prompt itself, as for a client that sends
 * none. atmosphere.js wraps a prompt only when the application asks for it
 * ({@code AtmosphereRequest.clientRunId}); the Atmosphere Console always does.</p>
 */
public final class ClientRunIds {

    private static final Logger logger = LoggerFactory.getLogger(ClientRunIds.class);

    /** The envelope field naming the prompt's run id. */
    public static final String RUN_ID_FIELD = "clientRunId";

    /** The envelope field holding the prompt. */
    public static final String PROMPT_FIELD = "prompt";

    /** Request attribute holding the run id the prompt carried, once it was read. */
    public static final String ATTRIBUTE = "org.atmosphere.ai.clientRunId";

    /**
     * Metadata key of the frame telling a client that reconnected with a run
     * id that the server knows no run by it: the client then sends the prompt
     * it kept under that id again.
     */
    public static final String UNKNOWN_RUN_KEY = "X-Atmosphere-Run-Unknown";

    /** A UUID in its canonical text form, the only form a client run id may take. */
    private static final Pattern UUID_FORM = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ClientRunIds() {
    }

    /**
     * A prompt and the run id it carried.
     *
     * @param prompt the prompt, as the client would have sent it alone
     * @param runId  the run id, or {@code null} when the envelope carried none well formed
     */
    public record Unwrapped(String prompt, String runId) {
    }

    /**
     * The prompt in {@code body} and the run id it carried, or {@code null}
     * when {@code body} is no such envelope (the prompt itself). An envelope
     * whose id is not a well-formed UUID still yields its prompt, without an id.
     */
    public static Unwrapped unwrap(String body) {
        if (body == null || body.indexOf('"' + RUN_ID_FIELD + '"') < 0) {
            return null;
        }
        var trimmed = body.strip();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '{') {
            return null;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(trimmed);
        } catch (RuntimeException e) {
            logger.trace("Not a client run id envelope: {}", e.toString());
            return null;
        }
        if (root == null || !root.isObject() || root.size() != 2
                || !root.has(RUN_ID_FIELD) || !root.has(PROMPT_FIELD)) {
            return null;
        }
        var prompt = root.get(PROMPT_FIELD);
        String text;
        if (prompt.isString()) {
            text = prompt.asString();
        } else if (prompt.isObject() || prompt.isArray()) {
            text = prompt.toString();
        } else {
            return null;
        }
        var id = root.get(RUN_ID_FIELD);
        var runId = id.isString() ? id.asString() : null;
        if (!wellFormed(runId)) {
            logger.debug("Ignoring a malformed client run id; the prompt runs under an id of the server's");
            runId = null;
        }
        return new Unwrapped(text, runId);
    }

    /** Whether {@code runId} is a run id a client may mint: a UUID in its canonical text form. */
    public static boolean wellFormed(String runId) {
        return runId != null && UUID_FORM.matcher(runId).matches();
    }
}

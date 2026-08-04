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
package org.atmosphere.samples.springboot.aitools;

import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.llm.ChatMessage;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Demonstrates cost metering as an {@link AiInterceptor}.
 *
 * <p>{@code preProcess} estimates input streaming texts from text length (roughly 1 streaming text
 * per 4 characters) and calculates an estimated input cost using per-model pricing.
 * {@code beforeCompletion} sends the {@code routing.*} metadata (model / cost /
 * streaming-text count / latency) to the client while the session still accepts
 * frames — immediately before the terminal {@code complete} frame — so the
 * Console's routing chips render it. When a routing profile has installed a
 * {@link org.atmosphere.ai.routing.RoutingLlmClient}, the emission is skipped:
 * the router already published its genuine {@code routing.*} decision
 * mid-stream and the estimates must not overwrite it (Runtime Truth).
 * {@code postProcess} runs after the terminal frame and only logs the turn
 * latency server-side.</p>
 *
 * <p><b>Important:</b> the input figures are length-based estimates. For actual
 * output streaming text costs, production applications should integrate with
 * provider usage APIs or parse streaming metadata.</p>
 */
public class CostMeteringInterceptor implements AiInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(CostMeteringInterceptor.class);

    private static final String START_TIME_ATTR = "cost.startNanos";
    private static final String EST_STREAMING_TEXTS_ATTR = "cost.estStreamingTexts";
    private static final String EST_COST_ATTR = "cost.estCost";
    private static final String MODEL_ATTR = "cost.model";

    // Estimated price per 1M input streaming texts (USD) by model pattern (matched via contains())
    private static final Map<String, Double> INPUT_PRICE_PER_MILLION = Map.ofEntries(
            Map.entry("claude-haiku", 0.80),
            Map.entry("claude-sonnet", 3.0),
            Map.entry("claude-opus", 15.0),
            Map.entry("gpt-5.1-codex-max", 10.0),
            Map.entry("gpt-5", 2.5),
            Map.entry("gpt-4.1", 2.0),
            Map.entry("gpt-4o", 2.5),
            Map.entry("gpt-4", 30.0),
            Map.entry("gemini-2.5-flash", 0.15),
            Map.entry("gemini-2.5-pro", 1.25),
            Map.entry("gemini-3", 1.25)
    );

    private static final double DEFAULT_PRICE_PER_MILLION = 3.0;

    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        int totalChars = request.systemPrompt().length() + request.message().length();
        for (ChatMessage msg : request.history()) {
            totalChars += msg.content().length();
        }
        long estimatedStreamingTexts = totalChars / 4;

        var model = request.model() != null ? request.model() : "default";
        var modelLower = model.toLowerCase();

        // Match using contains() to handle prefixes like "copilot:claude-sonnet-4.6"
        double pricePerMillion = INPUT_PRICE_PER_MILLION.entrySet().stream()
                .filter(e -> modelLower.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(DEFAULT_PRICE_PER_MILLION);

        double estimatedCost = estimatedStreamingTexts * pricePerMillion / 1_000_000.0;

        logger.info("[Cost] model={}, est_input_streaming_texts=~{}, est_input_cost=${}", model, estimatedStreamingTexts,
                String.format("%.6f", estimatedCost));

        // Store for postProcess to send as metadata
        var req = resource.getRequest();
        req.setAttribute(START_TIME_ATTR, System.nanoTime());
        req.setAttribute(EST_STREAMING_TEXTS_ATTR, estimatedStreamingTexts);
        req.setAttribute(EST_COST_ATTR, estimatedCost);
        req.setAttribute(MODEL_ATTR, model);

        return request;
    }

    @Override
    public void beforeCompletion(AiRequest request, StreamingSession session,
                                 AtmosphereResource resource) {
        // Send routing metadata to the client while the session still accepts
        // frames — this hook runs immediately before the terminal complete
        // frame. The session parameter is the composed decorator target (the
        // same object stored under STREAMING_SESSION_ATTR): the frames reach
        // the wire and the streaming observers, but layers that finalize on
        // complete() (e.g. lineage capture) have already snapshotted their
        // state by the time this hook runs.
        //
        // Runtime Truth (Invariant #5): when a routing profile has installed a
        // RoutingLlmClient, the router already emitted its GENUINE routing.*
        // decision mid-stream. This hook fires later, so its length-based
        // estimates would clobber the routed values (last-write-wins in both
        // the Console merge and atmosphere.js's snapshot-at-complete) — skip
        // the emission and let the router's truth stand. On the plain/demo
        // path (no router installed) the estimates are the only source, so
        // they are emitted as before.
        var settings = org.atmosphere.ai.AiConfig.get();
        if (settings != null
                && settings.client() instanceof org.atmosphere.ai.routing.RoutingLlmClient) {
            logger.debug("[Cost] RoutingLlmClient active — skipping routing.* estimates "
                    + "so the router's genuine values are not overwritten");
            return;
        }
        var req = resource.getRequest();
        var model = req.getAttribute(MODEL_ATTR);
        var cost = req.getAttribute(EST_COST_ATTR);
        var streamingTexts = req.getAttribute(EST_STREAMING_TEXTS_ATTR);

        if (model != null) {
            session.sendMetadata("routing.model", model);
        }
        if (cost != null) {
            session.sendMetadata("routing.cost", cost);
        }
        if (streamingTexts != null) {
            session.sendMetadata("routing.streamingTexts", streamingTexts);
        }
        if (req.getAttribute(START_TIME_ATTR) instanceof Long startNanos) {
            var elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            if (elapsedMs > 0) {
                session.sendMetadata("routing.latency", elapsedMs);
            }
        }
    }

    @Override
    public void postProcess(AiRequest request, AtmosphereResource resource) {
        // Pure logging — the terminal frame is already forwarded by now, so
        // client-visible metadata is emitted from beforeCompletion above.
        if (resource.getRequest().getAttribute(START_TIME_ATTR) instanceof Long startNanos) {
            logger.info("[Cost] Request completed in {}ms",
                    (System.nanoTime() - startNanos) / 1_000_000);
        }
    }
}

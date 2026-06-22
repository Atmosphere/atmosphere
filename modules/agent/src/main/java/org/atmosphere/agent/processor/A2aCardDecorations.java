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
package org.atmosphere.agent.processor;

import org.atmosphere.a2a.runtime.A2aProtocolHandler;
import org.atmosphere.a2a.runtime.PushNotificationService;
import org.atmosphere.a2a.runtime.TaskManager;
import org.atmosphere.a2a.security.A2aCardSecurity;
import org.atmosphere.a2a.types.AgentCapabilities;
import org.atmosphere.a2a.types.AgentCard;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional A2A AgentCard decorations (JWS signing, push notifications) for
 * {@link AgentProcessor}.
 *
 * <p><strong>Why a separate class:</strong> these helpers take and return A2A
 * types ({@link AgentCard}). Atmosphere's annotation-scanning injector
 * reflects over every scanned class's declared methods and resolves their
 * parameter/return types ({@code Utils.getInheritedPrivateMethod}). If methods
 * with A2A-typed signatures lived on {@link AgentProcessor}, that reflection
 * would force-load {@link AgentCard} on the classpath of samples that do
 * <em>not</em> include {@code atmosphere-a2a}, throwing
 * {@code NoClassDefFoundError} and aborting agent registration. Confining the
 * A2A-typed signatures here — a class loaded only when {@code AgentProcessor}
 * already entered an {@code A2a}-guarded branch (so {@code atmosphere-a2a} is
 * present) — keeps {@link AgentProcessor}'s own signatures A2A-free and
 * reflection-safe.</p>
 */
final class A2aCardDecorations {

    private static final Logger logger = LoggerFactory.getLogger(A2aCardDecorations.class);

    private A2aCardDecorations() {
        // static helpers
    }

    /**
     * Sign the served AgentCard when {@code org.atmosphere.a2a.signCards=true}.
     * Uses an ephemeral Ed25519 key whose public JWK is embedded in the
     * signature so a verifier can detect tampering of the card in transit
     * (Correctness Invariant #4). The key rotates on restart; supply a stable
     * key out-of-band for identity binding. Off by default — an unsigned card
     * is the prior behaviour, so the capability is advertised only when
     * actually signing (Correctness Invariant #5).
     */
    static AgentCard signIfEnabled(AgentCard card, AtmosphereFramework framework, String agentName) {
        var config = framework.getAtmosphereConfig();
        if (config == null
                || !Boolean.parseBoolean(config.getInitParameter("org.atmosphere.a2a.signCards", "false"))) {
            return card;
        }
        var signer = org.atmosphere.a2a.security.AgentCardSigner.ephemeral();
        logger.info("Signing A2A AgentCard for '{}' with an ephemeral Ed25519 key "
                        + "(tamper-detection; rotates on restart — supply a stable key for identity)",
                agentName);
        return signer.sign(card);
    }

    /**
     * Whether A2A push notifications are enabled
     * ({@code org.atmosphere.a2a.pushNotifications=true}). Off by default.
     */
    static boolean pushEnabled(AtmosphereFramework framework) {
        var config = framework.getAtmosphereConfig();
        return config != null && Boolean.parseBoolean(
                config.getInitParameter("org.atmosphere.a2a.pushNotifications", "false"));
    }

    /**
     * Flip the card's {@code pushNotifications} capability to {@code true} —
     * advertised only when push is actually wired (Correctness Invariant #5).
     */
    static AgentCard advertisePush(AgentCard card) {
        var caps = card.capabilities();
        var updated = caps == null
                ? new AgentCapabilities(true, true, null, true)
                : new AgentCapabilities(caps.streaming(), true, caps.extensions(), caps.extendedAgentCard());
        return card.withCapabilities(updated);
    }

    /**
     * Advertise the deployer-declared A2A security scheme on the served card so
     * clients know what credential to present ({@code
     * org.atmosphere.a2a.securityScheme=bearer|apiKey}, header overridable via
     * {@code org.atmosphere.a2a.apiKeyHeader}). Off by default — an undeclared
     * scheme leaves the card open (the prior behaviour). The framework does not
     * enforce the scheme; the deployer's gateway/filter does.
     */
    static AgentCard advertiseSecurity(AgentCard card, AtmosphereFramework framework) {
        var config = framework.getAtmosphereConfig();
        if (config == null) {
            return card;
        }
        return A2aCardSecurity.advertise(card,
                config.getInitParameter("org.atmosphere.a2a.securityScheme", ""),
                config.getInitParameter("org.atmosphere.a2a.apiKeyHeader",
                        A2aCardSecurity.DEFAULT_API_KEY_HEADER));
    }

    /**
     * Emit a startup warning when an A2A endpoint is exposed with no declared
     * security scheme. The endpoint accepts {@code message/send} (LLM dispatch +
     * tool execution) from any caller unless the deployer fronts it with auth,
     * so the insecure default must not ship silently (Correctness Invariant #6).
     * Suppress with {@code org.atmosphere.a2a.suppressAuthWarning=true}.
     */
    static void warnIfUnauthenticated(AtmosphereFramework framework, String endpoint, String agentName) {
        var config = framework.getAtmosphereConfig();
        if (config == null) {
            return;
        }
        A2aCardSecurity.warnIfUnauthenticated(logger,
                config.getInitParameter("org.atmosphere.a2a.securityScheme", ""),
                Boolean.parseBoolean(
                        config.getInitParameter("org.atmosphere.a2a.suppressAuthWarning", "false")),
                endpoint, agentName);
    }

    /**
     * Wire a {@link PushNotificationService} into the handler and register its
     * shutdown with the framework so the owned HTTP client and task listener
     * are released on stop (Correctness Invariant #1).
     */
    static void wirePush(AtmosphereFramework framework, A2aProtocolHandler handler,
                         TaskManager taskManager, String agentName) {
        var service = new PushNotificationService(taskManager);
        framework.getAtmosphereConfig().shutdownHook(service::close);
        handler.setPushNotificationService(service);
        logger.info("A2A push notifications enabled for '{}' (webhook delivery on terminal task state)",
                agentName);
    }
}

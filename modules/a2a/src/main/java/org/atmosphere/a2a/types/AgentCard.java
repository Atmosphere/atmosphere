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
package org.atmosphere.a2a.types;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Public agent descriptor served at {@code /.well-known/agent-card.json} (the
 * A2A v1.0.0 well-known path) and at the pre-1.0 {@code /.well-known/agent.json}. Aligned
 * with A2A v1.0.0: the pre-1.0 single top-level {@code url} string is replaced
 * by the {@link #supportedInterfaces} list (so an agent can advertise multiple
 * protocol bindings — e.g. JSON-RPC and HTTP+JSON — at distinct URLs);
 * {@code provider} is structured as {@link AgentProvider} (was a free string);
 * {@link #securitySchemes} is typed; and {@link #signatures}, {@link #iconUrl},
 * and {@link #securityRequirements} are new.
 *
 * <p>The pre-1.0 {@code guardrails} top-level list is no longer modeled —
 * surface guardrails as an {@link AgentExtension} on
 * {@link AgentCapabilities#extensions()} using
 * {@link AgentExtension#GUARDRAILS_URI}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentCard(
    String name,
    String description,
    List<AgentInterface> supportedInterfaces,
    AgentProvider provider,
    String version,
    String documentationUrl,
    AgentCapabilities capabilities,
    Map<String, SecurityScheme> securitySchemes,
    List<SecurityRequirement> securityRequirements,
    List<String> defaultInputModes,
    List<String> defaultOutputModes,
    List<AgentSkill> skills,
    List<AgentCardSignature> signatures,
    String iconUrl
) {
    public AgentCard {
        supportedInterfaces = supportedInterfaces != null
                ? List.copyOf(supportedInterfaces) : List.of();
        securitySchemes = securitySchemes != null ? Map.copyOf(securitySchemes) : null;
        securityRequirements = securityRequirements != null
                ? List.copyOf(securityRequirements) : null;
        defaultInputModes = defaultInputModes != null
                ? List.copyOf(defaultInputModes) : List.of("text");
        defaultOutputModes = defaultOutputModes != null
                ? List.copyOf(defaultOutputModes) : List.of("text");
        skills = skills != null ? List.copyOf(skills) : List.of();
        signatures = signatures != null ? List.copyOf(signatures) : null;
    }

    /**
     * A copy of this card with its {@link #signatures} replaced. Used by the
     * JWS signing/verification path: the signed payload is the card with
     * {@code signatures} cleared, and the served card carries the resulting
     * {@link AgentCardSignature}s.
     */
    public AgentCard withSignatures(List<AgentCardSignature> newSignatures) {
        return new AgentCard(name, description, supportedInterfaces, provider, version,
                documentationUrl, capabilities, securitySchemes, securityRequirements,
                defaultInputModes, defaultOutputModes, skills, newSignatures, iconUrl);
    }

    /**
     * A copy of this card whose relative {@link AgentInterface#url()}s (a path
     * such as {@code /atmosphere/a2a}, which is what the processors know when
     * they build the card) are made absolute against {@code origin}, the
     * scheme, host, port and context path the card is being served from. A2A
     * v1.0.0 requires an interface URL to be an absolute URL.
     *
     * <p>A signed card is returned unchanged: its signature covers the URLs it
     * was signed with, and rewriting them would make it fail verification. An
     * agent that signs its card and needs absolute URLs declares an absolute
     * endpoint. Absolute URLs, and a blank {@code origin}, leave the card as is.</p>
     *
     * @param origin for example {@code https://agents.example.com} or
     *               {@code http://localhost:8080/app}, without a trailing slash
     */
    public AgentCard withAbsoluteInterfaceUrls(String origin) {
        if (origin == null || origin.isBlank()
                || (signatures != null && !signatures.isEmpty())) {
            return this;
        }
        var changed = false;
        var resolved = new java.util.ArrayList<AgentInterface>(supportedInterfaces.size());
        for (var anInterface : supportedInterfaces) {
            var url = anInterface.url();
            if (url != null && url.startsWith("/") && !url.startsWith("//")) {
                resolved.add(new AgentInterface(origin + url, anInterface.protocolBinding(),
                        anInterface.tenant(), anInterface.protocolVersion()));
                changed = true;
            } else {
                resolved.add(anInterface);
            }
        }
        if (!changed) {
            return this;
        }
        return new AgentCard(name, description, resolved, provider, version,
                documentationUrl, capabilities, securitySchemes, securityRequirements,
                defaultInputModes, defaultOutputModes, skills, signatures, iconUrl);
    }

    /**
     * A copy of this card with its {@link #capabilities} replaced. Used to
     * advertise a capability (e.g. push notifications) only once it is
     * actually wired at runtime (Correctness Invariant #5).
     */
    public AgentCard withCapabilities(AgentCapabilities newCapabilities) {
        return new AgentCard(name, description, supportedInterfaces, provider, version,
                documentationUrl, newCapabilities, securitySchemes, securityRequirements,
                defaultInputModes, defaultOutputModes, skills, signatures, iconUrl);
    }

    /**
     * A copy of this card with its {@link #securitySchemes} and
     * {@link #securityRequirements} replaced. Used to advertise the security
     * scheme the deployer declares they enforce in front of the (otherwise
     * unauthenticated) A2A endpoint, so clients know what credential to present.
     * The framework does not itself enforce the scheme — the deployer's
     * gateway/filter does — so this relays the declared contract; it does not
     * assert framework-enforced auth.
     */
    public AgentCard withSecurity(Map<String, SecurityScheme> newSchemes,
                                  List<SecurityRequirement> newRequirements) {
        return new AgentCard(name, description, supportedInterfaces, provider, version,
                documentationUrl, capabilities, newSchemes, newRequirements,
                defaultInputModes, defaultOutputModes, skills, signatures, iconUrl);
    }
}

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
package org.atmosphere.quarkus.runtime;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.atmosphere.ai.gateway.AiGateway;
import org.atmosphere.ai.gateway.AiGatewayHolder;
import org.atmosphere.ai.gateway.GatewayProfiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Quarkus port of the Spring Boot starter's
 * {@code AtmosphereAiAutoConfiguration.GatewayProfileInstaller}. Gives the
 * outbound-LLM gateway the same one-line production opt-in on both runtimes
 * (Mode Parity, Correctness Invariant #7):
 *
 * <pre>
 *   quarkus.atmosphere.ai.gateway.profile=production
 * </pre>
 *
 * <p>Without that key the process-wide {@link AiGatewayHolder} keeps its
 * permissive dev default (1M calls/hour) — no enforcement is forced on an
 * existing deployment. With it, {@link GatewayProfiles#production} limits
 * install: a per-principal request ceiling plus a separate, tighter ceiling
 * for the shared {@link AiGateway#ANONYMOUS_USER} bucket that every
 * unauthenticated caller collapses into. Each dimension is overridable via
 * {@code max-requests-per-window}, {@code window-seconds}, and
 * {@code anonymous-max-requests}; a {@link AiGateway.CredentialResolver} or
 * {@link AiGateway.GatewayTraceExporter} CDI bean is composed in when present.</p>
 *
 * <p>{@link #onShutdown(ShutdownEvent)} restores the permissive default only
 * when this bean installed (Ownership, Correctness Invariant #1) so a dev-mode
 * live reload does not leave a stale gateway behind.</p>
 */
@ApplicationScoped
public class AtmosphereGatewayProducer {

    private static final Logger logger =
            LoggerFactory.getLogger(AtmosphereGatewayProducer.class);

    @Inject
    AtmosphereConfig config;

    // Instance<> is always satisfiable, so absent user beans do not make these
    // injections unresolvable; isResolvable() == exactly one bean present.
    @Inject
    Instance<AiGateway.CredentialResolver> userResolver;

    @Inject
    Instance<AiGateway.GatewayTraceExporter> userExporter;

    // Non-null only when this bean installed into the process-wide holder.
    private volatile AiGateway installedGateway;
    private volatile String profile = "not-started";

    /**
     * Installs the configured gateway profile on application startup. A no-op
     * for any profile other than {@code production} — the holder keeps the
     * permissive default and the framework logs its existing one-line-fix
     * warning on first use.
     *
     * @param event the Quarkus startup event (unused, present so Arc fires
     *              the observer eagerly)
     */
    public void onStart(@Observes @Priority(130) StartupEvent event) {
        if (installedGateway != null) {
            return;
        }
        profile = config.ai().gateway().profile();
        if (!GatewayProfiles.PRODUCTION.equalsIgnoreCase(profile)) {
            logger.debug("AtmosphereGatewayProducer: profile '{}' — holder keeps "
                    + "the permissive default gateway", profile);
            return;
        }
        var windowSeconds = config.ai().gateway().windowSeconds();
        var gateway = GatewayProfiles.production(
                config.ai().gateway().maxRequestsPerWindow(),
                windowSeconds > 0 ? Duration.ofSeconds(windowSeconds) : null,
                config.ai().gateway().anonymousMaxRequests(),
                userResolver.isResolvable()
                        ? userResolver.get() : AiGateway.CredentialResolver.noop(),
                userExporter.isResolvable()
                        ? userExporter.get() : AiGateway.GatewayTraceExporter.noop());
        AiGatewayHolder.install(gateway);
        installedGateway = gateway;
        logger.info("AiGateway production profile installed "
                        + "({} requests / {}s per principal, {} for the shared "
                        + "anonymous bucket)",
                gateway.rateLimiter().maxRequests(),
                gateway.rateLimiter().window().toSeconds(),
                gateway.anonymousRateLimiter().maxRequests());
    }

    /**
     * Restores the permissive default gateway if — and only if — this bean
     * installed one (Ownership, Correctness Invariant #1).
     *
     * @param event the Quarkus shutdown event (unused, present so Arc fires
     *              the observer)
     */
    public void onShutdown(@Observes ShutdownEvent event) {
        if (installedGateway != null) {
            AiGatewayHolder.reset();
            installedGateway = null;
        }
    }

    /**
     * Accessor used by tests to confirm what the startup observer installed.
     *
     * @return the installed gateway, or {@code null} when the holder kept its default
     */
    public AiGateway installedGateway() {
        return installedGateway;
    }

    /**
     * Accessor used by tests to confirm which profile was resolved.
     *
     * @return the resolved profile name
     */
    public String profile() {
        return profile;
    }
}

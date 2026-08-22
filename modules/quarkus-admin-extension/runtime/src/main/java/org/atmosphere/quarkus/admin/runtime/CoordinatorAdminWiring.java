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
package org.atmosphere.quarkus.admin.runtime;

import org.atmosphere.admin.AtmosphereAdmin;
import org.atmosphere.admin.coordinator.CoordinatorController;
import org.atmosphere.coordinator.fleet.AgentFleet;
import org.atmosphere.coordinator.journal.CoordinationJournal;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Wires the coordinator roster into the admin facade. Kept out of
 * {@link AdminProducer} because {@code atmosphere-coordinator} is an
 * optional dependency: this class is only loaded behind a
 * {@code Class.forName} guard so its coordinator-typed constant pool
 * never links when the module is absent (native-image force-links
 * types referenced from reachable code).
 *
 * <p>Previously nothing on Quarkus called
 * {@code setCoordinatorController}, so {@code /api/admin/coordinators}
 * and the fleet-health / journal-tree routes always answered empty/404
 * while Spring served real data (Correctness Invariant #7 — mode
 * parity).</p>
 */
final class CoordinatorAdminWiring {

    private static final Logger logger = LoggerFactory.getLogger(CoordinatorAdminWiring.class);

    private CoordinatorAdminWiring() {
    }

    /**
     * The live fleet roster: reads the framework property bag
     * {@code CoordinatorProcessor} publishes into on every call, so the
     * report is runtime truth (Correctness Invariant #5).
     */
    static Supplier<Map<String, AgentFleet>> fleetsSupplier(AtmosphereFramework framework) {
        return () -> {
            try {
                var cfg = framework.getAtmosphereConfig();
                if (cfg != null && cfg.properties().get(CoordinatorController.FLEETS_PROPERTY)
                        instanceof Map<?, ?> fleets) {
                    // Module-owned key, written only by CoordinatorProcessor.
                    @SuppressWarnings("unchecked")
                    var typed = (Map<String, AgentFleet>) fleets;
                    return typed;
                }
            } catch (RuntimeException e) {
                logger.debug("Coordinator fleets not available", e);
            }
            return Map.of();
        };
    }

    static void install(AtmosphereAdmin admin, AtmosphereFramework framework) {
        admin.setCoordinatorController(new CoordinatorController(
                fleetsSupplier(framework), CoordinationJournal.NOOP));
        logger.debug("Atmosphere Admin: Coordinator controller wired");
    }
}

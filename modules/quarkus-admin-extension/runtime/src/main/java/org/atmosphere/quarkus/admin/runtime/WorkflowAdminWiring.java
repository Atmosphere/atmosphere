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
import org.atmosphere.admin.workflow.InMemoryWorkflowStore;
import org.atmosphere.admin.workflow.WorkflowController;
import org.atmosphere.admin.workflow.WorkflowRunner;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wires the workflow authoring + execution surface (registre#1). Kept
 * out of {@link AdminProducer} because {@link WorkflowRunner} references
 * both optional modules ({@code atmosphere-coordinator} for fleet
 * dispatch, {@code atmosphere-ai} for approval gates): this class is
 * only loaded behind {@code Class.forName} guards for both, so its
 * constant pool never links when either is absent.
 */
final class WorkflowAdminWiring {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowAdminWiring.class);

    private WorkflowAdminWiring() {
    }

    static void install(AtmosphereAdmin admin, AtmosphereFramework framework) {
        // The runner dispatches saved manifests against the same live
        // fleet roster the coordinator controller reports.
        var runner = new WorkflowRunner(CoordinatorAdminWiring.fleetsSupplier(framework));
        admin.setWorkflowController(new WorkflowController(
                new InMemoryWorkflowStore(), admin.authorizer(), admin.auditLog(), runner));
        logger.debug("Atmosphere Admin: Workflow controller wired (execution enabled)");
    }
}

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

/**
 * Microsoft Agent Control Specification (ACS) manifest support: the YAML
 * dialect rooted at {@code agent_control_specification_version} that binds
 * named policies (Rego bundles and other engine types) to intervention
 * points. {@link org.atmosphere.ai.governance.YamlPolicyParser} routes ACS
 * documents here; {@code atmosphere-ai-policy-rego} registers the
 * {@code rego} {@link org.atmosphere.ai.governance.acs.AcsPolicyEngine}
 * backed by the real {@code opa} binary. Conformance with the upstream
 * manifest contract is pinned by {@code AcsManifestConformanceTest} against
 * verbatim copies of microsoft/agent-governance-toolkit's own contract
 * fixtures, kept in sync by the weekly {@code ms-yaml-conformance} workflow.
 */
package org.atmosphere.ai.governance.acs;

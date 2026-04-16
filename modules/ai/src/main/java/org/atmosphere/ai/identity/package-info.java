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
 * Per-user identity, permissions, credentials, audit trail, and session
 * sharing. {@link org.atmosphere.ai.identity.AgentIdentity} is the SPI;
 * {@link org.atmosphere.ai.identity.InMemoryAgentIdentity} is the default.
 *
 * <p>Credential storage is delegated to
 * {@link org.atmosphere.ai.identity.CredentialStore} — built-ins include
 * {@link org.atmosphere.ai.identity.InMemoryCredentialStore} (tests) and
 * {@link org.atmosphere.ai.identity.AtmosphereEncryptedCredentialStore}
 * (AES-GCM).</p>
 */
package org.atmosphere.ai.identity;

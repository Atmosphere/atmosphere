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
package org.atmosphere.ai.llm;

/**
 * A turn a built-in client refused because its API key would cross the network
 * in cleartext ({@link CleartextApiKeyPolicy}). The client that refuses logs the
 * refusal at {@code ERROR} before it ends the turn with this exception, so the
 * streaming session that ends the turn logs no second error and no stack: the
 * refusal is expected, and its stack names nothing the message does not.
 *
 * <p>Only this package creates one, so every instance has been logged.</p>
 */
public final class CleartextApiKeyRefusedException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    CleartextApiKeyRefusedException(String refusal) {
        super(refusal);
    }
}

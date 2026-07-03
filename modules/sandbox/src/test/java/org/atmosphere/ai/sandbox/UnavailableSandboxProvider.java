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
package org.atmosphere.ai.sandbox;

import java.util.Map;

/**
 * Test-only {@link SandboxProvider} that is registered but never available.
 * Lets tests pin the fail-fast contract: a {@code @SandboxTool} naming this
 * backend must fail with a descriptive error and must NOT be routed to any
 * other (available) provider — {@link #create} throwing is the tripwire.
 */
public final class UnavailableSandboxProvider implements SandboxProvider {

    @Override
    public String name() {
        return "down";
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public Sandbox create(String image, SandboxLimits limits, Map<String, String> metadata) {
        throw new AssertionError(
                "create() must never be called on an unavailable provider");
    }
}

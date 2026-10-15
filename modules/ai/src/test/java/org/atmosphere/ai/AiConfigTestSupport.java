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
package org.atmosphere.ai;

/** Test hooks of {@link AiConfig} for tests outside its package. */
public final class AiConfigTestSupport {

    private AiConfigTestSupport() {
    }

    /**
     * Clears the settings of the process default and of every open
     * {@link AiEnvironment}, as {@code AiConfig.resetForTesting()} does.
     */
    public static void resetSettings() {
        AiConfig.resetForTesting();
    }
}

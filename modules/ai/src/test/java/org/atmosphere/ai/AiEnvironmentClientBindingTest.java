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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order in which a runtime adapter's instance picks its native client:
 * the client bound to its application, then the adapter's static binding,
 * then the client its application's auto-configuration offered. An
 * auto-configuration that bound instead of offering would hide a client the
 * application set through the static binder.
 */
class AiEnvironmentClientBindingTest {

    /** Stands in for a runtime adapter class owning the slot. */
    private static final class Adapter {
    }

    @Test
    void boundBeatsStaticWhichBeatsOffered() {
        var environment = AiEnvironment.create(key -> null);
        try {
            assertNull(environment.client(Adapter.class, String.class, null), "nothing bound, set or offered");

            assertTrue(environment.offerClient(Adapter.class, String.class, "offered"));
            assertEquals("offered", environment.client(Adapter.class, String.class, null),
                    "an offered client serves when nothing else is set");
            assertEquals("static", environment.client(Adapter.class, String.class, "static"),
                    "the adapter's static binding wins over an offered client");

            assertFalse(environment.offerClient(Adapter.class, String.class, "offered-later"),
                    "a client offered earlier is kept");
            assertEquals("offered", environment.client(Adapter.class, String.class, null));

            environment.bindClient(Adapter.class, String.class, "bound");
            assertEquals("bound", environment.client(Adapter.class, String.class, "static"),
                    "the client bound to the application wins over the static binding");

            environment.bindClient(Adapter.class, String.class, null);
            assertEquals("static", environment.client(Adapter.class, String.class, "static"),
                    "removing the binding restores the static one");
        } finally {
            environment.close();
        }
    }
}

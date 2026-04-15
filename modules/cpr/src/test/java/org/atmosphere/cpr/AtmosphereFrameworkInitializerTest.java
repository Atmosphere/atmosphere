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
package org.atmosphere.cpr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class AtmosphereFrameworkInitializerTest {

    @Test
    void frameworkCreatedOnFirstCall() {
        var initializer = new AtmosphereFrameworkInitializer(false, false);

        AtmosphereFramework fw = initializer.framework();

        assertNotNull(fw);
    }

    @Test
    void frameworkReturnsSameInstanceOnSecondCall() {
        var initializer = new AtmosphereFrameworkInitializer(false, false);

        AtmosphereFramework first = initializer.framework();
        AtmosphereFramework second = initializer.framework();

        assertSame(first, second);
    }

    @Test
    void destroyNullsFramework() {
        var initializer = new AtmosphereFrameworkInitializer(false, false);
        initializer.framework();

        initializer.destroy();

        // After destroy, framework() creates a new instance
        AtmosphereFramework newFw = initializer.framework();
        assertNotNull(newFw);
    }

    @Test
    void destroyTwiceIsSafe() {
        var initializer = new AtmosphereFrameworkInitializer(false, false);
        initializer.framework();

        assertDoesNotThrow(() -> {
            initializer.destroy();
            initializer.destroy();
        });
    }

    @Test
    void destroyWithoutFrameworkCreationIsSafe() {
        var initializer = new AtmosphereFrameworkInitializer(true, true);

        assertDoesNotThrow(initializer::destroy);
    }

    @Test
    void constructorWithFilterFlagCreatesFilterFramework() {
        var initializer = new AtmosphereFrameworkInitializer(true, false);

        AtmosphereFramework fw = initializer.framework();

        assertNotNull(fw);
    }

    @Test
    void constructorWithAutoDetectHandlers() {
        var initializer = new AtmosphereFrameworkInitializer(false, true);

        AtmosphereFramework fw = initializer.framework();

        assertNotNull(fw);
    }

    @Test
    void newAtmosphereFrameworkCreatesInstance() {
        AtmosphereFramework fw = AtmosphereFrameworkInitializer.newAtmosphereFramework(
                AtmosphereFramework.class, false, false);

        assertNotNull(fw);
    }

    @Test
    void frameworkFieldIsNullBeforeFirstCall() throws Exception {
        var initializer = new AtmosphereFrameworkInitializer(false, false);

        // Access the protected field via reflection to verify null initial state
        var field = AtmosphereFrameworkInitializer.class.getDeclaredField("framework");
        field.setAccessible(true);
        assertNull(field.get(initializer));
    }
}

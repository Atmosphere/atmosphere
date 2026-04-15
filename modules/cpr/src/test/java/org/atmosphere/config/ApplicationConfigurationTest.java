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
package org.atmosphere.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ApplicationConfigurationTest {

    @Test
    void constructorSetsNameAndValue() {
        ApplicationConfiguration config = new ApplicationConfiguration("key1", "value1");

        assertEquals("key1", config.getParamName());
        assertEquals("value1", config.getParamValue());
    }

    @Test
    void setParamNameUpdatesName() {
        ApplicationConfiguration config = new ApplicationConfiguration("original", "val");

        config.setParamName("updated");

        assertEquals("updated", config.getParamName());
    }

    @Test
    void setParamValueUpdatesValue() {
        ApplicationConfiguration config = new ApplicationConfiguration("key", "original");

        config.setParamValue("updated");

        assertEquals("updated", config.getParamValue());
    }

    @Test
    void nullValuesAreAllowed() {
        ApplicationConfiguration config = new ApplicationConfiguration(null, null);

        assertNull(config.getParamName());
        assertNull(config.getParamValue());
    }

    @Test
    void emptyStringValuesAreAllowed() {
        ApplicationConfiguration config = new ApplicationConfiguration("", "");

        assertEquals("", config.getParamName());
        assertEquals("", config.getParamValue());
    }
}

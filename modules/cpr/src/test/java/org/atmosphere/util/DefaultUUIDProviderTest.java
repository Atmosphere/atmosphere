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
package org.atmosphere.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultUUIDProviderTest {

    @Test
    void generatesNonNullUuid() {
        var provider = new DefaultUUIDProvider();
        assertNotNull(provider.generateUuid());
    }

    @Test
    void generatesUniqueUuids() {
        var provider = new DefaultUUIDProvider();
        var uuid1 = provider.generateUuid();
        var uuid2 = provider.generateUuid();
        assertNotEquals(uuid1, uuid2);
    }

    @Test
    void uuidHasCorrectFormat() {
        var provider = new DefaultUUIDProvider();
        var uuid = provider.generateUuid();
        assertTrue(uuid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }

    @Test
    void generatesNonEmptyUuid() {
        var provider = new DefaultUUIDProvider();
        assertFalse(provider.generateUuid().isEmpty());
    }
}

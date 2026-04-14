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

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAtmosphereResourceSessionTest {

    @Test
    void setAndGetAttribute() {
        var session = new DefaultAtmosphereResourceSession();
        session.setAttribute("key", "value");
        assertEquals("value", session.getAttribute("key"));
    }

    @Test
    void getTypedAttribute() {
        var session = new DefaultAtmosphereResourceSession();
        session.setAttribute("count", 42);
        assertEquals(42, session.getAttribute("count", Integer.class));
    }

    @Test
    void getMissingAttributeReturnsNull() {
        var session = new DefaultAtmosphereResourceSession();
        assertNull(session.getAttribute("missing"));
    }

    @Test
    void setNullValueRemovesAttribute() {
        var session = new DefaultAtmosphereResourceSession();
        session.setAttribute("key", "value");
        session.setAttribute("key", null);
        assertNull(session.getAttribute("key"));
    }

    @Test
    void setAttributeReturnsPreviousValue() {
        var session = new DefaultAtmosphereResourceSession();
        assertNull(session.setAttribute("key", "first"));
        assertEquals("first", session.setAttribute("key", "second"));
    }

    @Test
    void getAttributeNames() {
        var session = new DefaultAtmosphereResourceSession();
        session.setAttribute("a", 1);
        session.setAttribute("b", 2);
        Collection<String> names = session.getAttributeNames();
        assertEquals(2, names.size());
        assertTrue(names.contains("a"));
        assertTrue(names.contains("b"));
    }

    @Test
    void attributeNamesAreUnmodifiable() {
        var session = new DefaultAtmosphereResourceSession();
        session.setAttribute("x", 1);
        Collection<String> names = session.getAttributeNames();
        assertThrows(UnsupportedOperationException.class, () -> names.add("y"));
    }

    @Test
    void invalidateClearsAttributes() {
        var session = new DefaultAtmosphereResourceSession();
        session.setAttribute("key", "val");
        session.invalidate();
        assertThrows(IllegalStateException.class, () -> session.getAttribute("key"));
    }

    @Test
    void invalidatedSessionThrowsOnSetAttribute() {
        var session = new DefaultAtmosphereResourceSession();
        session.invalidate();
        assertThrows(IllegalStateException.class, () -> session.setAttribute("key", "val"));
    }

    @Test
    void invalidatedSessionThrowsOnGetNames() {
        var session = new DefaultAtmosphereResourceSession();
        session.invalidate();
        assertThrows(IllegalStateException.class, session::getAttributeNames);
    }

    @Test
    void doubleInvalidateThrows() {
        var session = new DefaultAtmosphereResourceSession();
        session.invalidate();
        assertThrows(IllegalStateException.class, session::invalidate);
    }
}

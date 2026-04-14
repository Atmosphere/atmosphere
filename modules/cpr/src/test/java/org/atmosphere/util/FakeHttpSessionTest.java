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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FakeHttpSessionTest {

    private FakeHttpSession session;

    @BeforeEach
    void setUp() {
        session = new FakeHttpSession("sess-1", null, 1000L, 300);
    }

    @Test
    void getIdReturnsSessionId() {
        assertEquals("sess-1", session.getId());
    }

    @Test
    void getCreationTimeReturnsConstructorValue() {
        assertEquals(1000L, session.getCreationTime());
    }

    @Test
    void getLastAccessedTimeReturnsZero() {
        assertEquals(0, session.getLastAccessedTime());
    }

    @Test
    void getServletContextReturnsNull() {
        assertNull(session.getServletContext());
    }

    @Test
    void maxInactiveInterval() {
        assertEquals(300, session.getMaxInactiveInterval());
        session.setMaxInactiveInterval(600);
        assertEquals(600, session.getMaxInactiveInterval());
    }

    @Test
    void setAndGetAttribute() {
        assertNull(session.getAttribute("key"));
        session.setAttribute("key", "value");
        assertEquals("value", session.getAttribute("key"));
    }

    @Test
    void setAttributeNullRemoves() {
        session.setAttribute("key", "value");
        session.setAttribute("key", null);
        assertNull(session.getAttribute("key"));
    }

    @Test
    void removeAttribute() {
        session.setAttribute("key", "value");
        session.removeAttribute("key");
        assertNull(session.getAttribute("key"));
    }

    @Test
    void getAttributeNamesReturnsAll() {
        session.setAttribute("a", 1);
        session.setAttribute("b", 2);
        var names = session.getAttributeNames();
        assertNotNull(names);
        int count = 0;
        while (names.hasMoreElements()) {
            names.nextElement();
            count++;
        }
        assertEquals(2, count);
    }

    @Test
    void isNewReturnsFalse() {
        assertFalse(session.isNew());
    }

    @Test
    void invalidateThrowsOnSubsequentAccess() {
        session.invalidate();
        assertThrows(IllegalStateException.class, session::getId);
        assertThrows(IllegalStateException.class, session::getCreationTime);
        assertThrows(IllegalStateException.class, () -> session.getAttribute("x"));
        assertThrows(IllegalStateException.class, () -> session.setAttribute("x", "y"));
    }

    @Test
    void doubleInvalidateThrows() {
        session.invalidate();
        assertThrows(IllegalStateException.class, session::invalidate);
    }

    @Test
    void destroyClearsAttributes() {
        session.setAttribute("a", 1);
        session.destroy();
        // After destroy, attributes are cleared but session is still valid
        assertNull(session.getAttribute("a"));
    }

    @Test
    @SuppressWarnings("deprecation")
    void getSessionContextReturnsNull() {
        assertNull(session.getSessionContext());
    }
}

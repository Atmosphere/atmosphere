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
package org.atmosphere.auth;

import org.junit.jupiter.api.Test;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.*;

public class SimplePrincipalTest {

    @Test
    public void testGetName() {
        var principal = new SimplePrincipal("alice");
        assertEquals("alice", principal.getName());
        assertEquals("alice", principal.name());
    }

    @Test
    public void testImplementsPrincipal() {
        assertInstanceOf(Principal.class, new SimplePrincipal("bob"));
    }

    @Test
    public void testNullNameThrows() {
        assertThrows(NullPointerException.class, () -> new SimplePrincipal(null));
    }

    @Test
    public void testEquality() {
        var a = new SimplePrincipal("alice");
        var b = new SimplePrincipal("alice");
        var c = new SimplePrincipal("bob");
        assertEquals(a, b);
        assertNotEquals(a, c);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void testToString() {
        var principal = new SimplePrincipal("charlie");
        assertTrue(principal.toString().contains("charlie"));
    }
}

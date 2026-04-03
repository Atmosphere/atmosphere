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

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DeliverTest {

    @Test
    void constructorForSingleResource() {
        var future = new BroadcasterFuture<>("original");
        var deliver = new Deliver("hello", (AtmosphereResource) null, future, "original");

        assertEquals(Deliver.TYPE.RESOURCE, deliver.getType());
        assertEquals("hello", deliver.getMessage());
        assertEquals("original", deliver.getOriginalMessage());
        assertNull(deliver.getResource());
        assertSame(future, deliver.getFuture());
        assertTrue(deliver.isWriteLocally());
        assertTrue(deliver.isAsync());
        assertNull(deliver.getResources());
        assertNull(deliver.getCache());
    }

    @Test
    void constructorForAllResources() {
        var future = new BroadcasterFuture<>("msg");
        var deliver = new Deliver("broadcast", future, "original");

        assertEquals(Deliver.TYPE.ALL, deliver.getType());
        assertEquals("broadcast", deliver.getMessage());
        assertEquals("original", deliver.getOriginalMessage());
        assertNull(deliver.getResource());
        assertNull(deliver.getResources());
    }

    @Test
    void constructorForResourceSet() {
        var future = new BroadcasterFuture<>("msg");
        Set<AtmosphereResource> resources = Set.of();
        var deliver = new Deliver("msg", resources, future, "original");

        assertEquals(Deliver.TYPE.SET, deliver.getType());
        assertSame(resources, deliver.getResources());
        assertNull(deliver.getResource());
    }

    @Test
    void constructorWithWriteLocally() {
        var future = new BroadcasterFuture<>("msg");
        var deliver = new Deliver("msg", future, false);

        assertEquals(Deliver.TYPE.ALL, deliver.getType());
        assertFalse(deliver.isWriteLocally());
        assertEquals("msg", deliver.getMessage());
        assertEquals("msg", deliver.getOriginalMessage());
    }

    @Test
    void settersUpdateFields() {
        var future = new BroadcasterFuture<>("msg");
        var deliver = new Deliver("initial", future, "original");

        deliver.setMessage("updated");
        assertEquals("updated", deliver.getMessage());

        deliver.setOriginalMessage("newOriginal");
        assertEquals("newOriginal", deliver.getOriginalMessage());

        deliver.setWriteLocally(false);
        assertFalse(deliver.isWriteLocally());

        deliver.setAsync(false);
        assertFalse(deliver.isAsync());

        var newFuture = new BroadcasterFuture<>("new");
        deliver.setFuture(newFuture);
        assertSame(newFuture, deliver.getFuture());
    }

    @Test
    void toStringContainsKeyFields() {
        var future = new BroadcasterFuture<>("msg");
        var deliver = new Deliver("hello", future, "original");

        var str = deliver.toString();
        assertTrue(str.contains("hello"));
        assertTrue(str.contains("ALL"));
    }

    @Test
    void copyConstructorPreservesFields() {
        var future = new BroadcasterFuture<>("msg");
        var original = new Deliver("msg", future, "orig");
        original.setAsync(false);

        var copy = new Deliver(null, original);

        assertEquals(Deliver.TYPE.RESOURCE, copy.getType());
        assertEquals("msg", copy.getMessage());
        assertEquals("orig", copy.getOriginalMessage());
        assertSame(future, copy.getFuture());
        assertFalse(copy.isAsync());
    }
}

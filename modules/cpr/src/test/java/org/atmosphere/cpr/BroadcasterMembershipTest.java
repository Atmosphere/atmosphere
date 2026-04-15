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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.Collection;
import java.util.Iterator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BroadcasterMembershipTest {

    private BroadcasterMembership membership;

    @BeforeEach
    void setUp() {
        membership = new BroadcasterMembership();
    }

    @Test
    void newMembershipIsEmpty() {
        assertTrue(membership.isEmpty());
        assertEquals(0, membership.size());
    }

    @Test
    void addIncreasesSize() {
        AtmosphereResource r1 = mock(AtmosphereResource.class);
        AtmosphereResource r2 = mock(AtmosphereResource.class);

        membership.add(r1);
        assertEquals(1, membership.size());
        assertFalse(membership.isEmpty());

        membership.add(r2);
        assertEquals(2, membership.size());
    }

    @Test
    void containsReturnsTrueForAddedResource() {
        AtmosphereResource r = mock(AtmosphereResource.class);
        membership.add(r);
        assertTrue(membership.contains(r));
    }

    @Test
    void containsReturnsFalseForUnknownResource() {
        AtmosphereResource r = mock(AtmosphereResource.class);
        assertFalse(membership.contains(r));
    }

    @Test
    void removeDeletesResource() {
        AtmosphereResource r = mock(AtmosphereResource.class);
        membership.add(r);

        boolean removed = membership.remove(r);

        assertTrue(removed);
        assertFalse(membership.contains(r));
        assertEquals(0, membership.size());
    }

    @Test
    void removeReturnsFalseForAbsentResource() {
        AtmosphereResource r = mock(AtmosphereResource.class);
        assertFalse(membership.remove(r));
    }

    @Test
    void clearRemovesAllMembers() {
        membership.add(mock(AtmosphereResource.class));
        membership.add(mock(AtmosphereResource.class));
        membership.add(mock(AtmosphereResource.class));

        membership.clear();

        assertTrue(membership.isEmpty());
        assertEquals(0, membership.size());
    }

    @Test
    void pollReturnsFirstAddedResource() {
        AtmosphereResource r1 = mock(AtmosphereResource.class);
        AtmosphereResource r2 = mock(AtmosphereResource.class);
        membership.add(r1);
        membership.add(r2);

        AtmosphereResource polled = membership.poll();

        assertSame(r1, polled);
        assertEquals(1, membership.size());
    }

    @Test
    void pollReturnsNullWhenEmpty() {
        assertNull(membership.poll());
    }

    @Test
    void iteratorTraversesAllMembers() {
        AtmosphereResource r1 = mock(AtmosphereResource.class);
        AtmosphereResource r2 = mock(AtmosphereResource.class);
        membership.add(r1);
        membership.add(r2);

        Iterator<AtmosphereResource> it = membership.iterator();
        int count = 0;
        while (it.hasNext()) {
            it.next();
            count++;
        }
        assertEquals(2, count);
    }

    @Test
    void getResourcesReturnsUnmodifiableView() {
        AtmosphereResource r = mock(AtmosphereResource.class);
        membership.add(r);

        Collection<AtmosphereResource> resources = membership.getResources();

        assertEquals(1, resources.size());
        assertTrue(resources.contains(r));
    }

    @Test
    void queueReturnsUnderlyingQueue() {
        AtmosphereResource r = mock(AtmosphereResource.class);
        membership.add(r);

        assertEquals(1, membership.queue().size());
        assertTrue(membership.queue().contains(r));
    }
}

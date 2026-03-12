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

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Encapsulates the resource membership concern for a {@link DefaultBroadcaster}.
 * <p>
 * Manages the collection of {@link AtmosphereResource} instances subscribed to a broadcaster,
 * providing thread-safe add, remove, and query operations. Extracted from {@link DefaultBroadcaster}
 * to separate membership management from the message dispatch hot path.
 *
 * @author Jeanfrancois Arcand
 */
public class BroadcasterMembership {

    private final ConcurrentLinkedQueue<AtmosphereResource> resources = new ConcurrentLinkedQueue<>();

    /**
     * Add an {@link AtmosphereResource} to the membership.
     *
     * @param r the resource to add
     */
    public void add(AtmosphereResource r) {
        resources.add(r);
    }

    /**
     * Remove an {@link AtmosphereResource} from the membership.
     *
     * @param r the resource to remove
     * @return true if the resource was removed
     */
    public boolean remove(AtmosphereResource r) {
        return resources.remove(r);
    }

    /**
     * Check if the membership contains the given {@link AtmosphereResource}.
     *
     * @param r the resource to check
     * @return true if the resource is a member
     */
    public boolean contains(AtmosphereResource r) {
        return resources.contains(r);
    }

    /**
     * Return true if there are no members.
     *
     * @return true if empty
     */
    public boolean isEmpty() {
        return resources.isEmpty();
    }

    /**
     * Return the number of members.
     *
     * @return the size
     */
    public int size() {
        return resources.size();
    }

    /**
     * Clear all members.
     */
    public void clear() {
        resources.clear();
    }

    /**
     * Poll the first resource (used for FIFO policy enforcement).
     *
     * @return the first resource, or null if empty
     */
    public AtmosphereResource poll() {
        return resources.poll();
    }

    /**
     * Return an iterator over the resources.
     *
     * @return an iterator
     */
    public Iterator<AtmosphereResource> iterator() {
        return resources.iterator();
    }

    /**
     * Return an unmodifiable view of the resources collection.
     *
     * @return an unmodifiable collection
     */
    public Collection<AtmosphereResource> getResources() {
        return Collections.unmodifiableCollection(resources);
    }

    /**
     * Return the underlying concurrent queue. This provides direct access for the
     * dispatch hot path and for subclasses that need to iterate over resources.
     *
     * @return the underlying queue
     */
    public ConcurrentLinkedQueue<AtmosphereResource> queue() {
        return resources;
    }
}

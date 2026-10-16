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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Instances held weakly and told apart by identity, never by
 * {@code equals}/{@code hashCode}: two distinct instances an implementation
 * declares equal (a record, or equality by name) are both kept, and an
 * instance nobody references any more is collected. Bounded by the instances
 * still referenced: the references of collected ones are dropped on every
 * {@link #addAll}. Not thread-safe: the caller synchronizes.
 *
 * @param <T> the instance type
 */
final class WeakIdentityInstances<T> {

    private final List<WeakReference<T>> references = new ArrayList<>();

    /**
     * Keep every instance of {@code instances} not already kept (by identity).
     */
    void addAll(Collection<? extends T> instances) {
        references.removeIf(reference -> reference.get() == null);
        for (var instance : instances) {
            if (!contains(instance)) {
                references.add(new WeakReference<>(instance));
            }
        }
    }

    private boolean contains(T instance) {
        for (var reference : references) {
            if (reference.get() == instance) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the instances kept and not collected, in the order they were
     *         added; afterwards none is kept
     */
    List<T> drain() {
        var live = new ArrayList<T>(references.size());
        for (var reference : references) {
            var instance = reference.get();
            if (instance != null) {
                live.add(instance);
            }
        }
        references.clear();
        return live;
    }
}

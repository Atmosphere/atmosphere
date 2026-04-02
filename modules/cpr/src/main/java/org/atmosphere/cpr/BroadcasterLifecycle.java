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

import org.atmosphere.lifecycle.LifecycleHandler;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.atmosphere.cpr.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.NEVER;

/**
 * Encapsulates the lifecycle management concern for a {@link DefaultBroadcaster}.
 * <p>
 * Manages lifecycle policy, lifecycle policy listeners, the lifecycle handler,
 * and the scheduled lifecycle task reference. Extracted from {@link DefaultBroadcaster}
 * to separate lifecycle management from the message dispatch hot path.
 *
 * @author Jeanfrancois Arcand
 */
public class BroadcasterLifecycle {

    private volatile BroadcasterLifeCyclePolicy lifeCyclePolicy = new BroadcasterLifeCyclePolicy.Builder()
            .policy(NEVER).build();

    private final ConcurrentLinkedQueue<BroadcasterLifeCyclePolicyListener> lifeCycleListeners =
            new ConcurrentLinkedQueue<>();

    private final AtomicBoolean recentActivity = new AtomicBoolean(false);

    private volatile LifecycleHandler lifecycleHandler;

    private volatile Future<?> currentLifecycleTask;

    /**
     * Return the current {@link BroadcasterLifeCyclePolicy}.
     *
     * @return the lifecycle policy
     */
    public BroadcasterLifeCyclePolicy policy() {
        return lifeCyclePolicy;
    }

    /**
     * Set the {@link BroadcasterLifeCyclePolicy} and activate the lifecycle handler if one is set.
     *
     * @param policy the new lifecycle policy
     * @param broadcaster the broadcaster to apply the policy on
     */
    public void setPolicy(BroadcasterLifeCyclePolicy policy, DefaultBroadcaster broadcaster) {
        this.lifeCyclePolicy = policy;
        if (lifecycleHandler != null) {
            lifecycleHandler.on(broadcaster);
        }
    }

    /**
     * Add a {@link BroadcasterLifeCyclePolicyListener}.
     *
     * @param listener the listener to add
     */
    public void addLifeCycleListener(BroadcasterLifeCyclePolicyListener listener) {
        lifeCycleListeners.add(listener);
    }

    /**
     * Remove a {@link BroadcasterLifeCyclePolicyListener}.
     *
     * @param listener the listener to remove
     */
    public void removeLifeCycleListener(BroadcasterLifeCyclePolicyListener listener) {
        lifeCycleListeners.remove(listener);
    }

    /**
     * Return the lifecycle policy listeners.
     *
     * @return the concurrent queue of lifecycle listeners
     */
    public ConcurrentLinkedQueue<BroadcasterLifeCyclePolicyListener> lifeCycleListeners() {
        return lifeCycleListeners;
    }

    /**
     * Return the {@link AtomicBoolean} tracking recent activity.
     *
     * @return the recent activity flag
     */
    public AtomicBoolean recentActivity() {
        return recentActivity;
    }

    /**
     * Return the current {@link LifecycleHandler}.
     *
     * @return the lifecycle handler, may be null
     */
    public LifecycleHandler lifecycleHandler() {
        return lifecycleHandler;
    }

    /**
     * Set the {@link LifecycleHandler}.
     *
     * @param handler the lifecycle handler
     */
    public void lifecycleHandler(LifecycleHandler handler) {
        this.lifecycleHandler = handler;
    }

    /**
     * Return the current scheduled lifecycle task.
     *
     * @return the lifecycle task future, may be null
     */
    public Future<?> currentLifecycleTask() {
        return currentLifecycleTask;
    }

    /**
     * Set the current scheduled lifecycle task.
     *
     * @param task the lifecycle task future
     */
    public void currentLifecycleTask(Future<?> task) {
        this.currentLifecycleTask = task;
    }

    /**
     * Clear all lifecycle listeners. Called during broadcaster destruction.
     */
    public void clearListeners() {
        lifeCycleListeners.clear();
    }
}

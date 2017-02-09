/*
 * Copyright 2017 Async-IO.org
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
package org.atmosphere.lifecycle;

import org.atmosphere.runtime.AtmosphereResource;
import org.atmosphere.runtime.Broadcaster;
import org.atmosphere.runtime.BroadcasterConfig;
import org.atmosphere.runtime.BroadcasterLifeCyclePolicy;
import org.atmosphere.runtime.BroadcasterLifeCyclePolicyListener;
import org.atmosphere.runtime.DefaultBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.atmosphere.runtime.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.EMPTY;
import static org.atmosphere.runtime.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.EMPTY_DESTROY;
import static org.atmosphere.runtime.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.IDLE;
import static org.atmosphere.runtime.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.IDLE_DESTROY;
import static org.atmosphere.runtime.BroadcasterLifeCyclePolicy.ATMOSPHERE_RESOURCE_POLICY.IDLE_RESUME;

public class LifecycleHandler {
    private static final Logger logger = LoggerFactory.getLogger(LifecycleHandler.class);

    public LifecycleHandler on(final DefaultBroadcaster broadcaster) {

        final BroadcasterLifeCyclePolicy lifeCyclePolicy = broadcaster.getBroadcasterLifeCyclePolicy();
        final BroadcasterConfig bc = broadcaster.getBroadcasterConfig();
        final Collection<AtmosphereResource>  resources = broadcaster.getAtmosphereResources();

        final AtomicBoolean recentActivity = broadcaster.recentActivity();

        if (logger.isTraceEnabled()) {
            logger.trace("{} new lifecycle policy: {}", broadcaster.getID(), lifeCyclePolicy.getLifeCyclePolicy().name());
        }

        if (broadcaster.currentLifecycleTask() != null) {
            broadcaster.currentLifecycleTask().cancel(false);
        }

        if (bc != null && bc.getScheduledExecutorService() == null) {
            logger.error("No Broadcaster's SchedulerExecutorService has been configured on {}. BroadcasterLifeCyclePolicy won't work.", broadcaster.getID());
            return this;
        }

        if (lifeCyclePolicy.getLifeCyclePolicy() == IDLE
                || lifeCyclePolicy.getLifeCyclePolicy() == IDLE_RESUME
                || lifeCyclePolicy.getLifeCyclePolicy() == IDLE_DESTROY) {

            recentActivity.set(false);

            int time = lifeCyclePolicy.getTimeout();
            if (time == -1) {
                throw new IllegalStateException("BroadcasterLifeCyclePolicy time is not set");
            }

            Future<?> currentLifecycleTask = bc.getScheduledExecutorService().scheduleAtFixedRate(new Runnable() {

                @Override
                public void run() {
                    try {

                        // Check for activity since the last execution.
                        if (recentActivity.getAndSet(false)) {
                            return;
                        } else if (resources.isEmpty()) {
                            if (lifeCyclePolicy.getLifeCyclePolicy() == IDLE) {
                                notifyEmptyListener(broadcaster);
                                notifyIdleListener(broadcaster);

                                broadcaster.releaseExternalResources();
                                logger.debug("Applying BroadcasterLifeCyclePolicy IDLE policy to Broadcaster {}", broadcaster.getID());
                                if (broadcaster.currentLifecycleTask() != null) {
                                    broadcaster.currentLifecycleTask().cancel(true);
                                }
                            } else if (lifeCyclePolicy.getLifeCyclePolicy() == IDLE_DESTROY) {
                                notifyEmptyListener(broadcaster);
                                notifyIdleListener(broadcaster);

                                destroy(false);
                                logger.debug("Applying BroadcasterLifeCyclePolicy IDLE_DESTROY policy to Broadcaster {}", broadcaster.getID());
                            }
                        } else if (lifeCyclePolicy.getLifeCyclePolicy() == IDLE_RESUME) {
                            notifyIdleListener(broadcaster);

                            destroy(true);
                            logger.debug("Applying BroadcasterLifeCyclePolicy IDLE_RESUME policy to Broadcaster {}", broadcaster.getID());
                        }
                    } catch (Throwable t) {
                        if (broadcaster.isDestroyed()) {
                            logger.trace("Scheduled BroadcasterLifeCyclePolicy exception", t);
                        } else {
                            logger.warn("Scheduled BroadcasterLifeCyclePolicy exception", t);
                        }
                    }
                }


                void destroy(boolean resume) {

                    if (resume) {
                        logger.info("All AtmosphereResource will now be resumed from Broadcaster {}", broadcaster.getID());
                        broadcaster.resumeAll();
                    }

                    broadcaster.destroy();
                    /**
                     * The value may be null if the timeout is too low. Hopefully next execution will
                     * cancel the task properly.
                     */
                    if (broadcaster.currentLifecycleTask() != null) {
                        broadcaster.currentLifecycleTask().cancel(true);
                    }
                }

            }, time, time, lifeCyclePolicy.getTimeUnit());
            broadcaster.currentLifecycleTask(currentLifecycleTask);
        }
        return this;
    }

    public LifecycleHandler offIfEmpty(DefaultBroadcaster broadcaster) {
        BroadcasterConfig bc = broadcaster.getBroadcasterConfig();
        Collection<AtmosphereResource>  resources = broadcaster.getAtmosphereResources();
        final BroadcasterLifeCyclePolicy lifeCyclePolicy = broadcaster.getBroadcasterLifeCyclePolicy();

        if (resources.isEmpty()) {
            notifyEmptyListener(broadcaster);
            if (broadcaster.getScope() != Broadcaster.SCOPE.REQUEST && lifeCyclePolicy.getLifeCyclePolicy() == EMPTY) {
                broadcaster.releaseExternalResources();
            } else if (broadcaster.getScope() == Broadcaster.SCOPE.REQUEST || lifeCyclePolicy.getLifeCyclePolicy() == EMPTY_DESTROY) {
                bc.getAtmosphereConfig().getBroadcasterFactory().remove(broadcaster, broadcaster.getID());
                broadcaster.destroy();
            }
        }
        return this;
    }

    public LifecycleHandler off(DefaultBroadcaster broadcaster) {
        Future<?> currentLifecycleTask = broadcaster.currentLifecycleTask();

        if (currentLifecycleTask != null) {
            currentLifecycleTask.cancel(true);
        }

        notifyDestroyListener(broadcaster);
        return this;
    }


    protected void notifyIdleListener(DefaultBroadcaster broadcaster) {
        for (BroadcasterLifeCyclePolicyListener b : broadcaster.lifeCycleListeners()) {
            b.onIdle();
        }
    }

    protected void notifyDestroyListener(DefaultBroadcaster broadcaster) {
            for (BroadcasterLifeCyclePolicyListener b : broadcaster.lifeCycleListeners()) {
            b.onDestroy();
        }
    }

    protected void notifyEmptyListener(DefaultBroadcaster broadcaster) {
            for (BroadcasterLifeCyclePolicyListener b : broadcaster.lifeCycleListeners()) {
            b.onEmpty();
        }
    }
}


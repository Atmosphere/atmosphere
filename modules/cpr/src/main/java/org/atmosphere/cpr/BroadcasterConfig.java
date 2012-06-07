/*
 * Copyright 2012 Jeanfrancois Arcand
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
/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package org.atmosphere.cpr;

import org.atmosphere.cache.BroadcasterCacheBase;
import org.atmosphere.cpr.BroadcastFilter.BroadcastAction;
import org.atmosphere.di.InjectorProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handle {@link Broadcaster} configuration like {@link ExecutorService} and
 * {@link BroadcastFilter}
 *
 * @author Jeanfrancois Arcand
 */
public class BroadcasterConfig {

    private static final Logger logger = LoggerFactory.getLogger(BroadcasterConfig.class);

    protected final ConcurrentLinkedQueue<BroadcastFilter> filters = new ConcurrentLinkedQueue<BroadcastFilter>();
    protected final ConcurrentLinkedQueue<PerRequestBroadcastFilter> perRequestFilters = new ConcurrentLinkedQueue<PerRequestBroadcastFilter>();
    private ExecutorService executorService;
    private ExecutorService asyncWriteService;
    private ExecutorService defaultExecutorService;
    private ExecutorService defaultAsyncWriteService;
    private ScheduledExecutorService scheduler;
    private final Object[] lock = new Object[0];
    private BroadcasterCache broadcasterCache;
    private AtmosphereConfig config;
    private boolean isExecutorShared = false;
    private boolean isAsyncExecutorShared = false;
    private boolean shared = false;

    public BroadcasterConfig(List<String> list, AtmosphereConfig config) {
        this(list, config, true);
    }

    public BroadcasterConfig(List<String> list, AtmosphereConfig config, boolean createExecutor) {
        this.config = config;
        if (createExecutor) {
            configExecutors();
        } else {
            shared = true;
        }
        configureBroadcasterFilter(list);
        configureBroadcasterCache();
    }

    public BroadcasterConfig(ExecutorService executorService, ExecutorService asyncWriteService,
                             ScheduledExecutorService scheduler, AtmosphereConfig config) {
        this.executorService = executorService;
        this.scheduler = scheduler;
        this.asyncWriteService = asyncWriteService;
        this.config = config;
    }

    private void configureBroadcasterCache() {
        try {
            if (AtmosphereFramework.broadcasterCacheClassName != null) {
                BroadcasterCache cache = (BroadcasterCache) Thread.currentThread().getContextClassLoader()
                        .loadClass(AtmosphereFramework.broadcasterCacheClassName).newInstance();
                InjectorProvider.getInjector().inject(cache);
                setBroadcasterCache(cache);
            }
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

    }

    protected synchronized void configExecutors() {
        String s = config.getInitParameter(ApplicationConfig.BROADCASTER_SHARABLE_THREAD_POOLS);
        if (Boolean.parseBoolean(s)) {
            isExecutorShared = true;
            isAsyncExecutorShared = true;
        }

        if (config.properties().get("executorService") == null) {
            int numberOfMessageProcessingThread = 1;
            s = config.getInitParameter(ApplicationConfig.BROADCASTER_MESSAGE_PROCESSING_THREADPOOL_MAXSIZE);
            if (s != null) {
                numberOfMessageProcessingThread = Integer.parseInt(s);
            }

            if (isExecutorShared && numberOfMessageProcessingThread == 1) {
                logger.warn("Not enough numberOfMessageProcessingThread for a shareable thread pool {}, " +
                        "Setting it to a newCachedThreadPool", numberOfMessageProcessingThread);
                numberOfMessageProcessingThread = -1;
            }

            int numberOfAsyncThread = 1;
            s = config.getInitParameter(ApplicationConfig.BROADCASTER_ASYNC_WRITE_THREADPOOL_MAXSIZE);
            if (s != null) {
                numberOfAsyncThread = Integer.parseInt(s);
            }

            if (isAsyncExecutorShared && numberOfAsyncThread == 1) {
                logger.warn("Not enough numberOfAsyncThread for a shareable thread pool {}, " +
                        "Setting it to a newCachedThreadPool", numberOfAsyncThread);
                numberOfAsyncThread = -1;
            }

            if (numberOfMessageProcessingThread == -1) {
                executorService = Executors.newCachedThreadPool(new ThreadFactory() {

                    private final AtomicInteger count = new AtomicInteger();

                    @Override
                    public Thread newThread(final Runnable runnable) {
                        Thread t = new Thread(runnable, "Atmosphere-BroadcasterConfig-" + count.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
                });
            } else {
                executorService = Executors.newFixedThreadPool(numberOfMessageProcessingThread, new ThreadFactory() {

                    private final AtomicInteger count = new AtomicInteger();

                    @Override
                    public Thread newThread(final Runnable runnable) {
                        Thread t = new Thread(runnable, "Atmosphere-BroadcasterConfig-" + count.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
                });
            }
            defaultExecutorService = executorService;

            if (numberOfAsyncThread == -1) {
                asyncWriteService = Executors.newCachedThreadPool(new ThreadFactory() {

                    private final AtomicInteger count = new AtomicInteger();

                    @Override
                    public Thread newThread(final Runnable runnable) {
                        Thread t = new Thread(runnable, "Atmosphere-AsyncWrite-" + count.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
                });
            } else {
                asyncWriteService = Executors.newFixedThreadPool(numberOfAsyncThread, new ThreadFactory() {

                    private final AtomicInteger count = new AtomicInteger();

                    @Override
                    public Thread newThread(final Runnable runnable) {
                        Thread t = new Thread(runnable, "Atmosphere-AsyncWrite-" + count.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
                });
            }
            defaultAsyncWriteService = asyncWriteService;

            if (isExecutorShared) {
                config.properties().put("executorService", executorService);
                config.properties().put("asyncWriteService", asyncWriteService);
            }

        } else {
            executorService = (ExecutorService) config.properties().get("executorService");
            defaultExecutorService = executorService;

            asyncWriteService = (ExecutorService) config.properties().get("asyncWriteService");
            defaultAsyncWriteService = asyncWriteService;
        }
    }

    /**
     * Set an {@link ExecutorService} which can be used to dispatch
     * {@link AtmosphereResourceEvent}. By default, an {@link Executors#newFixedThreadPool}
     * of size 1 is used if that method is not invoked.
     *
     * @param executorService to be used when broadcasting.
     */
    public BroadcasterConfig setExecutorService(ExecutorService executorService) {
        return setExecutorService(executorService, false);
    }

    /**
     * Set an {@link ExecutorService} which can be used to dispatch
     * {@link AtmosphereResourceEvent}. By default, an {@link Executors#newFixedThreadPool}
     * of size 1 is used if that method is not invoked.
     *
     * @param executorService  to be used when broadcasting.
     * @param isExecutorShared true is the life cycle of the {@link ExecutorService} will be executed by the application.
     *                         That means Atmosphere will NOT invoke the shutdown method when this {@link org.atmosphere.cpr.BroadcasterConfig#destroy()}
     */
    public BroadcasterConfig setExecutorService(ExecutorService executorService, boolean isExecutorShared) {
        if (!this.isExecutorShared && this.executorService != null) {
            this.executorService.shutdown();
        }
        this.executorService = executorService;
        this.isExecutorShared = isExecutorShared;
        return this;
    }

    /**
     * Return the {@link ExecutorService} this {@link Broadcaster} support.
     * By default it returns {@link java.util.concurrent.Executors#newFixedThreadPool(int)} of size 1.
     *
     * @return An ExecutorService.
     */
    public ExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * Set an {@link ExecutorService} which can be used to write
     * {@link org.atmosphere.cpr.AtmosphereResourceEvent#getMessage()}. By default, an {@link Executors#newFixedThreadPool}
     * is used if that method is not invoked.
     *
     * @param asyncWriteService to be used when writing events .
     */
    public BroadcasterConfig setAsyncWriteService(ExecutorService asyncWriteService) {
        return setAsyncWriteService(asyncWriteService, false);
    }

    /**
     * Set an {@link ExecutorService} which can be used to write
     * {@link org.atmosphere.cpr.AtmosphereResourceEvent#getMessage()}. By default, an {@link Executors#newFixedThreadPool}
     * is used if that method is not invoked.
     *
     * @param asyncWriteService     to be used when writing events .
     * @param isAsyncExecutorShared true is the life cycle of the {@link ExecutorService} will be executed by the application.
     *                              That means Atmosphere will NOT invoke the shutdown method when this {@link org.atmosphere.cpr.BroadcasterConfig#destroy()}
     */
    public BroadcasterConfig setAsyncWriteService(ExecutorService asyncWriteService, boolean isAsyncExecutorShared) {
        if (!this.isAsyncExecutorShared && this.asyncWriteService != null) {
            this.asyncWriteService.shutdown();
        }
        this.asyncWriteService = asyncWriteService;
        this.isAsyncExecutorShared = isAsyncExecutorShared;
        return this;
    }

    /**
     * Return the {@link ExecutorService} this {@link Broadcaster} use for executing asynchronous write of events.
     * By default it returns {@link java.util.concurrent.Executors#newCachedThreadPool()} of size 1.
     *
     * @return An ExecutorService.
     */
    public ExecutorService getAsyncWriteService() {
        return asyncWriteService;
    }

    /**
     * Add a {@link BroadcastFilter}
     *
     * @param e {@link BroadcastFilter}
     * @return true if added.
     */
    public boolean addFilter(BroadcastFilter e) {
        logDuplicateFilter(e);
        if (filters.contains(e)) return false;

        if (e instanceof BroadcastFilterLifecycle) {
            ((BroadcastFilterLifecycle) e).init();
        }

        if (e instanceof PerRequestBroadcastFilter) {
            perRequestFilters.add((PerRequestBroadcastFilter) e);
        }

        return filters.offer(e);
    }

    private void logDuplicateFilter(BroadcastFilter e) {
        for (BroadcastFilter f : filters) {
            if (f.getClass().isAssignableFrom(e.getClass())) {
                logger.trace("Duplicate Filter instance {}", f.getClass());
            }
        }
    }

    public void destroy() {
        destroy(false);
    }

    protected void destroy(boolean force) {
        if (shared) return;
        if (broadcasterCache != null) {
            broadcasterCache.stop();
        }

        if ((force || !isExecutorShared) && executorService != null) {
            executorService.shutdownNow();
        }
        if ((force || !isAsyncExecutorShared) && asyncWriteService != null) {
            asyncWriteService.shutdownNow();
        }
        if ((force || !isExecutorShared) && defaultExecutorService != null) {
            defaultExecutorService.shutdownNow();
        }
        if ((force || !isAsyncExecutorShared) && defaultAsyncWriteService != null) {
            defaultAsyncWriteService.shutdownNow();
        }

        if (scheduler != null) {
            scheduler.shutdownNow();
        }

        for (BroadcastFilter f : filters) {
            if (f instanceof BroadcastFilterLifecycle) {
                ((BroadcastFilterLifecycle) f).destroy();
            }
        }
        removeAllFilters();
    }

    /**
     * Force shutdown of all {@link ExecutorService}
     */
    public void forceDestroy() {
        destroy(true);
    }

    /**
     * Remove a {@link BroadcastFilter}
     *
     * @param filter {@link BroadcastFilter}
     * @return true if removed
     */
    public boolean removeFilter(BroadcastFilter filter) {

        if (filter instanceof BroadcastFilterLifecycle) {
            ((BroadcastFilterLifecycle) filter).destroy();
        }

        if (filter instanceof PerRequestBroadcastFilter) {
            perRequestFilters.remove(filter);
        }

        return filters.remove(filter);
    }

    /**
     * Remove all {@link BroadcastFilter}
     */
    public void removeAllFilters() {
        for (BroadcastFilter filter : filters) {
            removeFilter(filter);
        }
    }

    /**
     * Return true if this object contains {@link BroadcastFilter}
     *
     * @return true if this object contains {@link BroadcastFilter}
     */
    public boolean hasFilters() {
        return !filters.isEmpty();
    }

    /**
     * Return true if this object contains {@link BroadcastFilter}
     *
     * @return true if this object contains {@link BroadcastFilter}
     */
    public boolean hasPerRequestFilters() {
        if (filters.isEmpty()) {
            return false;
        } else {
            for (BroadcastFilter b : filters) {
                if (PerRequestBroadcastFilter.class.isAssignableFrom(b.getClass())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Invoke {@link BroadcastFilter} in the other they were added.
     *
     * @param object the broadcasted object.
     * @return BroadcastAction that tell Atmosphere to invoke the next filter or not.
     */
    protected BroadcastAction filter(Object object) {
        BroadcastAction transformed = new BroadcastAction(object);
        for (BroadcastFilter mf : filters) {
            synchronized (mf) {
                transformed = mf.filter(object, transformed.message());
                if (transformed == null || transformed.action() == BroadcastAction.ACTION.ABORT) {
                    return transformed;
                }
            }
        }
        return transformed;
    }

    /**
     * Invoke {@link BroadcastFilter} in the other they were added, with a unique {@link AtmosphereRequest}
     *
     * @param r {@link AtmosphereResource}
     * @param message the broadcasted object.
     * @param message the broadcasted object.
     * @return BroadcastAction that tell Atmosphere to invoke the next filter or not.
     */
    protected BroadcastAction filter(AtmosphereResource r, Object message, Object originalMessage) {
        BroadcastAction transformed = new BroadcastAction(originalMessage);
        for (PerRequestBroadcastFilter mf : perRequestFilters) {
            synchronized (mf) {
                transformed = mf.filter(r, message, transformed.message());
                if (transformed == null || transformed.action() == BroadcastAction.ACTION.ABORT) {
                    return transformed;
                }
            }
        }
        return transformed;
    }

    /**
     * Return the default {@link ExecutorService}.
     *
     * @return the defaultExecutorService
     */
    public ExecutorService getDefaultExecutorService() {
        return defaultExecutorService;
    }

    /**
     * Set an {@link ExecutorService} which can be used to dispatch
     * {@link AtmosphereResourceEvent}. By default, an {@link java.util.concurrent.ScheduledExecutorService}
     * is used if that method is not invoked.
     *
     * @param scheduler to be used when broadcasting.
     * @return this.
     */
    public BroadcasterConfig setScheduledExecutorService(ScheduledExecutorService scheduler) {
        if (this.scheduler != null) {
            this.scheduler.shutdown();
        }
        this.scheduler = scheduler;
        return this;
    }

    /**
     * Return the {@link ScheduledExecutorService} this {@link Broadcaster} support.
     * By default it returns {@link Executors#newScheduledThreadPool} and will use
     * the underlying number of core/protocol as an indication of the thread number.
     *
     * @return An ExecutorService.
     */
    public ScheduledExecutorService getScheduledExecutorService() {
        synchronized (lock) {
            if (scheduler == null) {
                scheduler = Executors.newSingleThreadScheduledExecutor();
            }
        }
        return scheduler;
    }

    /**
     * Set a {@link BroadcasterCache}
     *
     * @param broadcasterCache a {@link BroadcasterCache}
     * @return this
     */
    public BroadcasterConfig setBroadcasterCache(BroadcasterCache broadcasterCache) {
        this.broadcasterCache = broadcasterCache;
        if (BroadcasterCacheBase.class.isAssignableFrom(broadcasterCache.getClass())) {
            BroadcasterCacheBase.class.cast(broadcasterCache).setExecutorService(getScheduledExecutorService());
        }
        return this;
    }

    /**
     * Get a {@link BroadcasterCache}
     *
     * @return this
     */
    public BroadcasterCache getBroadcasterCache() {
        if (broadcasterCache == null) {
            broadcasterCache = new DefaultBroadcasterCache();
        }
        return broadcasterCache;
    }

    public static class DefaultBroadcasterCache implements BroadcasterCache {
        private final List<Object> list = new ArrayList<Object>();

        public void start() {
        }

        public void stop() {
        }

        public void addToCache(AtmosphereResource r, Object e) {
        }

        public List<Object> retrieveFromCache(AtmosphereResource r) {
            return list;
        }
    }

    void configureBroadcasterFilter(List<String> list) {
        for (String broadcastFilter : list) {
            BroadcastFilter bf = null;
            try {
                bf = BroadcastFilter.class
                        .cast(Thread.currentThread().getContextClassLoader().loadClass(broadcastFilter).newInstance());
            } catch (InstantiationException e) {
                logger.warn("Error trying to instantiate BroadcastFilter: " + broadcastFilter, e);
            } catch (IllegalAccessException e) {
                logger.warn("Error trying to instantiate BroadcastFilter: " + broadcastFilter, e);
            } catch (ClassNotFoundException e) {
                try {
                    bf = BroadcastFilter.class
                            .cast(BroadcastFilter.class.getClassLoader().loadClass(broadcastFilter).newInstance());
                } catch (InstantiationException e1) {
                } catch (IllegalAccessException e1) {
                } catch (ClassNotFoundException e1) {
                    logger.warn("Error trying to instantiate BroadcastFilter: " + broadcastFilter, e);
                }
            }
            if (bf != null) {
                InjectorProvider.getInjector().inject(bf);
                addFilter(bf);
            }

        }
    }

    /**
     * Return the {@link AtmosphereConfig} value. This value might be null
     * if the associated {@link Broadcaster} has been created manually.
     *
     * @return {@link AtmosphereConfig}
     */
    public AtmosphereConfig getAtmosphereConfig() {
        return config;
    }

    /**
     * Set the {@link AtmosphereConfig}
     *
     * @param config {@link AtmosphereConfig}
     */
    public void setAtmosphereConfig(AtmosphereConfig config) {
        this.config = config;
    }
}

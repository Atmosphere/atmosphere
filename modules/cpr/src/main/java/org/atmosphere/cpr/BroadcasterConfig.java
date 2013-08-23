/*
 * Copyright 2013 Jeanfrancois Arcand
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

import org.atmosphere.cache.BroadcasterCacheInspector;
import org.atmosphere.cpr.BroadcastFilter.BroadcastAction;
import org.atmosphere.di.InjectorProvider;
import org.atmosphere.util.ExecutorsFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

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
    private ScheduledExecutorService scheduler;
    private BroadcasterCache broadcasterCache = BroadcasterCache.DEFAULT;
    private final AtmosphereConfig config;
    private boolean isExecutorShared = false;
    private boolean isAsyncExecutorShared = false;
    private final boolean shared;
    private String name;
    private boolean handleExecutors;

    public BroadcasterConfig(List<String> list, AtmosphereConfig config, String name) {
        this(list, config, true, name);
    }

    public BroadcasterConfig(List<String> list, AtmosphereConfig config, boolean handleExecutors, String name) {
        this.config = config;
        this.name = name;
        this.shared = config.framework().isShareExecutorServices();

        if (handleExecutors) {
            configExecutors();
        }

        configureBroadcasterFilter(list);
        configureBroadcasterCache();
        this.handleExecutors = handleExecutors;
    }

    public BroadcasterConfig(ExecutorService executorService, ExecutorService asyncWriteService,
                             ScheduledExecutorService scheduler, AtmosphereConfig config, String name) {
        this.executorService = executorService;
        this.scheduler = scheduler;
        this.asyncWriteService = asyncWriteService;
        this.config = config;
        this.name = name;
        this.handleExecutors = true;
        this.shared = config.framework().isShareExecutorServices();
    }
    private void configureBroadcasterCache() {
        try {
            String className = config.framework().getBroadcasterCacheClassName();
            if (className != null) {
                try {
                    broadcasterCache = (BroadcasterCache) Thread.currentThread().getContextClassLoader()
                            .loadClass(className).newInstance();
                } catch (ClassNotFoundException ex) {
                    broadcasterCache = (BroadcasterCache) getClass().getClassLoader()
                            .loadClass(className).newInstance();
                }
                InjectorProvider.getInjector().inject(broadcasterCache);
                configureSharedCacheExecutor();
                broadcasterCache.configure(this);
            }

            for (BroadcasterCacheInspector b : config.framework().inspectors()) {
                broadcasterCache.inspector(b);
                InjectorProvider.getInjector().inject(b);
            }
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    protected void configureSharedCacheExecutor() {
        if (!shared) return;

        config.properties().put("shared", "true");
    }

    protected BroadcasterConfig broadcasterID(String name) {
        this.name = name;
        initClusterExtension();
        return this;
    }

    protected void initClusterExtension() {
        for (BroadcastFilter mf : filters) {
            if (ClusterBroadcastFilter.class.isAssignableFrom(mf.getClass())) {
                try {
                    Broadcaster b = config.getBroadcasterFactory().lookup(name, false);
                    if (b != null) {
                        synchronized (mf) {
                            ClusterBroadcastFilter.class.cast(mf).setBroadcaster(b);
                        }
                    }
                } catch (Throwable t) {
                    logger.error("", t);
                }
            }
        }
    }

    protected synchronized void configExecutors() {
        if (shared) {
            handleExecutors = false;
            isExecutorShared = true;
            isAsyncExecutorShared = true;
        }

        executorService = ExecutorsFactory.getMessageDispatcher(config, name);
        asyncWriteService = ExecutorsFactory.getAsyncOperationExecutor(config, name);
        scheduler = ExecutorsFactory.getScheduler(config);
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
        return addFilter(e, true);
    }

    /**
     * Add a {@link BroadcastFilter}
     *
     * @param e {@link BroadcastFilter}
     * @return true if added.
     */
    protected boolean addFilter(BroadcastFilter e, boolean init) {
        logDuplicateFilter(e);
        if (filters.contains(e)) return false;

        if (e instanceof BroadcastFilterLifecycle) {
            ((BroadcastFilterLifecycle) e).init(config);
        }

        if (init && ClusterBroadcastFilter.class.isAssignableFrom(e.getClass())) {
            Broadcaster b = config.getBroadcasterFactory().lookup(name, false);
            if (b != null) {
                synchronized (e) {
                    ClusterBroadcastFilter.class.cast(e).setBroadcaster(b);
                }
            }
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

    /**
     * Return the current list of installed {@link BroadcastFilter}
     * @return the current list of installed {@link BroadcastFilter}
     */
    public Collection<BroadcastFilter> filters(){
        return filters;
    }

    public void destroy() {
        destroy(false);
    }

    protected void destroy(boolean force) {
        if (!force && !handleExecutors) return;

        broadcasterCache.cleanup();
        if ((force || !shared) && broadcasterCache != null) {
            broadcasterCache.stop();
        }

        if ((force || !isExecutorShared) && executorService != null) {
            executorService.shutdownNow();
        }
        if ((force || !isAsyncExecutorShared) && asyncWriteService != null) {
            asyncWriteService.shutdownNow();
        }

        if ((force || !shared) && scheduler != null) {
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
                if (transformed == null
                        || transformed.action() == BroadcastAction.ACTION.ABORT
                        || transformed.action() == BroadcastAction.ACTION.SKIP) {
                    return transformed;
                }
            }
        }
        return transformed;
    }

    /**
     * Invoke {@link BroadcastFilter} in the other they were added, with a unique {@link AtmosphereRequest}
     *
     * @param r       {@link AtmosphereResource}
     * @param message the broadcasted object.
     * @param message the broadcasted object.
     * @return BroadcastAction that tell Atmosphere to invoke the next filter or not.
     */
    protected BroadcastAction filter(AtmosphereResource r, Object message, Object originalMessage)  {
        BroadcastAction transformed = new BroadcastAction(message);
        for (PerRequestBroadcastFilter mf : perRequestFilters) {
            synchronized (mf) {
                transformed = mf.filter(r, originalMessage, transformed.message());
                if (transformed == null
                        || transformed.action() == BroadcastAction.ACTION.ABORT
                        || transformed.action() == BroadcastAction.ACTION.SKIP) {
                    return transformed;
                }
            }
        }
        return transformed;
    }

    /**
     * Apply all filters to the {@link AtmosphereResource} and the provided {@link List} of messages.
     * @param r  {@link AtmosphereResource}
     * @param cacheMessages list of messages
     * @return the new list of objects.
     */
    public List<Object> applyFilters(AtmosphereResource r, List<Object> cacheMessages){
        LinkedList<Object> filteredMessage = new LinkedList<Object>();
        BroadcastFilter.BroadcastAction a;
        for (Object o : cacheMessages) {
            a = filter(o);
            if (a.action() == BroadcastFilter.BroadcastAction.ACTION.ABORT) return Collections.<Object>emptyList();

            if (a.action() == BroadcastAction.ACTION.SKIP) {
                filteredMessage.add(a.message());
                return filteredMessage;
            }

            a = filter(r, a.message(), a.originalMessage());
            if (a.action() == BroadcastFilter.BroadcastAction.ACTION.ABORT) return Collections.<Object>emptyList();

            if (a.action() == BroadcastAction.ACTION.SKIP) {
                filteredMessage.add(a.message());
                return filteredMessage;
            }

            filteredMessage.add(a.message());
        }
        return filteredMessage;
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
        return this;
    }

    /**
     * Get a {@link BroadcasterCache}
     *
     * @return this
     */
    public BroadcasterCache getBroadcasterCache() {
        return broadcasterCache;
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
}

/*
 * Copyright 2015 Async-IO.org
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

import org.atmosphere.cache.BroadcasterCacheInspector;
import org.atmosphere.cpr.BroadcastFilter.BroadcastAction;
import org.atmosphere.util.ExecutorsFactory;
import org.atmosphere.util.IOUtils;
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
 * Handle {@link Broadcaster} configuration like {@link ExecutorService} and {@link BroadcastFilter}.
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
    private boolean isExecutorShared;
    private boolean isAsyncExecutorShared;
    private final boolean shared;
    private String broadcasterId;
    private boolean handleExecutors;
    private List<String> filterList;

    /**
     * Create a new BroadcasterConfig. Remember to call init() after the object has been created.
     *
     * @param broadcastFilters
     * @param config
     * @param broadcasterId
     */
    public BroadcasterConfig(List<String> broadcastFilters, AtmosphereConfig config, String broadcasterId) {
        this(broadcastFilters, config, true, broadcasterId);
    }

    /**
     * Create a new BroadcasterConfig. Remember to call init() after the object has been created.
     *
     * @param broadcastFilters
     * @param config
     * @param handleExecutors
     * @param broadcasterId
     */
    public BroadcasterConfig(List<String> broadcastFilters, AtmosphereConfig config, boolean handleExecutors, String broadcasterId) {
        this.config = config;
        this.broadcasterId = broadcasterId;
        this.shared = config.framework().isShareExecutorServices();
        this.handleExecutors = handleExecutors;
        this.filterList = broadcastFilters;
    }

    /**
     * Create a new BroadcasterConfig. Remember to call init() after the object has been created.
     *
     * @param executorService
     * @param asyncWriteService
     * @param scheduler
     * @param config
     * @param broadcasterId
     */
    public BroadcasterConfig(ExecutorService executorService, ExecutorService asyncWriteService,
                             ScheduledExecutorService scheduler, AtmosphereConfig config, String broadcasterId) {
        this.executorService = executorService;
        this.scheduler = scheduler;
        this.asyncWriteService = asyncWriteService;
        this.config = config;
        this.broadcasterId = broadcasterId;
        this.handleExecutors = true;
        this.shared = config.framework().isShareExecutorServices();
    }

    /**
     * Initialize BroadcastFilters and BroadcasterCache. Must always be called after creating a new BroadcasterConfig!
     */
    public BroadcasterConfig init() {
        if (handleExecutors) {
            configExecutors();
        }

        if (filterList != null) {
            configureBroadcasterFilter(filterList);
        }
        configureBroadcasterCache();
        return this;
    }

    private void configureBroadcasterCache() {
        try {
            String className = config.framework().getBroadcasterCacheClassName();
            if (className != null) {
                broadcasterCache = config.framework().newClassInstance(BroadcasterCache.class,
                        (Class<BroadcasterCache>) IOUtils.loadClass(getClass(), className));
                configureSharedCacheExecutor();
                broadcasterCache.configure(config);
            }

            for (BroadcasterCacheInspector b : config.framework().inspectors()) {
                broadcasterCache.inspector(b);
            }

            for (BroadcasterCacheListener l : config.framework().broadcasterCacheListeners()) {
                broadcasterCache.addBroadcasterCacheListener(l);
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected void configureSharedCacheExecutor() {
        if (!shared) return;

        config.properties().put("shared", "true");
    }

    protected BroadcasterConfig broadcasterID(String broadcasterId) {
        this.broadcasterId = broadcasterId;
        initClusterExtension();
        return this;
    }

    protected void initClusterExtension() {
        for (BroadcastFilter mf : filters) {
            if (ClusterBroadcastFilter.class.isAssignableFrom(mf.getClass())) {
                try {
                    Broadcaster b = config.getBroadcasterFactory().lookup(broadcasterId, false);
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

    public boolean handleExecutors() {
        return handleExecutors;
    }

    protected synchronized void configExecutors() {
        if (shared) {
            handleExecutors = false;
            isExecutorShared = true;
            isAsyncExecutorShared = true;
        }

        executorService = ExecutorsFactory.getMessageDispatcher(config, broadcasterId);
        asyncWriteService = ExecutorsFactory.getAsyncOperationExecutor(config, broadcasterId);
        scheduler = ExecutorsFactory.getScheduler(config);
    }

    /**
     * Set an {@link ExecutorService} which can be used to dispatch
     * {@link AtmosphereResourceEvent}. By default, the result of {@link ExecutorsFactory#getMessageDispatcher(AtmosphereConfig, String)}
     * is used if this method is not invoked.
     *
     * @param executorService to be used when broadcasting.
     */
    public BroadcasterConfig setExecutorService(ExecutorService executorService) {
        return setExecutorService(executorService, false);
    }

    /**
     * Set an {@link ExecutorService} which can be used to dispatch
     * {@link AtmosphereResourceEvent}. By default, the result of {@link ExecutorsFactory#getMessageDispatcher(AtmosphereConfig, String)}
     * is used if this method is not invoked.
     *
     * @param executorService  to be used when broadcasting.
     * @param isExecutorShared true if the life cycle of the {@link ExecutorService} will be executed by the application.
     *                         It means Atmosphere will NOT invoke the shutdown method when {@link org.atmosphere.cpr.BroadcasterConfig#destroy()}
     *                         is invoked.
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
     * Return the {@link ExecutorService} this {@link Broadcaster} supports.
     * By default it returns the result of {@link ExecutorsFactory#getMessageDispatcher(AtmosphereConfig, String)}}.
     *
     * @return An ExecutorService.
     */
    public ExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * Set an {@link ExecutorService} which can be used to write
     * {@link org.atmosphere.cpr.AtmosphereResourceEvent#getMessage()}. By default, the result of {@link ExecutorsFactory#getAsyncOperationExecutor(AtmosphereConfig, String)}
     * is used if this method is not invoked.
     *
     * @param asyncWriteService to be used when writing events.
     */
    public BroadcasterConfig setAsyncWriteService(ExecutorService asyncWriteService) {
        return setAsyncWriteService(asyncWriteService, false);
    }

    /**
     * Set an {@link ExecutorService} which can be used to write
     * {@link org.atmosphere.cpr.AtmosphereResourceEvent#getMessage()}. By default, the result of {@link ExecutorsFactory#getAsyncOperationExecutor(AtmosphereConfig, String)}
     * is used if this method is not invoked.
     *
     * @param asyncWriteService     to be used when writing events.
     * @param isAsyncExecutorShared true if the life cycle of the {@link ExecutorService} will be executed by the application.
     *                              It means Atmosphere will NOT invoke the shutdown method when this {@link org.atmosphere.cpr.BroadcasterConfig#destroy()}
     *                              is invoked.
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
     * By default it returns the result of {@link ExecutorsFactory#getAsyncOperationExecutor(AtmosphereConfig, String)}.
     *
     * @return An ExecutorService.
     */
    public ExecutorService getAsyncWriteService() {
        return asyncWriteService;
    }

    /**
     * Add a {@link BroadcastFilter}.
     *
     * @param e {@link BroadcastFilter}
     * @return true if successfully added
     */
    public boolean addFilter(BroadcastFilter e) {
        return addFilter(e, true);
    }

    /**
     * Add a {@link BroadcastFilter}.
     *
     * @param e {@link BroadcastFilter}
     * @return true if successfully added
     */
    protected boolean addFilter(BroadcastFilter e, boolean init) {
        logDuplicateFilter(e);
        if (filters.contains(e)) return false;

        if (e instanceof BroadcastFilterLifecycle) {
            ((BroadcastFilterLifecycle) e).init(config);
        }

        if (init && ClusterBroadcastFilter.class.isAssignableFrom(e.getClass())) {
            Broadcaster b = config.getBroadcasterFactory().lookup(broadcasterId, false);
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
     * Return the current list of installed {@link BroadcastFilter}s.
     *
     * @return the current list of installed {@link BroadcastFilter}s
     */
    public Collection<BroadcastFilter> filters() {
        return filters;
    }

    public void destroy() {
        destroy(false);
    }

    protected void destroy(boolean force) {

        broadcasterCache.cleanup();
        if ((force || !shared) && broadcasterCache != null) {
            broadcasterCache.stop();
        }

        removeAllFilters();

        if (!force && !handleExecutors) return;

        if ((force || !isExecutorShared) && executorService != null) {
            executorService.shutdownNow();
        }
        if ((force || !isAsyncExecutorShared) && asyncWriteService != null) {
            asyncWriteService.shutdownNow();
        }

        if ((force || !shared) && scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * Force shutdown of all {@link ExecutorService}s.
     */
    public void forceDestroy() {
        destroy(true);
    }

    /**
     * Remove a {@link BroadcastFilter}.
     *
     * @param filter {@link BroadcastFilter}
     * @return true if successfully removed
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
     * Remove all {@link BroadcastFilter}s.
     */
    public void removeAllFilters() {
        for (BroadcastFilter filter : filters) {
            removeFilter(filter);
        }
    }

    /**
     * Check if this object contains {@link BroadcastFilter}s.
     *
     * @return true if this object contains {@link BroadcastFilter}s
     */
    public boolean hasFilters() {
        return !filters.isEmpty();
    }

    /**
     * Check if this object contains {@link BroadcastFilter}s.
     *
     * @return true if this object contains {@link BroadcastFilter}s
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
     * Invoke {@link BroadcastFilter}s in the order they were added.
     *
     * @param object the broadcasted object.
     * @return BroadcastAction that tell Atmosphere to invoke the next filter or not.
     */
    protected BroadcastAction filter(Object object) {

        Object newO = unwrap(object);
        boolean isManipulated = false;
        if (newO != null && !newO.equals(object)) {
            isManipulated = true;
            object = newO;
        }

        BroadcastAction transformed = new BroadcastAction(object);
        for (BroadcastFilter mf : filters) {
            synchronized (mf) {
                transformed = mf.filter(broadcasterId, object, transformed.message());
                if (transformed == null
                        || transformed.action() == BroadcastAction.ACTION.ABORT
                        || transformed.action() == BroadcastAction.ACTION.SKIP) {
                    return transformed;
                }
            }
        }
        return wrap(transformed, isManipulated);
    }

    /**
     * Invoke {@link BroadcastFilter}s in the order they were added, with a unique {@link AtmosphereRequest}.
     *
     * @param r       {@link AtmosphereResource}
     * @param message the broadcasted object.
     * @param message the broadcasted object.
     * @return BroadcastAction that tell Atmosphere to invoke the next filter or not.
     */
    protected BroadcastAction filter(AtmosphereResource r, Object message, Object originalMessage) {

        Object newO = unwrap(message);
        boolean isManipulated = false;
        if (!newO.equals(message)) {
            isManipulated = true;
            message = newO;
        }

        BroadcastAction transformed = new BroadcastAction(message);
        for (PerRequestBroadcastFilter mf : perRequestFilters) {
            synchronized (mf) {
                transformed = mf.filter(broadcasterId, r, originalMessage, transformed.message());
                if (transformed == null
                        || transformed.action() == BroadcastAction.ACTION.ABORT
                        || transformed.action() == BroadcastAction.ACTION.SKIP) {
                    return transformed;
                }
            }
        }
        return wrap(transformed, isManipulated);
    }

    /**
     * Apply all filters to the {@link AtmosphereResource} and the provided {@link List} of messages.
     *
     * @param r             {@link AtmosphereResource}
     * @param cacheMessages list of messages
     * @return the new list of objects.
     */
    public List<Object> applyFilters(AtmosphereResource r, List<Object> cacheMessages) {
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
     * {@link AtmosphereResourceEvent}s. By default a {@link java.util.concurrent.ScheduledExecutorService}
     * is used if this method is not invoked.
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
     * Return the {@link ScheduledExecutorService} this {@link Broadcaster} supports.
     * By default it returns an {@link Executors#newScheduledThreadPool} and will use
     * the underlying number of core/protocol as an indication of the thread number.
     *
     * @return An ExecutorService.
     */
    public ScheduledExecutorService getScheduledExecutorService() {
        return scheduler;
    }

    /**
     * Set a {@link BroadcasterCache}.
     *
     * @param broadcasterCache a {@link BroadcasterCache}
     * @return this
     */
    public BroadcasterConfig setBroadcasterCache(BroadcasterCache broadcasterCache) {
        this.broadcasterCache = broadcasterCache;
        return this;
    }

    /**
     * Get the {@link BroadcasterCache} used for this {@link Broadcaster}.
     *
     * @return a {@link BroadcasterCache}
     */
    public BroadcasterCache getBroadcasterCache() {
        return broadcasterCache;
    }

    void configureBroadcasterFilter(List<String> list) {
        for (String broadcastFilter : list) {
            BroadcastFilter bf = null;
            try {
                bf = config.framework().newClassInstance(BroadcastFilter.class, (Class<BroadcastFilter>) IOUtils.loadClass(getClass(), broadcastFilter));
            } catch (Exception e) {
                logger.warn("Error trying to instantiate BroadcastFilter: {}", broadcastFilter, e);
            }
            if (bf != null) {
                addFilter(bf);
            }
        }
    }

    protected Object unwrap(Object o) {
        Object manipulated = o;
        for (FilterManipulator f: config.framework().filterManipulators()) {
            manipulated = f.unwrap(manipulated);
        }
        return manipulated;
    }

    protected BroadcastAction wrap(BroadcastAction a, boolean wasUnwraped) {
        for (FilterManipulator f: config.framework().filterManipulators()) {
            a = f.wrap(a, wasUnwraped);
        }
        return a;
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
     * Manipulate the message before and after they are getting filtered by {@link org.atmosphere.cpr.BroadcastFilter}
     */
    public static interface FilterManipulator {

        Object unwrap(Object o);

        BroadcastAction wrap(BroadcastAction a, boolean wasWrapped);
    }
}

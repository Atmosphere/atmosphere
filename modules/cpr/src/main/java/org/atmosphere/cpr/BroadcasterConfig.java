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

import org.atmosphere.cpr.BroadcastFilter.BroadcastAction;
import org.atmosphere.util.LoggerUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handle {@link Broadcaster} configuration like {@link ExecutorService} and
 * {@link BroadcastFilter}
 *
 * @author Jeanfrancois Arcand
 */
public class BroadcasterConfig {
    public final static Logger logger = LoggerUtils.getLogger();

    protected final ConcurrentLinkedQueue<BroadcastFilter> filters =
            new ConcurrentLinkedQueue<BroadcastFilter>();

    protected final ConcurrentLinkedQueue<PerRequestBroadcastFilter> perRequestFilters =
            new ConcurrentLinkedQueue<PerRequestBroadcastFilter>();

    private ExecutorService executorService;

    private ExecutorService asyncWriteService;

    private ExecutorService defaultExecutorService;

    private ExecutorService defaultAsyncWriteService;

    private ScheduledExecutorService scheduler;

    private final Object[] lock = new Object[0];

    private BroadcasterCache broadcasterCache;
    private AtmosphereServlet.AtmosphereConfig config;

    public BroadcasterConfig(String[] list, AtmosphereServlet.AtmosphereConfig config) {
        this.config = config;
        configExecutors();
        configureBroadcasterFilter(list);
        configureBroadcasterCache();
    }

    private void configureBroadcasterCache() {
        try {
            if (AtmosphereServlet.broadcasterCacheClassName != null) {
                setBroadcasterCache((BroadcasterCache)
                        Thread.currentThread().getContextClassLoader()
                                .loadClass(AtmosphereServlet.broadcasterCacheClassName).newInstance());
            }
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

    }

    public BroadcasterConfig(ExecutorService executorService, ExecutorService asyncWriteService, ScheduledExecutorService scheduler, AtmosphereServlet.AtmosphereConfig config) {
        this.executorService = executorService;
        this.scheduler = scheduler;
        this.asyncWriteService = asyncWriteService;
        this.config = config;
    }

    protected void configExecutors() {
        executorService = Executors.newSingleThreadExecutor(new ThreadFactory() {

            private AtomicInteger count = new AtomicInteger();

            @Override
            public Thread newThread(final Runnable runnable) {
                return new Thread(runnable, "Atmosphere-BroadcasterConfig-" + count.getAndIncrement());
            }
        });
        defaultExecutorService = executorService;

        asyncWriteService = Executors.newCachedThreadPool(new ThreadFactory() {

            private AtomicInteger count = new AtomicInteger();

            @Override
            public Thread newThread(final Runnable runnable) {
                return new Thread(runnable, "Atmosphere-AsyncWrite-" + count.getAndIncrement());
            }
        });
        defaultAsyncWriteService = asyncWriteService;
    }

    /**
     * Set an {@link ExecutorService} which can be used to dispatch
     * {@link AtmosphereResourceEvent}. By default, an {@link Executors#newFixedThreadPool}
     * of size 1 is used if that method is not invoked.
     *
     * @param executorService to be used when broadcasting.
     */
    public BroadcasterConfig setExecutorService(ExecutorService executorService) {
        if (this.executorService != null) {
            this.executorService.shutdown();
        }
        this.executorService = executorService;
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
        if (this.asyncWriteService != null) {
            this.asyncWriteService.shutdown();
        }
        this.asyncWriteService = asyncWriteService;
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
        if (filters.contains(e) || checkDuplicateFilter(e)) return false;

        if (e instanceof BroadcastFilterLifecycle) {
            ((BroadcastFilterLifecycle) e).init();
        }

        if (e instanceof PerRequestBroadcastFilter) {
            perRequestFilters.add((PerRequestBroadcastFilter)e);
        }

        return filters.offer(e);
    }

    private boolean checkDuplicateFilter(BroadcastFilter e) {
        for (BroadcastFilter f: filters) {
            if (f.getClass().isAssignableFrom(e.getClass())) {
                return true;
            }
        }
        return false;
    }

    public void destroy() {
        if (broadcasterCache != null) {
            broadcasterCache.stop();
        }

        if (executorService != null) {
            executorService.shutdown();
        }
        if (asyncWriteService != null) {
            asyncWriteService.shutdown();
        }
        if (defaultExecutorService != null) {
            defaultExecutorService.shutdown();
        }
        if (defaultAsyncWriteService != null) {
            defaultAsyncWriteService.shutdown();
        }

        if (scheduler != null) {
            scheduler.shutdown();
        }

        for (BroadcastFilter f : filters) {
            if (f instanceof BroadcastFilterLifecycle) {
                ((BroadcastFilterLifecycle) f).destroy();
            }
        }
        removeAllFilters();
    }

    /**
     * Remove a {@link BroadcastFilter}
     *
     * @param e {@link BroadcastFilter}
     * @return true if removed
     */
    public boolean removeFilter(BroadcastFilter e) {

        if (e instanceof BroadcastFilterLifecycle) {
            ((BroadcastFilterLifecycle) e).destroy();
        }

        if (e instanceof PerRequestBroadcastFilter) {
            perRequestFilters.remove(e);
        }
        
        return filters.remove(e);
    }

    /**
     * Remove all {@link BroadcastFilter}
     */
    public void removeAllFilters() {
        for (BroadcastFilter e : filters) {
            removeFilter(e);
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
            for(BroadcastFilter b: filters) {
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
            transformed = mf.filter(object, transformed.message());
            if (transformed == null || transformed.action() == BroadcastAction.ACTION.ABORT) {
                return transformed;
            }
        }
        return transformed;
    }

    /**
     * Invoke {@link BroadcastFilter} in the other they were added, with a unique {@link javax.servlet.http.HttpServletRequest}
     * @param request {@link javax.servlet.http.HttpServletRequest}
     * @param object the broadcasted object.
     * @return BroadcastAction that tell Atmosphere to invoke the next filter or not.
     */
    protected BroadcastAction filter(HttpServletRequest request, HttpServletResponse response, Object object) {
        BroadcastAction transformed = new BroadcastAction(object);
        for (PerRequestBroadcastFilter mf : perRequestFilters) {
            transformed = mf.filter(request, response, transformed.message());
            if (transformed == null || transformed.action() == BroadcastAction.ACTION.ABORT) {
                return transformed;
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
     * the underlying number of core/processor as an indication of the thread number.
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

    void configureBroadcasterFilter(String[] list) {
        for (String broadcastFilter : list) {
            try {
                addFilter(BroadcastFilter.class.cast(
                        Thread.currentThread().getContextClassLoader().loadClass(broadcastFilter).newInstance()));
            } catch (InstantiationException e) {
                logger.log(Level.WARNING, String.format("Error trying to instanciate BroadcastFilter %s", broadcastFilter), e);
            } catch (IllegalAccessException e) {
                logger.log(Level.WARNING, String.format("Error trying to instanciate BroadcastFilter %s", broadcastFilter), e);
            } catch (ClassNotFoundException e) {
                logger.log(Level.WARNING, String.format("Error trying to instanciate BroadcastFilter %s", broadcastFilter), e);
            }
        }
    }

    /**
     * Return the {@link org.atmosphere.cpr.AtmosphereServlet.AtmosphereConfig} value. This value might be null
     * if the associated {@link Broadcaster} has been created manually.
     * @return {@link org.atmosphere.cpr.AtmosphereServlet.AtmosphereConfig}
     */
    public AtmosphereServlet.AtmosphereConfig getAtmosphereConfig() {
        return config;
    }

    /**
     * Set the {@link org.atmosphere.cpr.AtmosphereServlet.AtmosphereConfig}
     * @param config {@link org.atmosphere.cpr.AtmosphereServlet.AtmosphereConfig}
     */
    public void setAtmosphereConfig(AtmosphereServlet.AtmosphereConfig config) {
        this.config = config;
    }

}

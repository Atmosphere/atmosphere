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
package org.atmosphere.util;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Factory to create {@link ExecutorService} for Atmosphere components.
 * Uses Virtual Threads (JDK 21+) by default for unlimited scalability.
 *
 * @author Jeanfrancois Arcand
 */
public class ExecutorsFactory {

    private final static Logger logger = LoggerFactory.getLogger(ExecutorsFactory.class);
    public final static int DEFAULT_ASYNC_THREAD = 200;
    public final static int DEFAULT_MESSAGE_THREAD = -1;
    public final static int DEFAULT_KEEP_ALIVE = 30;

    public final static String ASYNC_WRITE_THREAD_POOL = "asyncWriteService";
    public final static String SCHEDULER_THREAD_POOL = "scheduler";
    public final static String BROADCASTER_THREAD_POOL = "executorService";

    public final static class AtmosphereThreadFactory implements ThreadFactory {
        private final AtomicInteger count = new AtomicInteger();
        private final boolean shared;
        private final String name;

        public AtmosphereThreadFactory(boolean shared, String name) {
            this.shared = shared;
            this.name = name;
        }

        @Override
        public Thread newThread(final Runnable runnable) {
            Thread t = new Thread(runnable, (shared ? "Atmosphere-Shared-" : name) + count.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }

    /**
     * Create an {@link ExecutorService} to be used for dispatching messages.
     * Uses Virtual Threads by default for unlimited scalability.
     */
    public static ExecutorService getMessageDispatcher(final AtmosphereConfig config, final String name) {
        final boolean shared = config.framework().isShareExecutorServices();
        
        if (!shared || config.properties().get(BROADCASTER_THREAD_POOL) == null) {
            boolean useVirtualThreads = config.getInitParameter(ApplicationConfig.USE_VIRTUAL_THREADS, true);
            ExecutorService service;
            
            if (useVirtualThreads) {
                logger.info("Using Virtual Threads for message dispatching (unlimited scalability)");
                service = Executors.newVirtualThreadPerTaskExecutor();
            } else {
                // Traditional thread pool only when virtual threads explicitly disabled
                int threads = config.getInitParameter(
                    ApplicationConfig.BROADCASTER_MESSAGE_PROCESSING_THREADPOOL_MAXSIZE, 
                    DEFAULT_MESSAGE_THREAD
                );
                
                if (threads == -1) {
                    logger.info("Using cached thread pool for message dispatching");
                    service = Executors.newCachedThreadPool(
                        new AtmosphereThreadFactory(shared, name + "-DispatchOp-")
                    );
                } else {
                    logger.info("Using fixed thread pool ({} threads) for message dispatching", threads);
                    service = Executors.newFixedThreadPool(threads,
                        new AtmosphereThreadFactory(shared, name + "-DispatchOp-")
                    );
                }
                
                configureThreadPool(service, config);
            }

            if (shared) {
                config.properties().put(BROADCASTER_THREAD_POOL, service);
            }
            return service;
        } else {
            return (ExecutorService) config.properties().get(BROADCASTER_THREAD_POOL);
        }
    }

    /**
     * Create an {@link ExecutorService} for async I/O operations.
     * Uses Virtual Threads by default - perfect for I/O-bound work.
     */
    public static ExecutorService getAsyncOperationExecutor(final AtmosphereConfig config, final String name) {
        final boolean shared = config.framework().isShareExecutorServices();

        if (!shared || config.properties().get(ASYNC_WRITE_THREAD_POOL) == null) {
            boolean useVirtualThreads = config.getInitParameter(ApplicationConfig.USE_VIRTUAL_THREADS, true);
            ExecutorService service;

            if (useVirtualThreads) {
                logger.info("Using Virtual Threads for async I/O (unlimited scalability)");
                service = Executors.newVirtualThreadPerTaskExecutor();
            } else {
                // Traditional thread pool only when virtual threads explicitly disabled
                int threads = config.getInitParameter(
                    ApplicationConfig.BROADCASTER_ASYNC_WRITE_THREADPOOL_MAXSIZE,
                    DEFAULT_ASYNC_THREAD
                );
                
                if (threads == -1) {
                    logger.info("Using cached thread pool for async I/O");
                    service = Executors.newCachedThreadPool(
                        new AtmosphereThreadFactory(shared, name + "-AsyncOp-")
                    );
                } else {
                    logger.info("Using fixed thread pool ({} threads) for async I/O", threads);
                    service = Executors.newFixedThreadPool(threads,
                        new AtmosphereThreadFactory(shared, name + "-AsyncOp-")
                    );
                }
                
                configureThreadPool(service, config);
            }

            if (shared) {
                config.properties().put(ASYNC_WRITE_THREAD_POOL, service);
            }
            return service;
        } else {
            return (ExecutorService) config.properties().get(ASYNC_WRITE_THREAD_POOL);
        }
    }

    /**
     * Create a {@link ScheduledExecutorService} for scheduled tasks.
     */
    public static ScheduledExecutorService getScheduler(final AtmosphereConfig config) {
        final boolean shared = config.framework().isShareExecutorServices();

        if (!shared || config.properties().get(SCHEDULER_THREAD_POOL) == null) {
            int threads = config.getInitParameter(
                ApplicationConfig.SCHEDULER_THREADPOOL_MAXSIZE,
                Runtime.getRuntime().availableProcessors()
            );
            
            logger.info("Creating scheduler with {} threads", threads);
            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(threads, new ThreadFactory() {
                private final AtomicInteger count = new AtomicInteger();

                @Override
                public Thread newThread(final Runnable runnable) {
                    Thread t = new Thread(runnable, "Atmosphere-Scheduler-" + count.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            });

            if (shared) {
                config.properties().put(SCHEDULER_THREAD_POOL, scheduler);
            }

            configureThreadPool(scheduler, config);
            return scheduler;
        } else {
            return (ScheduledExecutorService) config.properties().get(SCHEDULER_THREAD_POOL);
        }
    }

    private static void configureThreadPool(ExecutorService service, AtmosphereConfig config) {
        if (service instanceof ThreadPoolExecutor executor) {
            int keepAlive = config.getInitParameter(
                ApplicationConfig.EXECUTORFACTORY_KEEP_ALIVE,
                DEFAULT_KEEP_ALIVE
            );
            executor.setKeepAliveTime(keepAlive, TimeUnit.SECONDS);
            executor.allowCoreThreadTimeOut(
                config.getInitParameter(ApplicationConfig.ALLOW_CORE_THREAD_TIMEOUT, true)
            );
        }
    }

    public static void reset(AtmosphereConfig config) {
        shutdown(config, ASYNC_WRITE_THREAD_POOL);
        shutdown(config, SCHEDULER_THREAD_POOL);
        shutdown(config, BROADCASTER_THREAD_POOL);
    }
    
    private static void shutdown(AtmosphereConfig config, String poolName) {
        ExecutorService service = (ExecutorService) config.properties().get(poolName);
        if (service != null) {
            service.shutdown();
        }
        config.properties().remove(poolName);
    }
}

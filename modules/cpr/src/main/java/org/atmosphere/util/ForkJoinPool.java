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
package org.atmosphere.util;

import org.atmosphere.cpr.ApplicationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wrapper around a ForkJoinPool for JDK7+ support.
 *
 * @author Jean-Francois Arcand
 */
public class ForkJoinPool extends AbstractExecutorService {

    private final static Logger logger = LoggerFactory.getLogger(ForkJoinPool.class);

    private final AbstractExecutorService forkJoinPool;
    private final boolean shared;

    public ForkJoinPool(boolean shared, final String threadName) {
        this.shared = shared;

        forkJoinPool = new java.util.concurrent.ForkJoinPool(Runtime.getRuntime().availableProcessors(), new java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory() {
            @Override
            public java.util.concurrent.ForkJoinWorkerThread newThread(java.util.concurrent.ForkJoinPool pool) {
                return new JDK7ForkJoinWorkerThread(pool, ForkJoinPool.this.shared, threadName);
            }
        }, null, false);
        logger.info("Using ForkJoinPool  {}. Set the {} to -1 to fully use its power.", forkJoinPool.getClass().getName(), ApplicationConfig.BROADCASTER_ASYNC_WRITE_THREADPOOL_MAXSIZE);
    }

    public AbstractExecutorService pool() {
        return forkJoinPool;
    }

    @Override
    public void shutdown() {
        forkJoinPool.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return forkJoinPool.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return forkJoinPool.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return forkJoinPool.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return forkJoinPool.awaitTermination(timeout, unit);
    }

    @Override
    public void execute(Runnable command) {
        forkJoinPool.execute(command);
    }

    private final static class JDK7ForkJoinWorkerThread extends java.util.concurrent.ForkJoinWorkerThread {
        private final AtomicInteger count = new AtomicInteger();

        protected JDK7ForkJoinWorkerThread(java.util.concurrent.ForkJoinPool pool, boolean shared, String threadName) {
            super(pool);
            setName((shared ? "Atmosphere-Shared-" : threadName) + count.getAndIncrement());
        }
    }
}

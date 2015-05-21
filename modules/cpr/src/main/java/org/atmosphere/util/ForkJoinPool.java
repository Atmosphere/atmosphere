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
 * Wrapper around a ForkJoinPool for JDK6 support.
 *
 * @author Jean-Francois Arcand
 */
public class ForkJoinPool extends AbstractExecutorService {

    private final static Logger logger = LoggerFactory.getLogger(ForkJoinPool.class);

    private final org.atmosphere.util.chmv8.ForkJoinPool forkJoinPool;

    public ForkJoinPool() {
        forkJoinPool =
                new org.atmosphere.util.chmv8.ForkJoinPool(Runtime.getRuntime().availableProcessors(),
                    new org.atmosphere.util.chmv8.ForkJoinPool.ForkJoinWorkerThreadFactory() {

                        @Override
                        public org.atmosphere.util.chmv8.ForkJoinWorkerThread newThread(org.atmosphere.util.chmv8.ForkJoinPool pool) {
                            return new AtmosphereThreadFactoryJoin(pool);
                        }
                    }, null, true);
        logger.info("Using ForkJoinPool. Set the {} to -1 to fully use its power.", ApplicationConfig.BROADCASTER_ASYNC_WRITE_THREADPOOL_MAXSIZE);
    }

    public org.atmosphere.util.chmv8.ForkJoinPool pool() {
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
        return forkJoinPool.awaitTermination(timeout,unit);
    }

    @Override
    public void execute(Runnable command) {
        forkJoinPool.execute(command);
    }

    private final class AtmosphereThreadFactoryJoin extends org.atmosphere.util.chmv8.ForkJoinWorkerThread {
        private final AtomicInteger count = new AtomicInteger();

        protected AtmosphereThreadFactoryJoin(org.atmosphere.util.chmv8.ForkJoinPool pool) {
            super(pool);
            setName("Atmosphere-ForkJoinThreadShared-" + count.getAndIncrement());
        }
    }
}

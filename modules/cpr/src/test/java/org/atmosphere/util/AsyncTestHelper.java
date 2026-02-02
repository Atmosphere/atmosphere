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

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Test utility for handling asynchronous operations in tests.
 * <p>
 * This helper is specifically designed to work around the BlockingIOCometSupport
 * behavior where doCometSupport() blocks the calling thread. By running the
 * suspend operation in a separate thread, tests can proceed to trigger broadcasts
 * and other operations that would normally unblock the suspended connection.
 * <p>
 * <b>Usage Example:</b>
 * <pre>{@code
 * AsyncTestHelper helper = new AsyncTestHelper();
 * 
 * // Start async suspend
 * helper.asyncDoCometSupport(framework, request, response);
 * 
 * // Wait for suspend to be set up
 * helper.awaitSuspendSetup();
 * 
 * // Now do operations that depend on the suspended state
 * broadcaster.broadcast("message");
 * 
 * // Cleanup
 * helper.shutdown();
 * }</pre>
 *
 * @author Jeanfrancois Arcand
 */
public class AsyncTestHelper {
    
    private static final Logger logger = LoggerFactory.getLogger(AsyncTestHelper.class);
    
    private final ExecutorService executor;
    private final long defaultSetupDelayMs;
    
    /**
     * Creates a new AsyncTestHelper with default setup delay of 100ms
     */
    public AsyncTestHelper() {
        this(100);
    }
    
    /**
     * Creates a new AsyncTestHelper with specified setup delay
     * 
     * @param setupDelayMs time in milliseconds to wait for async operations to set up
     */
    public AsyncTestHelper(long setupDelayMs) {
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("AsyncTestHelper-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });
        this.defaultSetupDelayMs = setupDelayMs;
    }
    
    /**
     * Executes doCometSupport asynchronously in a separate thread.
     * This allows the test to continue and trigger broadcasts or other operations.
     * 
     * @param framework the AtmosphereFramework
     * @param request the AtmosphereRequest
     * @param response the AtmosphereResponse
     * @return CompletableFuture that completes when doCometSupport returns
     */
    public CompletableFuture<Void> asyncDoCometSupport(
            final AtmosphereFramework framework,
            final AtmosphereRequest request,
            final AtmosphereResponse response) {
        
        return CompletableFuture.runAsync(() -> {
            try {
                logger.debug("Starting async doCometSupport in thread: {}", 
                           Thread.currentThread().getName());
                framework.doCometSupport(request, response);
                logger.debug("Completed async doCometSupport");
            } catch (Exception e) {
                logger.error("Error in async doCometSupport", e);
                throw new RuntimeException("Failed to execute doCometSupport", e);
            }
        }, executor);
    }
    
    /**
     * Waits for the default setup delay to allow async operations to initialize.
     * This is useful after starting an async suspend to ensure the framework
     * is ready before proceeding with broadcasts.
     * 
     * @throws InterruptedException if interrupted while waiting
     */
    public void awaitSuspendSetup() throws InterruptedException {
        awaitSuspendSetup(defaultSetupDelayMs);
    }
    
    /**
     * Waits for the specified time to allow async operations to initialize.
     * 
     * @param delayMs time in milliseconds to wait
     * @throws InterruptedException if interrupted while waiting
     */
    public void awaitSuspendSetup(long delayMs) throws InterruptedException {
        logger.debug("Waiting {}ms for suspend setup", delayMs);
        Thread.sleep(delayMs);
    }
    
    /**
     * Executes a runnable asynchronously
     * 
     * @param task the task to execute
     * @return CompletableFuture that completes when task finishes
     */
    public CompletableFuture<Void> async(Runnable task) {
        return CompletableFuture.runAsync(task, executor);
    }
    
    /**
     * Shuts down the executor service.
     * Should be called in test cleanup (@AfterMethod or @After)
     */
    public void shutdown() {
        logger.debug("Shutting down AsyncTestHelper executor");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("Executor did not terminate");
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Gets the executor service used by this helper.
     * Advanced usage only.
     * 
     * @return the ExecutorService
     */
    public ExecutorService getExecutor() {
        return executor;
    }
}

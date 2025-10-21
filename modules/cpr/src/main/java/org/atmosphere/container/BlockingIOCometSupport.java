/*
 * Copyright 2008-2025 Async-IO.org
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
package org.atmosphere.container;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsyncSupport;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletException;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter.OnResume;

/**
 * This class is used when the {@link org.atmosphere.cpr.AtmosphereFramework} fails to autodetect
 * the Servlet Container we are running on.
 * <p/>
 * This {@link org.atmosphere.cpr.AsyncSupport} implementation uses a blocking approach, meaning
 * the request thread will be blocked until another Thread invoke the {@link Broadcaster#broadcast}.
 * <p/>
 * <b>JDK 25+ Compatibility:</b> On JDK 25 and later, this class automatically uses a default timeout
 * instead of infinite wait to avoid deadlocks caused by changes in AbstractQueuedSynchronizer.
 * This can be configured via:
 * <ul>
 *   <li>{@code org.atmosphere.container.blockingIO.defaultTimeout} - timeout in milliseconds (default: 300000 = 5 minutes)</li>
 *   <li>{@code org.atmosphere.container.blockingIO.jdk25SafeMode} - enable/disable safe mode (default: auto-detect JDK version)</li>
 * </ul>
 *
 * @author Jeanfrancois Arcand
 */
public class BlockingIOCometSupport extends AsynchronousProcessor {

    private static final Logger logger = LoggerFactory.getLogger(BlockingIOCometSupport.class);

    protected static final String LATCH = BlockingIOCometSupport.class.getName() + ".latch";
    
    /**
     * Configuration parameter for default suspend timeout in milliseconds.
     * Use 0 for infinite wait (not recommended on JDK 25+).
     */
    public static final String DEFAULT_SUSPEND_TIMEOUT_PARAM = 
        "org.atmosphere.container.blockingIO.defaultTimeout";
    
    /**
     * Configuration parameter to enable/disable JDK 25 safe mode.
     * When enabled, uses timeout-based await instead of infinite wait.
     */
    public static final String JDK25_SAFE_MODE_PARAM = 
        "org.atmosphere.container.blockingIO.jdk25SafeMode";
    
    /**
     * Default timeout: 10 seconds (10,000 milliseconds)
     * This is suitable for both production long-polling and test scenarios.
     * Can be overridden via configuration parameter.
     */
    private static final long DEFAULT_TIMEOUT_MS = 10000;
    
    /**
     * Detect if running on JDK 25 or later
     */
    private static final boolean IS_JDK25_OR_LATER;
    
    static {
        int javaVersion = Runtime.version().feature();
        IS_JDK25_OR_LATER = javaVersion >= 25;
    }
    
    private final long defaultTimeout;
    private final boolean jdk25SafeMode;

    public BlockingIOCometSupport(AtmosphereConfig config) {
        super(config);
        
        // Read default timeout configuration
        long configuredTimeout = DEFAULT_TIMEOUT_MS;
        String timeoutParam = config.getInitParameter(DEFAULT_SUSPEND_TIMEOUT_PARAM);
        if (timeoutParam != null) {
            try {
                configuredTimeout = Long.parseLong(timeoutParam);
            } catch (NumberFormatException e) {
                logger.warn("Invalid {} value: '{}', using default {}ms", 
                           DEFAULT_SUSPEND_TIMEOUT_PARAM, timeoutParam, DEFAULT_TIMEOUT_MS);
                configuredTimeout = DEFAULT_TIMEOUT_MS;
            }
        }
        this.defaultTimeout = configuredTimeout;
        
        // Check if JDK25 safe mode is explicitly configured
        boolean safeModeEnabled = IS_JDK25_OR_LATER; // default
        String safeModeParam = config.getInitParameter(JDK25_SAFE_MODE_PARAM);
        if (safeModeParam != null) {
            safeModeEnabled = Boolean.parseBoolean(safeModeParam);
            logger.info("BlockingIOCometSupport JDK25 safe mode explicitly {} (timeout={}ms)", 
                       safeModeEnabled ? "enabled" : "disabled", this.defaultTimeout);
        } else if (IS_JDK25_OR_LATER) {
            logger.info("BlockingIOCometSupport JDK25 safe mode auto-enabled (JDK {}, timeout={}ms)", 
                       Runtime.version().feature(), this.defaultTimeout);
        }
        this.jdk25SafeMode = safeModeEnabled;
    }

    @Override
    public Action service(AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {

        Action action = suspended(req, res);
        if (action.type() == Action.TYPE.SUSPEND) {
            suspend(action, req, res);
        } else if (action.type() == Action.TYPE.RESUME) {
            CountDownLatch latch = (CountDownLatch) req.getAttribute(LATCH);

            if (latch == null || req.getAttribute(AtmosphereResourceImpl.PRE_SUSPEND) == null) {
                logger.debug("response wasn't suspended: {}", res);
                return action;
            }

            latch.countDown();

            Action nextAction = resumed(req, res);
            if (nextAction.type() == Action.TYPE.SUSPEND) {
                suspend(action, req, res);
            }
        }

        return action;
    }

    /**
     * Suspend the connection by blocking the current {@link Thread}
     *
     * @param action The {@link Action}
     * @param req    the {@link AtmosphereRequest}
     * @param res    the {@link AtmosphereResponse}
     */
    protected void suspend(Action action, AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {

        final CountDownLatch latch = new CountDownLatch(1);
        req.setAttribute(LATCH, latch);

        boolean ok = true;
        AtmosphereResource resource = req.resource();
        if (resource != null) {
            try {
                resource.addEventListener(new OnResume() {
                    @Override
                    public void onResume(AtmosphereResourceEvent event) {
                        latch.countDown();
                    }
                });
                
                if (action.timeout() != -1) {
                    // Explicit timeout specified in action
                    ok = latch.await(action.timeout(), TimeUnit.MILLISECONDS);
                } else if (jdk25SafeMode && defaultTimeout > 0) {
                    // JDK 25 safe mode: use configured timeout
                    ok = latch.await(defaultTimeout, TimeUnit.MILLISECONDS);
                    if (!ok) {
                        logger.debug("BlockingIOCometSupport timed out after {}ms (JDK25 safe mode)", 
                                   defaultTimeout);
                    }
                } else {
                    // Legacy behavior: infinite wait (JDK < 25 or explicitly disabled)
                    latch.await();
                }
            } catch (InterruptedException ex) {
                logger.trace("", ex);
            } finally {
                if (!ok) {
                    timedout(req, res);
                } else {
                    ((AtmosphereResourceImpl) resource).cancel();
                }
            }
        }
    }

    @Override
    public Action cancelled(AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {

        Action a = super.cancelled(req, res);
        if (req.getAttribute(LATCH) != null) {
            CountDownLatch latch = (CountDownLatch) req.getAttribute(LATCH);
            latch.countDown();
        }
        return a;
    }

    @Override
    public void action(AtmosphereResourceImpl r) {
        try {
            super.action(r);
            if (r.action().type() == Action.TYPE.RESUME) {
                complete(r);
            }
        } catch (Exception ex) {
            logger.error("", ex);
        }
    }

    @Override
    public AsyncSupport<AtmosphereResourceImpl> complete(AtmosphereResourceImpl r) {
        AtmosphereRequest req = r.getRequest(false);
        CountDownLatch latch = null;

        if (req.getAttribute(LATCH) != null) {
            latch = (CountDownLatch) req.getAttribute(LATCH);
        }

        if (latch != null) {
            latch.countDown();
        } else if (req.getAttribute(AtmosphereResourceImpl.PRE_SUSPEND) == null) {
            logger.trace("Unable to resume the suspended connection");
        }
        return this;
    }
}

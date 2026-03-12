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
package org.atmosphere.cpr;

import org.atmosphere.util.ServletContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;

/**
 * Encapsulates the {@link AtmosphereFramework} initialization pipeline.
 * <p>
 * This class extracts the multi-phase init sequence from {@code AtmosphereFramework.init(ServletConfig, boolean)}
 * into well-named methods, making the boot process easier to follow and maintain. The public API of
 * {@link AtmosphereFramework} is unchanged; only the internal wiring is delegated here.
 * <p>
 * Lifecycle:
 * <pre>
 *   new FrameworkBootstrap(framework).bootstrap(sc);
 * </pre>
 *
 * @author Jeanfrancois Arcand
 */
final class FrameworkBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(FrameworkBootstrap.class);

    private final AtmosphereFramework framework;

    FrameworkBootstrap(AtmosphereFramework framework) {
        this.framework = framework;
    }

    /**
     * Execute the full initialization pipeline. This method is called from
     * {@link AtmosphereFramework#init(ServletConfig, boolean)} after the early-return guard.
     *
     * @param sc   the wrapped ServletConfig
     * @param wrap whether to wrap the ServletConfig with init-param merging
     * @throws ServletException if any phase fails
     */
    void bootstrap(ServletConfig sc, boolean wrap) throws ServletException {
        // Phase 0: Bootstrap — wire config, system properties, meta-services
        phaseBootstrap(sc, wrap);

        try {
            ServletContextFactory.getDefault().init(sc.getServletContext());

            // Phase 1: Parse configuration from init-params
            phaseParseConfiguration();

            // Phase 2: Broadcaster infrastructure
            phaseBroadcasterInfrastructure();

            // Phase 3: Classpath scanning and annotation processing
            phaseClasspathScanning();

            // Phase 4: Re-configure after annotations have been processed
            phasePostAnnotationConfiguration();

            // Phase 5: Handler and interceptor initialization
            phaseHandlerAndInterceptorInit();

            // Phase 6: Finalization — analytics, shutdown hook, diagnostics
            phaseFinalization(sc);

        } catch (Throwable t) {
            logger.error("Failed to initialize Atmosphere Framework", t);

            if (t instanceof ServletException se) {
                throw se;
            }

            throw new ServletException(t);
        }

        framework.isInit = true;
        framework.config.initComplete();
        framework.onPostInit();
    }

    // ----------------------------------------------------------------
    // Phase 0: Bootstrap
    // ----------------------------------------------------------------

    private void phaseBootstrap(ServletConfig sc, boolean wrap) {
        framework.servletConfig(sc, wrap);
        framework.readSystemProperties();
        framework.populateBroadcasterType();
        framework.populateObjectFactoryType();
        framework.loadMetaService();
        framework.onPreInit();
    }

    // ----------------------------------------------------------------
    // Phase 1: Parse configuration
    // ----------------------------------------------------------------

    private void phaseParseConfiguration() throws InstantiationException, IllegalAccessException {
        framework.preventOOM();
        framework.doInitParams(framework.servletConfig);
        framework.doInitParamsForWebSocket(framework.servletConfig);
        framework.lookupDefaultObjectFactoryType();

        if (logger.isTraceEnabled()) {
            framework.asyncSupportListener(
                    framework.newClassInstance(AsyncSupportListener.class, AsyncSupportListenerAdapter.class));
        }
        framework.configureObjectFactory();
    }

    // ----------------------------------------------------------------
    // Phase 2: Broadcaster infrastructure
    // ----------------------------------------------------------------

    private void phaseBroadcasterInfrastructure() {
        framework.configureAnnotationPackages();
        framework.configureBroadcasterFactory();
        framework.configureMetaBroadcaster();
        framework.configureAtmosphereResourceFactory();
        if (framework.isSessionSupportSpecified) {
            framework.sessionFactory();
        }
    }

    // ----------------------------------------------------------------
    // Phase 3: Classpath scanning
    // ----------------------------------------------------------------

    private void phaseClasspathScanning() throws Exception {
        framework.configureScanningPackage(framework.servletConfig, ApplicationConfig.ANNOTATION_PACKAGE);
        framework.configureScanningPackage(framework.servletConfig, FrameworkConfig.JERSEY2_SCANNING_PACKAGE);
        framework.configureScanningPackage(framework.servletConfig, FrameworkConfig.JERSEY_SCANNING_PACKAGE);
        framework.defaultPackagesToScan();
        framework.installAnnotationProcessor(framework.servletConfig);
        framework.autoConfigureService(framework.servletConfig.getServletContext());
    }

    // ----------------------------------------------------------------
    // Phase 4: Re-configure after annotations
    // ----------------------------------------------------------------

    private void phasePostAnnotationConfiguration() throws ServletException {
        framework.configureBroadcasterFactory();
        framework.patchContainer();
        framework.configureBroadcaster();
        framework.loadConfiguration(framework.servletConfig);
    }

    // ----------------------------------------------------------------
    // Phase 5: Handler and interceptor initialization
    // ----------------------------------------------------------------

    private void phaseHandlerAndInterceptorInit() throws ServletException {
        framework.initWebSocket();
        framework.initEndpointMapper();
        framework.initDefaultSerializer();
        framework.autoDetectContainer();
        framework.configureWebDotXmlAtmosphereHandler(framework.servletConfig);
        framework.asyncSupport.init(framework.servletConfig);
        framework.initAtmosphereHandler(framework.servletConfig);
        framework.configureAtmosphereInterceptor(framework.servletConfig);
    }

    // ----------------------------------------------------------------
    // Phase 6: Finalization
    // ----------------------------------------------------------------

    private void phaseFinalization(ServletConfig sc) {
        framework.analytics();
        if (sc.getServletContext() != null) {
            sc.getServletContext().setAttribute(
                    BroadcasterFactory.class.getName(),
                    framework.broadcasterSetup.broadcasterFactory());
        }

        String s = framework.config.getInitParameter(ApplicationConfig.BROADCASTER_SHARABLE_THREAD_POOLS);
        if (s != null) {
            framework.sharedThreadPools = Boolean.parseBoolean(s);
        }

        framework.shutdownHook = new Thread(framework::destroy);
        Runtime.getRuntime().addShutdownHook(framework.shutdownHook);

        if (logger.isInfoEnabled()) {
            framework.info();
        }
        if (framework.initializationError != null) {
            logger.trace("ContainerInitalizer exception. May not be an issue if Atmosphere started properly ",
                    framework.initializationError);
        }
        framework.universe();
    }
}

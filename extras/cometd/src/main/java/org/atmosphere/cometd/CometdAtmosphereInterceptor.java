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
package org.atmosphere.cometd;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.FrameworkConfig;
import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationThrowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.ServletResponse;
import java.io.IOException;

public class CometdAtmosphereInterceptor implements AtmosphereInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(JettyAsyncSupport.class);

    @Override
    public void configure(AtmosphereConfig config) {
        if (config.getServletContext().getServerInfo().contains("jetty")) {
            config.framework().setAsyncSupport(new JettyAsyncSupport(
                    config));
        }
    }

    @Override
    public Action inspect(final AtmosphereResource r) {
        AtmosphereRequest request = r.getRequest();

        if (r.transport().equals(AtmosphereResource.TRANSPORT.WEBSOCKET)) {

            if (r.getRequest().getAttribute(FrameworkConfig.INJECTED_ATMOSPHERE_RESOURCE) != null) {
                return Action.CANCELLED;
            }
        }

        request.setAttribute(Continuation.ATTRIBUTE, new AtmosphereContinuation(r));

        return Action.CONTINUE;
    }

    public final static class AtmosphereContinuation implements Continuation {

        private long timeoutMs = -1;
        private ServletResponse response;
        private final AtmosphereResource r;

        public AtmosphereContinuation(AtmosphereResource r) {
            this.r = r;
        }

        @Override
        public void setTimeout(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        @Override
        public void suspend() {
            r.suspend(timeoutMs, false);
        }

        @Override
        public void suspend(ServletResponse response) {
            this.response = response;
            r.suspend(timeoutMs, false);
        }

        @Override
        public void resume() {
            try {
                r.getAtmosphereConfig().framework().doCometSupport(r.getRequest(), r.getResponse());
            } catch (IOException e) {
                logger.warn("",e);
            } catch (ServletException e) {
                logger.warn("", e);
            }
            r.resume();
        }

        @Override
        public void complete() {
            r.resume();
        }

        @Override
        public boolean isSuspended() {
            return r.isSuspended();
        }

        @Override
        public boolean isResumed() {
            return r.isResumed();
        }

        @Override
        public boolean isExpired() {
            return r.isResumed();
        }

        @Override
        public boolean isInitial() {
            return r.isSuspended();
        }

        @Override
        public boolean isResponseWrapped() {
            return true;
        }

        @Override
        public ServletResponse getServletResponse() {
            return response;
        }

        @Override
        public void addContinuationListener(final ContinuationListener listener) {
            r.addEventListener(new AtmosphereResourceEventListener() {
                @Override
                public void onSuspend(AtmosphereResourceEvent event) {
                }

                @Override
                public void onResume(AtmosphereResourceEvent event) {
                    if (event.isResuming()) {
                        listener.onComplete(AtmosphereContinuation.this);
                    } else {
                        try {
                            r.getAtmosphereConfig().framework().doCometSupport(r.getRequest(), r.getResponse());
                        } catch (IOException e) {
                            logger.warn("", e);
                        } catch (ServletException e) {
                            logger.warn("", e);
                        }
                        listener.onTimeout(AtmosphereContinuation.this);
                    }
                }

                @Override
                public void onDisconnect(AtmosphereResourceEvent event) {
                }

                @Override
                public void onBroadcast(AtmosphereResourceEvent event) {
                }

                @Override
                public void onThrowable(AtmosphereResourceEvent event) {
                }
            });
        }

        @Override
        public void setAttribute(String name, Object attribute) {
            r.getRequest().setAttribute(name, attribute);
        }

        @Override
        public Object getAttribute(String name) {
            return r.getRequest().getAttribute(name);
        }

        @Override
        public void removeAttribute(String name) {
            r.getRequest().removeAttribute(name);
        }

        @Override
        public void undispatch() throws ContinuationThrowable {
        }
    }

    @Override
    public void postInspect(AtmosphereResource r) {
    }

    @Override
    public String toString() {
        return "CometD/Bayeux Protocol";
    }
}

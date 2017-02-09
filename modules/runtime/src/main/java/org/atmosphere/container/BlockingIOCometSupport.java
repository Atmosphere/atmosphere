/*
 * Copyright 2017 Async-IO.org
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

import org.atmosphere.runtime.Action;
import org.atmosphere.runtime.AsyncSupport;
import org.atmosphere.runtime.AsynchronousProcessor;
import org.atmosphere.runtime.AtmosphereConfig;
import org.atmosphere.runtime.AtmosphereRequest;
import org.atmosphere.runtime.AtmosphereResource;
import org.atmosphere.runtime.AtmosphereResourceEvent;
import org.atmosphere.runtime.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.runtime.AtmosphereResourceImpl;
import org.atmosphere.runtime.AtmosphereResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


public class BlockingIOCometSupport extends AsynchronousProcessor {

    private static final Logger logger = LoggerFactory.getLogger(BlockingIOCometSupport.class);

    protected static final String LATCH = BlockingIOCometSupport.class.getName() + ".latch";

    public BlockingIOCometSupport(AtmosphereConfig config) {
        super(config);
    }

    @Override
    public Action service(AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {

        Action action = null;
        action = suspended(req, res);
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
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    protected void suspend(Action action, AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {

        final CountDownLatch latch = new CountDownLatch(1);
        req.setAttribute(LATCH, latch);

        boolean ok = true;
        AtmosphereResource resource = req.resource();
        if (resource != null) {
            try {
                resource.addEventListener(new AtmosphereResourceEventListenerAdapter.OnResume() {
                    @Override
                    public void onResume(AtmosphereResourceEvent event) {
                        latch.countDown();
                    }
                });
                if (action.timeout() != -1) {
                    ok = latch.await(action.timeout(), TimeUnit.MILLISECONDS);
                } else {
                    latch.await();
                }
            } catch (InterruptedException ex) {
                logger.trace("", ex);
            } finally {
                if (!ok) {
                    timedout(req, res);
                } else {
                    AtmosphereResourceImpl.class.cast(resource).cancel();
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
    public AsyncSupport complete(AtmosphereResourceImpl r) {
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
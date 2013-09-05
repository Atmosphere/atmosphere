/*
 * Copyright 2013 Jeanfrancois Arcand
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
/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://jersey.dev.java.net/CDDL+GPL.html
 * or jersey/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at jersey/legal/LICENSE.txt.
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
package org.jersey.devoxx.samples.ee6.atmosphere;

import org.atmosphere.annotation.Suspend;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.jersey.Broadcastable;
import org.atmosphere.jersey.JerseyBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.ejb.Stateless;
import javax.ejb.Timeout;
import javax.ejb.Timer;
import javax.ejb.TimerConfig;
import javax.ejb.TimerService;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import java.util.Date;
import java.util.concurrent.Semaphore;

/**
 * curl -N -v http://localhost:8080/atmosphere-ee6-1.0-SNAPSHOT/resources/timer
 * <p/>
 * curl -X POST http://localhost:8080/atmosphere-ee6-1.0-SNAPSHOT/resources/timer/start
 * <p/>
 * curl -X POST http://localhost:8080/atmosphere-ee6-1.0-SNAPSHOT/resources/timer/stop
 * curl -X POST http://localhost:8080/atmosphere-ee6-1.0-SNAPSHOT/resources/timer/hardstop
 *
 * @author Paul Sandoz
 */
@Path("timer")
@Stateless
public class TimerResource {

    private static final Logger logger = LoggerFactory.getLogger(TimerResource.class);

    private
    @Resource
    TimerService ts;

    private
    @Context
    BroadcasterFactory bf;

    private Semaphore started = new Semaphore(1);

    private Semaphore stopped = new Semaphore(1);

    private Broadcaster tb;

    private Timer t;

    private
    @PostConstruct
    void postConstruct() {
        stopped.tryAcquire();
    }

    private Broadcaster getTimerBroadcaster() {
        return bf.lookup(JerseyBroadcaster.class, "timer", true);
    }

    @Suspend
    @GET
    public Broadcastable get() {
        return new Broadcastable(getTimerBroadcaster());
    }

    @Path("start")
    @POST
    public void start() {
        if (started.tryAcquire()) {
            tb = getTimerBroadcaster();
            t = ts.createIntervalTimer(1000, 1000, new TimerConfig("timer", false));
            stopped.release();
        }
    }

    @Timeout
    public void timeout(Timer timer) {
        logger.info("{}: {}", getClass().getName(), new Date());

        tb.broadcast(new Date().toString() + "\n");
    }

    @Path("stop")
    @POST
    public void stop() {
        if (stopped.tryAcquire()) {
            t.cancel();
            tb = null;
            t = null;
            started.release();
        }
    }

    @Path("hardstop")
    @POST
    public void hardstop() {
        stop();

        getTimerBroadcaster().resumeAll();
    }
}
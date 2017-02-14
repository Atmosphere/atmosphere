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
package org.atmosphere.jersey.tests;

import org.atmosphere.annotation.Suspend;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.BroadcasterLifeCyclePolicy;
import org.atmosphere.jersey.Broadcastable;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * this resource sends messages in a high frequency from multiple thread.
 */
@Path("/jsonMessages")
@Produces("application/json")//"text/plain;charset=ISO-8859-1")
public class JsonMessagesResource {

    private static AtomicReference<String> BROADCASTER_ID = new AtomicReference<String>();
    private final static int MESSAGE_SENDER = 10;
    private final static int MESSAGES_TOTAL = 100000;
    private final static int SLEEP = 15;    // short sleep to provoke the bug!

    @GET
    @Suspend(resumeOnBroadcast = true, scope = Suspend.SCOPE.APPLICATION)
    public Broadcastable subscribe() {

        synchronized (this) {
            if (BROADCASTER_ID.get() == null) {
                String id = UUID.randomUUID().toString();
                Broadcaster broadcaster = BroadcasterFactory.getDefault().get(id);
                broadcaster.setBroadcasterLifeCyclePolicy(BroadcasterLifeCyclePolicy.NEVER);
                BROADCASTER_ID.set(id);
                startMessageSender();
            }
        }

        Broadcaster broadcaster = BroadcasterFactory.getDefault().lookup(BROADCASTER_ID.get());
        return new Broadcastable(broadcaster);
    }

    private void startMessageSender() {
        final AtomicInteger counter = new AtomicInteger(0);
        for (int i = 0; i < MESSAGE_SENDER; i++) {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        Broadcaster broadcaster = BroadcasterFactory.getDefault().lookup(BROADCASTER_ID.get());

                        for (int i = 0; i < MESSAGES_TOTAL; i = counter.incrementAndGet()) {
                            Thread.sleep(SLEEP);
                            broadcaster.broadcast("{ \"count\": " + i + ", \"messages\": \"Message Message MessageMessage Message MessageMessage Message MessageMessage Message MessageMessage Message MessageMessage Message MessageMessage Message MessageMessage Message Message\" }");

                        }

                        broadcaster.broadcast("done");

                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }).start();
        }
    }
}
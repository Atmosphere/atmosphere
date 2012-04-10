/*
 * Copyright 2012 Jean-Francois Arcand
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
package org.atmosphere.plugin.jgroups;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.util.AbstractBroadcasterProxy;
import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.ReceiverAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.concurrent.CountDownLatch;

/**
 * Simple {@link org.atmosphere.cpr.Broadcaster} implementation based on JGroups
 *
 * @author Jeanfrancois Arcand
 */
public class JGroupsBroadcaster extends AbstractBroadcasterProxy {

    private static final Logger logger = LoggerFactory.getLogger(JGroupsBroadcaster.class);

    private JChannel jchannel;
    private final CountDownLatch ready = new CountDownLatch(1);

    public JGroupsBroadcaster(String id, AtmosphereConfig config) {
        super(id, null, config);
    }

    @Override
    public void incomingBroadcast() {
        try {
            logger.info("Starting Atmosphere JGroups Clustering support with group name {}", getID());

            jchannel = new JChannel();
            jchannel.setReceiver(new ReceiverAdapter() {
                /** {@inheritDoc} */
                @Override
                public void receive(final Message message) {
                    final Object msg = message.getObject();
                    if (msg != null && BroadcastMessage.class.isAssignableFrom(msg.getClass())) {
                        BroadcastMessage b = BroadcastMessage.class.cast(msg);
                        if (b.getTopicId().equalsIgnoreCase(getID())) {
                            broadcastReceivedMessage(b.getMessage());
                        }
                    }
                }
            });
            jchannel.connect(getID());
        } catch (Throwable t) {
            logger.warn("failed to connect to JGroups channel", t);
        } finally {
            ready.countDown();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void outgoingBroadcast(Object message) {

        try {
            ready.await();
            jchannel.send(new Message(null, null, new BroadcastMessage(getID(), message)));
        } catch (Throwable e) {
            logger.error("failed to send messge over Jgroups channel", e.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void destroy() {
        super.destroy();
        if (!jchannel.isOpen()) return;
        jchannel.shutdown();
    }

    public static class BroadcastMessage implements Serializable {

        private final String topicId;
        private final Object message;

        public BroadcastMessage(String topicId, Object message) {
            this.topicId = topicId;
            this.message = message;
        }

        public String getTopicId() {
            return topicId;
        }

        public Object getMessage() {
            return message;
        }

    }
}
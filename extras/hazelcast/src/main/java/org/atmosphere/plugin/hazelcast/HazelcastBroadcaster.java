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
package org.atmosphere.plugin.hazelcast;


import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.ITopic;
import com.hazelcast.core.MessageListener;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.util.AbstractBroadcasterProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

/**
 * Simple {@link org.atmosphere.cpr.Broadcaster} implementation based on Hazelcast
 *
 * @author Jeanfrancois Arcand
 */
public class HazelcastBroadcaster extends AbstractBroadcasterProxy {

    private static final Logger logger = LoggerFactory.getLogger(org.atmosphere.plugin.hazelcast.HazelcastBroadcaster.class);
    private ITopic topic;

    public HazelcastBroadcaster(String id, AtmosphereConfig config) {
        this(id, URI.create("http://localhost:6379"), config);
    }

    public HazelcastBroadcaster(String id, URI uri, AtmosphereConfig config) {
        super(id, uri, config);
    }

    public void setUp() {
        topic = Hazelcast.<String>getTopic(getID());
    }

    @Override
    public synchronized void setID(String id) {
        super.setID(id);
        setUp();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() {
        topic.destroy();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void incomingBroadcast() {
        logger.info("Subscribing to: {}", getID());
        topic.addMessageListener(new MessageListener<String>() {
            @Override
            public void onMessage(String message) {
                broadcastReceivedMessage(message);
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void outgoingBroadcast(Object message) {
        topic.publish(message.toString());
    }

}

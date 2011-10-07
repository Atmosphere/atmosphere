/*
 * Copyright 2011 Jeanfrancois Arcand
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
package org.atmosphere.plugin.xmpp;


import org.atmosphere.util.AbstractBroadcasterProxy;
import org.jivesoftware.smack.Chat;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.MessageListener;
import org.jivesoftware.smack.SASLAuthentication;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.packet.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

/**
 * Simple {@link org.atmosphere.cpr.Broadcaster} implementation based on Smack, and XMPP library.
 *
 * @author Jeanfrancois Arcand
 */
public class XMPPBroadcaster extends AbstractBroadcasterProxy {

    private static final Logger logger = LoggerFactory.getLogger(XMPPBroadcaster.class);

    private static final String XMPP_AUTH = XMPPBroadcaster.class.getName() + ".authorization";
    private static final String XMPP_SERVER = XMPPBroadcaster.class.getName() + ".server";
    private static final String XMPP_DEBUG = XMPPBroadcaster.class.getName() + ".debug";

    private URI uri;
    private String authToken;
    private XMPPConnection xmppConnection;
    private Chat channel;

    public XMPPBroadcaster() {
        this("atmosphere", URI.create("http://gmail.com"));
    }

    public XMPPBroadcaster(String id) {
        this(id, URI.create("http://gmail.com"));
    }

    public XMPPBroadcaster(URI uri) {
        this(XMPPBroadcaster.class.getSimpleName(), uri);
    }

    public XMPPBroadcaster(String id, URI uri) {
        super(id);
        this.uri = uri;
    }

    private synchronized void setUp() {
        if (uri == null) return;

        try {

            if (config != null) {
                if (config.getServletConfig().getInitParameter(XMPP_AUTH) != null) {
                    authToken = config.getServletConfig().getInitParameter(XMPP_AUTH);
                } else {
                    throw new IllegalStateException("No authorization token specified. Please make sure your web.xml contains:" +
                            "\n        <init-param>\n" +
                            "            <param-name>org.atmosphere.plugin.xmpp.XMPPBroadcaster.authorization</param-name>\n" +
                            "            <param-value>principal:password</param-value>\n" +
                            "        </init-param>");
                }

                if (config.getServletConfig().getInitParameter(XMPP_SERVER) != null) {
                    uri = URI.create(config.getServletConfig().getInitParameter(XMPP_SERVER));
                }

                if (config.getServletConfig().getInitParameter(XMPP_DEBUG) != null) {
                    XMPPConnection.DEBUG_ENABLED = true;
                }
            }

            ConnectionConfiguration config = null;
            int port = -1;
            try {
                port = uri.getPort();
            } catch (Throwable t) {
                ;
            }
            if (port == -1) {
                config = new ConnectionConfiguration(uri.getHost());
            } else {
                config = new ConnectionConfiguration(uri.getHost(), port);

            }

            xmppConnection = new XMPPConnection(config);
            xmppConnection.connect();
            SASLAuthentication.supportSASLMechanism("PLAIN", 0);
            String[] credentials = authToken.split(":");

            xmppConnection.login(credentials[0], credentials[1], getID());

            logger.info("Subscribing to: " + getID());
            channel = xmppConnection.getChatManager().createChat(getID(), new MessageListener() {

                public void processMessage(Chat chat, Message message) {
                    broadcastReceivedMessage(message.getBody());
                }
            });

            logger.info("Connected to: " + getID());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @Override
    public void setID(String id) {
        super.setID(id);
        setUp();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() {
        super.destroy();
        synchronized (xmppConnection) {
            if (xmppConnection != null) {
                xmppConnection.disconnect();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void incomingBroadcast() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void outgoingBroadcast(Object message) {
        if (message instanceof String) {
            try {
                channel.sendMessage(message.toString());
            } catch (XMPPException e) {
                logger.debug("failed to send message on channel", e);
            }
        }
    }
}
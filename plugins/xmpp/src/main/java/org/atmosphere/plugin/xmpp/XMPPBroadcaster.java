/*
 * 
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
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
 *
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
    private static final String XMPP_DEBUG= XMPPBroadcaster.class.getName() + ".debug";

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

    private void setUp() {
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
        if (xmppConnection != null) {
            xmppConnection.disconnect();
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
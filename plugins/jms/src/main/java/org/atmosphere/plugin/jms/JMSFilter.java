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
package org.atmosphere.plugin.jms;

import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.ClusterBroadcastFilter;
import org.atmosphere.util.LoggerUtils;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import javax.naming.Context;
import javax.naming.InitialContext;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Clustering support based on JMS
 *
 * @author Jean-francois Arcand
 */
public class JMSFilter implements ClusterBroadcastFilter {
    private static final String JMS_TOPIC = JMSBroadcaster.class.getName() + ".topic";
    private static final String JNDI_NAMESPACE = JMSBroadcaster.class.getName() + ".JNDINamespace";
    private static final String JNDI_FACTORY_NAME = JMSBroadcaster.class.getName() + ".JNDIConnectionFactoryName";
    private static final String JNDI_TOPIC = JMSBroadcaster.class.getName() + ".JNDITopic";

    private final Logger logger = LoggerUtils.getLogger();
    private Connection connection;
    private Session session;
    private MessageConsumer consumer;
    private MessageProducer publisher;

    private String topicId = "atmosphere";
    private String factoryName = "atmosphereFactory";
    private String namespace = "jms/";

    private String clusterName;

    private Broadcaster bc = null;
    private final ConcurrentLinkedQueue<String> receivedMessages =
            new ConcurrentLinkedQueue<String>();

    public JMSFilter() {
        this(null);
    }

    /**
     * Create a JMSFilter based filter.
     *
     * @param bc the Broadcaster to use when receiving update from the cluster.
     */
    public JMSFilter(Broadcaster bc) {
        this(bc, "atmosphere-framework");
    }

    /**
     * Create a JMSFilter based filter.
     *
     * @param bc       the Broadcaster to use when receiving update from the cluster.
     * @param topicId  the topic id
     */
    public JMSFilter(Broadcaster bc, String topicId) {
        this.bc = bc;
        this.topicId = topicId;
    }

    public void setUri(String clusterName) {
        this.clusterName = clusterName;
    }

    /**
     * Preapre the cluter.
     */
    public void init() {
        try {
            AtmosphereServlet.AtmosphereConfig config = bc.getBroadcasterConfig().getAtmosphereConfig();
            if (config != null) {

                // For backward compatibility.
                if (config.getInitParameter(JMS_TOPIC) != null) {
                    topicId = config.getInitParameter(JMS_TOPIC);
                }

                if (config.getInitParameter(JNDI_NAMESPACE) != null) {
                    namespace = config.getInitParameter(JNDI_NAMESPACE);
                }

                if (config.getInitParameter(JNDI_FACTORY_NAME) != null) {
                    factoryName = config.getInitParameter(JNDI_FACTORY_NAME);
                }

                if (config.getInitParameter(JNDI_TOPIC) != null) {
                    topicId = config.getInitParameter(JNDI_TOPIC);
                }
            }

            String id = bc.getID();
            if (id.startsWith("/*")) {
                id = "atmosphere";
            }

            logger.info(String.format("Looking up Connection Factory %s", namespace + factoryName));
            Context ctx = new InitialContext();
            ConnectionFactory connectionFactory = (ConnectionFactory) ctx.lookup(namespace + factoryName);

            logger.info(String.format("Looking up topic: %s", topicId));
            Topic topic = (Topic) ctx.lookup(namespace + topicId);

            connection = connectionFactory.createConnection();
            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

            logger.info(String.format("Create customer: %s", id));
            String selector = String.format("BroadcasterId = '%s'", id);

            consumer = session.createConsumer(topic, selector);
            consumer.setMessageListener(new MessageListener() {

                @Override
                public void onMessage(Message msg) {
                    try {
                        TextMessage textMessage = (TextMessage) msg;
                        String message = textMessage.getText();

                        if (message != null && bc != null) {
                            receivedMessages.offer(message);
                            bc.broadcast(message);
                        }
                    } catch (JMSException ex) {
                        if (logger.isLoggable(Level.WARNING)) {
                            logger.log(Level.WARNING, "", ex);
                        }

                    }
                }
            });
            publisher = session.createProducer(topic);
            connection.start();
            logger.info(String.format("JMS created for topic %s, with filter %s", topicId, selector));
        } catch (Throwable ex) {
            throw new IllegalStateException("Unable to initialize JMSBroadcaster", ex);
        }
    }

    public void destroy() {
    }

    /**
     * Every time a message gets broadcasted, make sure we update the cluster.
     *
     * @param o the message to broadcast.
     * @return The same message.
     */
    public BroadcastAction filter(Object originalMessage, Object o) {
        if (o instanceof String) {
            String message = (String) o;
            // Avoid re-broadcasting
            if (!receivedMessages.remove(message)) {
                try {
                    String id = bc.getID();
                    if (id.startsWith("/*")) {
                        id = "atmosphere";
                    }

                    TextMessage textMessage = session.createTextMessage(message.toString());
                    textMessage.setStringProperty("BroadcasterId", id);
                    publisher.send(textMessage);
                } catch (JMSException ex) {
                    logger.log(Level.WARNING, "", ex);
                }
            }
            return new BroadcastAction(message);
        } else {
            return new BroadcastAction(o);
        }
    }

    /**
     * Return the current {@link Broadcaster}
     */
    public Broadcaster getBroadcaster() {
        return bc;
    }

    /**
     * Set the current {@link Broadcaster} to use when a cluster event happens.
     *
     * @param bc
     */
    public void setBroadcaster(Broadcaster bc) {
        this.bc = bc;
    }
}

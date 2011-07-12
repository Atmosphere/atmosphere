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
import org.atmosphere.util.AbstractBroadcasterProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

/**
 * Simple {@link org.atmosphere.cpr.Broadcaster} implementation based on JMS
 * <p/>
 * The {@link ConnectionFactory} name's is jms/atmosphereFactory The
 * {@link Topic} by constructing "BroadcasterId =
 * {@link org.atmosphere.cpr.Broadcaster#getID}
 * 
 * @author Jeanfrancois Arcand
 */
public class JMSBroadcaster extends AbstractBroadcasterProxy {
    private static final String JMS_TOPIC = JMSBroadcaster.class.getName()
            + ".topic";
    private static final String JNDI_NAMESPACE = JMSBroadcaster.class.getName()
            + ".JNDINamespace";
    private static final String JNDI_FACTORY_NAME = JMSBroadcaster.class
            .getName() + ".JNDIConnectionFactoryName";
    private static final String JNDI_TOPIC = JMSBroadcaster.class.getName()
            + ".JNDITopic";
    private static final Logger logger = LoggerFactory
            .getLogger(JMSBroadcaster.class);

    private Connection connection;
    private Session session;
    private Topic topic;
    private MessageConsumer consumer;
    private MessageProducer publisher;

    private String topicId = "atmosphere";
    private String factoryName = "atmosphereFactory";
    private String namespace = "jms/";

    public synchronized void configure(AtmosphereServlet.AtmosphereConfig config) {
        try {
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

            logger.info("Looking up Connection Factory {}", namespace + factoryName);
            Context ctx = new InitialContext();
            ConnectionFactory connectionFactory = (ConnectionFactory) ctx.lookup(namespace + factoryName);

            logger.info("Looking up topic: {}", topicId);
            topic = (Topic) ctx.lookup(namespace + topicId);

            connection = connectionFactory.createConnection();
            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

            publisher = session.createProducer(topic);
            connection.start();
            logger.info("JMS created for topic {}", topicId);
            // Unfortunately we need the getID() to complete the configuration
            // But setID() is called after configure(), therefore we do the
            // rest of the configuration in incomingBroadcast() (which is called
            // once during configuration). We cannot do all the configuration in
            // incomingBroadcast() though, as using bc.getAtmosphereConfig() would
            // introduce a race condition (the configuration is loaded in a different
            // thread).

            // Notify the async running thread on incomingBroadcast()
            this.notify();
        } catch (Exception e) {
            String msg = "Unable to configure JMSBroadcaster";
            logger.error(msg, e);
            throw new RuntimeException(msg, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void incomingBroadcast() {
        try {
            // This method is called by a task runner in an asynchronous fashion before the
            // call to configure(). Wait for the configure() method to finish
            synchronized(this) {
                while(session == null)
                    this.wait(1000);
            }
            String id = getID();
            if (id.startsWith("/*")) {
                id = "atmosphere";
            }
            if (consumer != null)
                consumer.close();

            logger.info("Create customer: {}", id);
            String selector = String.format("BroadcasterId = '%s'", id);

            consumer = session.createConsumer(topic, selector);
            consumer.setMessageListener(new MessageListener() {
                @Override
                public void onMessage(Message msg) {
                    try {
                        TextMessage textMessage = (TextMessage) msg;
                        String message = textMessage.getText();

                        if (message != null && bc != null) {
                            broadcastReceivedMessage(message);
                        }
                    } catch (JMSException ex) {
                        logger.warn("Failed to broadcast message", ex);
                    }
                }
            });
            logger.info("Consumer created for topic {}, with filter {}",
                    topicId, selector);
        } catch (Throwable ex) {
            String msg = "Unable to initialize JMSBroadcaster";
            logger.error(msg, ex);
            throw new IllegalStateException(msg, ex);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void outgoingBroadcast(Object message) {
        try {
            String id = getID();
            if (id.startsWith("/*")) {
                id = "atmosphere";
            }

            if (session == null) {
                throw new IllegalStateException("JMS Session is null");
            }

            TextMessage textMessage = session.createTextMessage(message
                    .toString());
            textMessage.setStringProperty("BroadcasterId", id);
            publisher.send(textMessage);
        } catch (JMSException ex) {
            logger.warn("Failed to send message over JMS", ex);
        }
    }

    /**
     * Close all related JMS factory, connection, etc.
     */
    @Override
    public void releaseExternalResources() {
        try {
            connection.close();
            session.close();
            consumer.close();
            publisher.close();
        } catch (Throwable ex) {
            logger.warn("releaseExternalResources", ex);
        }
    }
}

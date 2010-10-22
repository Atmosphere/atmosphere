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

import org.atmosphere.util.AbstractBroadcasterProxy;
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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Simple {@link org.atmosphere.cpr.Broadcaster} implementation based on JMS
 *
 * @author Jeanfrancois Arcand
 */
public class JMSBroadcaster extends AbstractBroadcasterProxy {
    private final Logger logger = LoggerUtils.getLogger();
    private Connection connection;
    private Session session;
    private MessageConsumer consumer;
    private MessageProducer publisher;
    private String clusterName;

    public JMSBroadcaster() {
        this(JMSBroadcaster.class.getSimpleName());
    }

    public JMSBroadcaster(String id) {
        this(id, "atmosphere-jms");
    }

    public JMSBroadcaster(String id, String clusterName) {
        super(id);
        this.clusterName = clusterName + "-id";

    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public void incomingBroadcast() {
        try {

            Context ctx = new InitialContext();
            ConnectionFactory connectionFactory =
                    (ConnectionFactory) ctx.lookup("jms/atmosphereFactory");

            Topic topic = (Topic) ctx.lookup("jms/" + clusterName);
            connection = connectionFactory.createConnection();
            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            consumer = session.createConsumer(topic, clusterName);
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
                        if (logger.isLoggable(Level.WARNING)) {
                            logger.log(Level.WARNING, "", ex);
                        }

                    }
                }
            });
            publisher = session.createProducer(topic);

            connection.start();
        } catch (Throwable ex) {
            throw new IllegalStateException("Unable to initialize JMSFilter", ex);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void outgoingBroadcast(Object message) {
        try {
            publisher.send(session.createTextMessage(message.toString()));
        } catch (JMSException ex) {
            logger.log(Level.WARNING, "", ex);
        }
    }
}
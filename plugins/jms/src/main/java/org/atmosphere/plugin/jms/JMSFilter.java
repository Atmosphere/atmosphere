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
public class JMSFilter implements MessageListener,ClusterBroadcastFilter {
    private static Logger logger = LoggerUtils.getLogger();

    private Connection connection;
    private Session session;
    private MessageConsumer consumer;
    private MessageProducer publisher;

    private String clusterName;

    private Broadcaster bc = null;
    private final ConcurrentLinkedQueue<String> receivedMessages =
            new ConcurrentLinkedQueue<String>();

    public JMSFilter(){
        this(null);
    }

    /**
     * Create a JMSFilter based filter.
     * @param bc the Broadcaster to use when receiving update from the cluster.
     */
    public JMSFilter(Broadcaster bc) {
        this(bc,"atmosphere-framework");
    }

    /**
     * Create a JMSFilter based filter.
     * @param bc the Broadcaster to use when receiving update from the cluster.
     * @param containerName the current WebServer'name.
     */
    public JMSFilter(Broadcaster bc, String containerName) {
        this(bc,containerName,"cluster-atmosphere");
    }


    /**
     * Create a JMSFilter based filter.
     * @param bc the Broadcaster to use when receiving update from the cluster.
     * @param containerName the current WebServer'name.
     * @param clusterName the cluster's group name.
     */
    public JMSFilter(Broadcaster bc, String containerName, String clusterName) {

    }

    public void setAddress(String clusterName){
        this.clusterName = clusterName;
    }

    /**
     * Preapre the cluter.
     */
    public void init() {
        try{

            Context ctx = new InitialContext();
            ConnectionFactory connectionFactory =
                    (ConnectionFactory)ctx.lookup("jms/atmosphereFactory");

            Topic topic =  (Topic)ctx.lookup("jms/" + clusterName);
            connection = connectionFactory.createConnection();
            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            consumer = session.createConsumer(topic,clusterName);
            consumer.setMessageListener(this);
            publisher = session.createProducer(topic);

            connection.start();
        } catch(Throwable ex){
            throw new IllegalStateException("Unable to initialize JMSFilter" ,ex);
        }
    }

    /**
     * Shutown the cluster.
     * TODO: Not sure we should close the cluster.
     */
    public void destroy(){

    }

    public void onMessage(Message msg) {
        try {
            TextMessage textMessage = (TextMessage) msg;
            String message = textMessage.getText();
            receivedMessages.offer(message);

            if (message != null && bc != null){
                bc.broadcast(message);
            }
        } catch (JMSException ex) {
            if (logger.isLoggable(Level.WARNING)){
                logger.log(Level.WARNING,"",ex);
            }

        }
    }

    /**
     * Every time a message gets broadcasted, make sure we update the cluster.
     * @param o the message to broadcast.
     * @return The same message.
     */
    public BroadcastAction filter(Object originalMessage, Object o) {
        if (o instanceof String){
            String message = (String)o;
            try {
                // Avoid re-broadcasting
                if (!receivedMessages.remove(message)) {
                    publisher.send(session.createTextMessage(message));
                }
            } catch (JMSException ex) {
                logger.log(Level.WARNING, "", ex);
            }
            return new BroadcastAction(message);
        } else {
            return new BroadcastAction(o);
        }
    }

    /**
     * Return the current {@link Broadcaster}
     */
    public Broadcaster getBroadcaster(){
        return bc;
    }

    /**
     * Set the current {@link Broadcaster} to use when a cluster event happens.
     * @param bc
     */
    public void setBroadcaster(Broadcaster bc){
        this.bc = bc;
    }
}

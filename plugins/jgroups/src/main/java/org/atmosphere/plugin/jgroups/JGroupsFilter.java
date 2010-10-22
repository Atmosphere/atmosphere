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
package org.atmosphere.plugin.jgroups;

import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.ClusterBroadcastFilter;
import org.jgroups.ChannelException;
import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.ReceiverAdapter;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Clustering support based on JGroupsFilter (http://jgroups.org)
 * 
 * @author Hubert Iwaniuk
 */
public class JGroupsFilter extends ReceiverAdapter implements ClusterBroadcastFilter{

    final static Logger logger = Logger.getLogger("JGroupsFilter");
    private JChannel jchannel;
    private String clusterName = "cluster-jgroups";
    private Broadcaster bc;
    private final ConcurrentLinkedQueue<String> receivedMessages =
            new ConcurrentLinkedQueue<String>();

    public JGroupsFilter() {
        this(null);
    }

    /**
     * Create a JGroupsFilter based filter.
     * @param bc the Broadcaster to use when receiving update from the cluster.
     */
    public JGroupsFilter(Broadcaster bc) {
        this(bc, "atmosphere-framework");
    }

    /**
     * Create a JGroupsFilter based filter.
     * @param bc the Broadcaster to use when receiving update from the cluster.
     * @param containerName the current WebServer'name.
     */
    public JGroupsFilter(Broadcaster bc, String containerName) {
        this(bc, containerName, "cluster-atmosphere");
    }

    /**
     * Create a JGroupsFilter based filter.
     * @param bc the Broadcaster to use when receiving update from the cluster.
     * @param containerName the current WebServer'name.
     * @param clusterName the cluster's group name.
     */
    public JGroupsFilter(Broadcaster bc, String containerName, String clusterName) {
        this.bc = bc;
        this.clusterName = clusterName;
    }

    public void setAddress(String clusterName) {
        this.clusterName = clusterName;
    }

    /**
     * Preapre the cluter.
     */
    public void init() {
        try {
            logger.log(Level.INFO, "Starting Atmosphere JGroups Clustering support");

            //initialize jgroups channel
            jchannel = new JChannel();
            //register for Group Events
            jchannel.setReceiver(this);
            //join group
            jchannel.connect(clusterName);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "", t);
        }
    }

    /**
     * Shutown the cluster.
     */
    public void destroy() {
        jchannel.shutdown();
    }

    /** {@inheritDoc} */
    @Override
    public void receive(final Message message) {
        final String msg = (String) message.getObject();
        if (message.getSrc() != jchannel.getLocalAddress()){
            if (msg != null) {
                receivedMessages.offer(msg);
                if (bc != null) {
                    bc.broadcast(msg);
                }
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
            // Avoid re-broadcasting
            if (!receivedMessages.remove(message)) {
                try {
                    jchannel.send(new Message(null, null, message));
                } catch (ChannelException e) {
                    logger.log(Level.WARNING, "", e);
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
     * @param bc
     */
    public void setBroadcaster(Broadcaster bc) {
        this.bc = bc;
    }
}

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
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.ClusterBroadcastFilter;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.jgroups.ChannelException;
import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.ReceiverAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Clustering support based on JGroupsFilter (http://jgroups.org)
 *
 * @author Hubert Iwaniuk
 */
public class JGroupsFilter extends ReceiverAdapter implements ClusterBroadcastFilter {

    private static final Logger logger = LoggerFactory.getLogger(JGroupsFilter.class);

    private JChannel jchannel;
    private Broadcaster bc;
    private final ConcurrentLinkedQueue<Object> receivedMessages = new ConcurrentLinkedQueue<Object>();

    public JGroupsFilter() throws InstantiationException, IllegalAccessException {
        this(BroadcasterFactory.getDefault().get(DefaultBroadcaster.class, "JGroupFilter"));
    }

    /**
     * Create a JGroupsFilter based filter.
     *
     * @param bc the Broadcaster to use when receiving update from the cluster.
     */
    public JGroupsFilter(Broadcaster bc) {
        this.bc = bc;
    }

    /**
     * Prepare the cluster.
     */
    public void init() {
        try {
            logger.info("Starting Atmosphere JGroups Clustering support with group name {}", JGroupsBroadcaster.CLUSTER_NAME);

            //initialize jgroups channel
            jchannel = new JChannel();
            //register for Group Events
            jchannel.setReceiver(this);
            //join group
            jchannel.connect(JGroupsBroadcaster.CLUSTER_NAME);
        } catch (Throwable t) {
            logger.warn("failed to connect to cluser", t);
        }
    }

    /**
     * Shutdown the cluster.
     */
    public void destroy() {
        jchannel.shutdown();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void receive(final Message message) {
        final Object msg = message.getObject();
        if (message.getSrc() != jchannel.getLocalAddress()) {
            if (msg != null) {
                if (msg != null && JGroupsBroadcaster.BroadcastMessage.class.isAssignableFrom(msg.getClass())) {
                    receivedMessages.offer(msg);
                    JGroupsBroadcaster.BroadcastMessage b = JGroupsBroadcaster.BroadcastMessage.class.cast(msg);
                    if (b.getTopicId().equalsIgnoreCase(bc.getID())) {
                        bc.broadcast(b.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Every time a message gets broadcasted, make sure we update the cluster.
     *
     * @param message the message to broadcast.
     * @return The same message.
     */
    public BroadcastAction filter(Object originalMessage, Object message) {
        // Avoid re-broadcasting
        if (!receivedMessages.remove(message)) {
            try {
                jchannel.send(new Message(null, null, new JGroupsBroadcaster.BroadcastMessage(bc.getID(), message)));
            } catch (ChannelException e) {
                logger.warn("failed to send message", e);
            }
        }
        return new BroadcastAction(message);
    }

    /**
     * Return the current {@link Broadcaster}
     */
    public Broadcaster getBroadcaster() {
        return bc;
    }

    @Override
    public void setUri(String name) {
        bc.setID(name);
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

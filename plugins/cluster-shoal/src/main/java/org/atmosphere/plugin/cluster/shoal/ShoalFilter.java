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
package org.atmosphere.plugin.cluster.shoal;

import com.sun.enterprise.ee.cms.core.CallBack;
import com.sun.enterprise.ee.cms.core.GMSConstants;
import com.sun.enterprise.ee.cms.core.GMSException;
import com.sun.enterprise.ee.cms.core.GMSFactory;
import com.sun.enterprise.ee.cms.core.GroupManagementService;
import com.sun.enterprise.ee.cms.core.MessageSignal;
import com.sun.enterprise.ee.cms.core.Signal;
import com.sun.enterprise.ee.cms.core.SignalAcquireException;
import com.sun.enterprise.ee.cms.core.SignalReleaseException;
import com.sun.enterprise.ee.cms.impl.client.FailureNotificationActionFactoryImpl;
import com.sun.enterprise.ee.cms.impl.client.FailureSuspectedActionFactoryImpl;
import com.sun.enterprise.ee.cms.impl.client.JoinNotificationActionFactoryImpl;
import com.sun.enterprise.ee.cms.impl.client.MessageActionFactoryImpl;
import com.sun.enterprise.ee.cms.impl.client.PlannedShutdownActionFactoryImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.ClusterBroadcastFilter;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Clustering support based on ShoalFilter (http://shoal.dev.java.net)
 * 
 * @author Jean-francois Arcand
 */
public class ShoalFilter implements CallBack, ClusterBroadcastFilter {
    final static Logger logger = Logger.getLogger("SimpleShoalGMSSample");
    final Object waitLock = new Object();
    private GroupManagementService gms;
    private String clusterName = "cluster-shoal";
    private String serverName = "Atmosphere-";
    private String topicName = "atmosphere-shoal";
    private Broadcaster bc;
    private final ConcurrentLinkedQueue<String> receivedMessages =
            new ConcurrentLinkedQueue<String>();
    private final static AtomicInteger count = new AtomicInteger();

    public ShoalFilter(){
        this(null);
    }


    /**
     * Create a ShoalFilter based filter.
     * @param bc the Broadcaster to use when receiving update from the cluster.
     */
    public ShoalFilter(Broadcaster bc) {
        this(bc,"atmosphere-framework");
    }

    /**
     * Create a ShoalFilter based filter.
     * @param bc the Broadcaster to use when receiving update from the cluster.
     * @param containerName the current WebServer'name.
     */
    public ShoalFilter(Broadcaster bc, String containerName) {
        this(bc,containerName,"cluster-atmosphere");
    }


    /**
     * Create a ShoalFilter based filter.
     * @param bc the Broadcaster to use when receiving update from the cluster.
     * @param containerName the current WebServer'name.
     * @param clusterName the cluster's group name.
     */
    public ShoalFilter(Broadcaster bc, String containerName, String clusterName) {
        this.bc = bc;
        try {
            serverName = InetAddress.getLocalHost().getHostAddress()
                    + "-" + containerName + "-" + count.getAndIncrement();
        } catch (UnknownHostException ex) {
            serverName += containerName;
        }
        this.clusterName = clusterName;
    }

    public void setClusterName(String clusterName){
        this.clusterName = clusterName;
    }

    /**
     * Preapre the cluter.
     */
    public void init() {
        try {
            logger.log(Level.INFO, "Starting Atmosphere Shoal Clustering support");

            //initialize Group Management Service
            gms = initializeGMS(serverName, clusterName);
            //register for Group Events
            registerForGroupEvents(gms);
            //join group
            joinGMSGroup(clusterName, gms);
        } catch (Throwable t) {
            logger.log(Level.FINE, "", t);
        }
    }

    /**
     * Shutown the cluster.
     * TODO: Not sure we should close the cluster.
     */
    public void destroy(){
        gms.shutdown(GMSConstants.shutdownType.INSTANCE_SHUTDOWN);
    }


    private GroupManagementService initializeGMS(String serverName, String clusterName) {
        return (GroupManagementService) GMSFactory.startGMSModule(serverName,
                clusterName, GroupManagementService.MemberType.CORE, null);
    }

    private void registerForGroupEvents(GroupManagementService gms) {
        gms.addActionFactory(new JoinNotificationActionFactoryImpl(this));
        gms.addActionFactory(new FailureSuspectedActionFactoryImpl(this));
        gms.addActionFactory(new FailureNotificationActionFactoryImpl(this));
        gms.addActionFactory(new PlannedShutdownActionFactoryImpl(this));
        gms.addActionFactory(new MessageActionFactoryImpl(this), topicName);
    }

    private void joinGMSGroup(String clusterName, GroupManagementService gms) throws GMSException {
        gms.join();
    }

    /**
     * Receive notification from the cluster.
     * @param signal
     */
    public void processNotification(Signal signal) {
        try {
            signal.acquire();
            String msg = null;
            if (signal instanceof MessageSignal) {
                msg = new String(((MessageSignal) signal).getMessage());
                receivedMessages.offer(msg);
            }
            signal.release();
            if (msg != null && bc != null){
                bc.broadcast(msg);
            }
        } catch (SignalAcquireException e) {
            logger.log(Level.WARNING, "Exception occured while acquiring signal" + e);
        } catch (SignalReleaseException e) {
            logger.log(Level.WARNING, "Exception occured while releasing signal" + e);
        }

    }

    /**
     * Every time a message gets broadcasted, make sure we update the cluster.
     * @param o the message to broadcast.
     * @return The same message.
     */
    public BroadcastAction filter(Object o) {
        if (o instanceof String){
            String message = (String)o;
            try {
                // Avoid re-broadcasting
                if (!receivedMessages.remove(message)) {
                    gms.getGroupHandle().sendMessage(topicName, message.getBytes());
                }
            } catch (GMSException ex) {
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

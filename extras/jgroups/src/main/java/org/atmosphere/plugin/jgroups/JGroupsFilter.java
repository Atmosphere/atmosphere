/*
 * Copyright 2012 Jean-Francois Arcand
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
package org.atmosphere.plugin.jgroups;


import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.ClusterBroadcastFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is attached to a Broadcaster you want to have in a clustered situation.
 * <p/>
 * Each clustered broadcaster should have its own instance of a JGroupsFilter and
 * likewise, each JGroupsFilter should have a circular reference back to that broadcaster.
 * <p/>
 * Therefore, when the JGroupsFilter is added to the Broadcaster config,
 * remember to make the reference circular by calling JGroupsFilter.setBroadcaster(bc)
 * or simply using the constructor with the Broadcaster to begin with.
 * <p/>
 * Uri is not currently used because the 'cluster name' is driven from the
 * JGroupsChannel itself.  I suppose it could be used to 'look up' the JGroupsChannel
 * if there is a registry of them implemented somehow, but its easier to just
 * inject the JGroupsChannel into the filter.
 *
 * @author Jean-Francois Arcand (original version)
 * @author westraj
 */
public class JGroupsFilter implements ClusterBroadcastFilter {

    private static final Logger logger = LoggerFactory
            .getLogger(JGroupsFilter.class);

    private JGroupsChannel jchannel;
    private Broadcaster bc;

    public JGroupsFilter(){
        jchannel = DefaultJGroupsChannelFactory.getDefaultJGroupsChannel();
    }

    public JGroupsFilter(String jGroupsFilterLocation){
        jchannel = DefaultJGroupsChannelFactory.getDefaultJGroupsChannel(jGroupsFilterLocation);
    }

    /**
     * Constructor
     *
     * @param jchannel
     */
    public JGroupsFilter(JGroupsChannel jchannel) {
        // no default broadcaster is created. Must set a specific one now with setBroadcaster()
        this.jchannel = jchannel;
    }

    /**
     * Constructor with broadcaster
     *
     * @param jchannel
     * @param bc
     */
    public JGroupsFilter(JGroupsChannel jchannel, Broadcaster bc) {
        this(jchannel);
        this.setBroadcaster(bc);
    }

    /*
      * (non-Javadoc)
      *
      * @see org.atmosphere.cpr.BroadcastFilterLifecycle#destroy()
      */
    @Override
    public void destroy() {
        jchannel.removeBroadcaster(bc);
        this.bc = null;
    }

    /*
      * (non-Javadoc)
      *
      * @see org.atmosphere.cpr.BroadcastFilterLifecycle#init()
      */
    @Override
    public void init() {

    }


    /**
     * Every time a message gets broadcasted, make sure we update the cluster.
     *
     * @param message the message to broadcast.
     * @return The same message.
     */
    public BroadcastAction filter(Object originalMessage, Object message) {
        if (bc != null) {
            this.jchannel.send(this.bc.getID(), message);
        }

        return new BroadcastAction(message);
    }

    /*
      * (non-Javadoc)
      *
      * @see org.atmosphere.cpr.ClusterBroadcastFilter#getBroadcaster()
      */
    @Override
    public Broadcaster getBroadcaster() {
        return bc;
    }

    /*
      * (non-Javadoc)
      *
      * @see
      * org.atmosphere.cpr.ClusterBroadcastFilter#setBroadcaster(org.atmosphere
      * .cpr.Broadcaster)
      */
    @Override
    public void setBroadcaster(Broadcaster bc) {
        this.bc = bc;

        // register this filter's broadcaster with the JGroupsChannel
        if (bc != null) jchannel.addBroadcaster(bc);
    }

    /*
      * (non-Javadoc)
      *
      * @see org.atmosphere.cpr.ClusterBroadcastFilter#setUri(java.lang.String)
      */
    @Override
    public void setUri(String clusterUri) {
        // NO OPS
    }


}

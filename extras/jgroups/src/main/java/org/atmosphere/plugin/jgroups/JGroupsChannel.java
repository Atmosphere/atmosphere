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
import org.jgroups.ChannelException;
import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.ReceiverAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * JGroupsChannel establishes a connection to a
 * JGroups cluster.  It sends/receives over that and forwards
 * the received messages to the appropriate Broadcaster on its 
 * node.
 * 
 * Best practice would have only 1 of these per Atmosphere application.
 * Each JGroupsFilter instance has a reference to the
 * singleton JGroupsChannel object and registers its broadcaster via
 * the addBroadcaster() method.
 * 
 * @author westraj
 *
 */
public class JGroupsChannel extends ReceiverAdapter {
	private static final Logger logger = LoggerFactory
			.getLogger(JGroupsChannel.class);
			
	/** JGroups  JChannel object */
	private final JChannel jchannel;
	
	/** JChannel cluster name */
	private final String clusterName;
	
	/** globally unique ID for this JGroupsChannel object to tag it's messages with */
	private final String id;
	
	/** registers all the Broadcasters that are filtered via a JGroupsFilter */
	private final Map<String, Broadcaster> broadcasters = new HashMap<String, Broadcaster>();
	
	/** Holds original messages (not BroadcastMessage) received over a cluster broadcast */
	private final ConcurrentLinkedQueue<Object> receivedMessages = new ConcurrentLinkedQueue<Object>();
	
	/**
	 * Constructor
	 * 
	 * @param jchannel  unconnected JGroups  JChannel object
	 * @param clusterName  name of the group to connect the JChannel to
	 */
	public JGroupsChannel(JChannel jchannel, String clusterName) {
		if (jchannel.isConnected()) throw new IllegalArgumentException("JChannel already connected");
		
		this.jchannel = jchannel;
		this.clusterName = clusterName;
		this.id = UUID.randomUUID().toString() + "_"+System.currentTimeMillis();
	}
	
	/**
	 * Connect to the cluster
	 * @throws Exception
	 */
	public void init() throws Exception {
		logger.info(
				"Starting Atmosphere JGroups Clustering support with group name {}",
				this.clusterName);
		try {
			this.jchannel.setReceiver(this);
			this.jchannel.connect(clusterName);
		} catch (Exception e) {
			logger.warn("Failed to connect to cluster: " + this.clusterName, e);
			throw e;
		}
		
	}

	/**
     * Shutdown the cluster.
     */
    public void destroy() {
        jchannel.shutdown();
        receivedMessages.clear();
        broadcasters.clear();
    }
    
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void receive(final Message jgroupMessage) {
		final Object payload = jgroupMessage.getObject();
		
		if (payload == null) return;
		
		if (BroadcastMessage.class.isAssignableFrom(payload.getClass())) {
			BroadcastMessage broadcastMsg = BroadcastMessage.class.cast(payload);
			
			// make sure the message wasn't sent from yourself
			if (this.id.equalsIgnoreCase(broadcastMsg.getClusterChannelId())) return;
			
			// original message from the sending node's JGroupsFilter.filter() method 
			Object origMessage = broadcastMsg.getMessage();
			
			// add original message to list to check re-broadcast logic in send()
			receivedMessages.offer(origMessage);
			
			String topicId = broadcastMsg.getTopic();
			if (broadcasters.containsKey(topicId)) {
				Broadcaster bc = broadcasters.get(topicId);
				try {
					bc.broadcast(origMessage).get();
				} catch(Exception ex) {
					logger.error("Failed to broadcast message received over the JGroups cluster "+this.clusterName, ex);
				}
			}
			
		}
	}
	
	/**
	 * Called from a ClusterBroadcastFilter filter() method
	 * to send the message over to other Atmosphere cluster nodes
	 * 
	 * @param topic
	 * @param message
	 */
	public void send(String topic, Object message) {
		if (jchannel.isConnected()) {
			// Avoid re-broadcasting to cluster by checking if the message was 
			// one already received from another cluster node
	        if (!receivedMessages.remove(message)) {
	            try {
	            	
	            	BroadcastMessage broadcastMsg = new BroadcastMessage(this.id, topic, message);
	            	Message jgroupMsg = new Message(null, null, broadcastMsg);
	            	
	                jchannel.send(jgroupMsg);
	            } catch (ChannelException e) {
	                logger.warn("Failed to send message {}", message, e);
	            }
	        }
		}
	}
	
	/**
	 * Adds/replaces the broadcaster to the JGroupsChannel
	 * @param broadcaster
	 */
	public void addBroadcaster(Broadcaster broadcaster) {
		this.broadcasters.put(broadcaster.getID(), broadcaster);
	}
	
	/**
	 * Removes the broadcaster from the JGroupsChannel
	 * @param broadcaster
	 */
	public void removeBroadcaster(Broadcaster broadcaster) {
		this.broadcasters.remove(broadcaster.getID());
	}
	
	
}

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

import java.io.Serializable;

/**
 * Container for holding the true message when broadcasting between cluster nodes.
 * It provides knowledge of 'who' sent the message, using the generated
 * globally unique Id, clusterChannelId instead of a JChannel host.  This is because the
 * JGroupsChannel the sent it will get the message too and needs to know
 * to discard it.  The standard JGroupsFilter implementation uses the JChannel address, 
 * 
 * Example: 
 * org.atmosphere.plugins.jgroups.JGroupsFilter.receive() says...
 * 
 * if (message.getSrc() != jchannel.getLocalAddress()) {
 * 
 * This is not good enough if multiple JChannels are started on
 * a single host, so each JGroupsChannel instance should have a globally unique Id instead
 * and set that value into each BroadcastMessage it sends.
 * 
 * @author westraj
 *
 */
public class BroadcastMessage implements Serializable {

	/**
	 * default
	 */
	private static final long serialVersionUID = 1L;
	private final String clusterChannelId;
	private final String topic;
	private final Object message;
		
	public BroadcastMessage(String clusterChannelId, String topic, Object message) {
		this.clusterChannelId = clusterChannelId;
		this.topic = topic;
		this.message = message;
	}
	
	/**
	 * @return the clusterChannelId
	 */
	public String getClusterChannelId() {
		return clusterChannelId;
	}
	/**
	 * @return the topic
	 */
	public String getTopic() {
		return topic;
	}
	/**
	 * @return the message
	 */
	public Object getMessage() {
		return message;
	}
}

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
 * 
 * @author westraj
 *
 */
public class BroadcastMessage implements Serializable {

	/**
	 * default
	 */
	private static final long serialVersionUID = 1L;
	private final String topic;
	private final Object message;
		
	public BroadcastMessage(String topic, Object message) {
		this.topic = topic;
		this.message = message;
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

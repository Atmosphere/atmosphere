/*
 * Copyright 2012 Sebastien Dionne
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
package org.atmosphere.protocol.socketio;

/**
 * 
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 *
 */
public enum ConnectionState {
	UNKNOWN(-1),
	CONNECTING(0),
	CONNECTED(1),
	CLOSING(2),
	CLOSED(3);

	private int value;
	private ConnectionState(int v) { this.value = v; }
	public int value() { return value; }
	
	public static ConnectionState fromInt(int val) {
		switch (val) {
		case 0:
			return CONNECTING;
		case 1:
			return CONNECTED;
		case 2:
			return CLOSING;
		case 3:
			return CLOSED;
		default:
			return UNKNOWN;
		}
	}
}

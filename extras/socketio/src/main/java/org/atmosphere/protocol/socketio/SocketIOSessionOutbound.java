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

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.Action;


/**
 * 
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 *
 */
public interface SocketIOSessionOutbound extends SocketIOOutbound {
	
	Action handle(HttpServletRequest request, HttpServletResponse response, SocketIOSession session) throws IOException;
	/**
	 * Cause connection and all activity to be aborted and all resources to be released.
	 * The handler is expected to call the session's onShutdown() when it is finished.
	 * The only session method that the handler can legally call after this is onShutdown();
	 */
	void abort();
}

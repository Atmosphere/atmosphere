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
package org.atmosphere.protocol.socketio.transport;

import java.io.IOException;

import javax.servlet.ServletConfig;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.protocol.socketio.SocketIOAtmosphereHandler;
import org.atmosphere.protocol.socketio.SocketIOSessionFactory;

/**
 * 
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 *
 */
public interface Transport {

	/**
	 * @return The name of the transport instance.
	 */
	String getName();

	/**
	 * 
	 */
	void destroy();

	/**
	 * 
	 * @param resource
	 * @param atmosphereHandler
	 * @param sessionFactory
	 * @return
	 * @throws IOException
	 */
	Action handle(AtmosphereResourceImpl resource, SocketIOAtmosphereHandler atmosphereHandler, SocketIOSessionFactory sessionFactory) throws IOException;
}

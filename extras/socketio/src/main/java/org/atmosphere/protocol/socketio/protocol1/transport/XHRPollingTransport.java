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
package org.atmosphere.protocol.socketio.protocol1.transport;

import java.io.IOException;

import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.protocol.socketio.SocketIOSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 *
 */
public class XHRPollingTransport extends XHRTransport {
	
	private static final Logger logger = LoggerFactory.getLogger(XHRPollingTransport.class);
	
	public static final String TRANSPORT_NAME = "xhr-polling";

	public XHRPollingTransport(int bufferSize) {
		super(bufferSize);
	}

	@Override
	public String getName() {
		return TRANSPORT_NAME;
	}

	protected XHRPollingSessionHelper createHelper(SocketIOSession session) {
		return new XHRPollingSessionHelper(session);
	}
	
	protected class XHRPollingSessionHelper extends XHRSessionHelper {

		XHRPollingSessionHelper(SocketIOSession session) {
			super(session, false);
		}

		protected void startSend(HttpServletResponse response) throws IOException {
			response.setContentType("text/plain; charset=UTF-8");
			response.setCharacterEncoding("UTF-8");
		}

		@Override
		protected void writeData(HttpServletResponse response, String data) throws IOException {
			logger.warn("Response HashCode=" + response.hashCode());
			logger.error("calling from " + this.getClass().getName() + " : " + "writeData(string) = " + data);
			//response.getWriter().print(data);
			response.getOutputStream().write(data.getBytes("UTF-8"));
			logger.error("WRITE SUCCESS calling from " + this.getClass().getName() + " : " + "writeData(string) = " + data);
		}

		protected void finishSend(HttpServletResponse response) throws IOException {
			response.flushBuffer();
			response.getOutputStream().flush();
		};

		protected void customConnect(HttpServletRequest request, HttpServletResponse response) throws IOException {
			startSend(response);
			//writeData(response, "1::"  + request.getRequestURI() + "/"+ request.getQueryString());
			writeData(response, "1::");
		}

	}

}

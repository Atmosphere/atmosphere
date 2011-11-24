/**
 * The MIT License
 * Copyright (c) 2010 Tad Glines
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.atmosphere.protocol.socketio.protocol1.transport;


/**
 * Jetty will close a connection even if there is continuous outbound data if the request's response
 * is not completed within maxIdleTime milliseconds. This is not appropriate for a persistent connection
 * SocketIO transport. IN order to prevent this, without disabling the maxIdleTime completely,
 * this class is used to obtain a @{link IdleCheck} instance that can be used to reset the idle timeout.
 */
public class ConnectionTimeoutPreventor {
	interface IdleCheck {
		void activity();
	}

	/**
	 * This must be called within the context of an active HTTP request.
	 */
	public static IdleCheck newTimeoutPreventor() {
		/*
		HttpConnection httpConnection = HttpConnection.getCurrentConnection();
		if (httpConnection != null) {
			EndPoint endPoint = httpConnection.getEndPoint();
			if (endPoint instanceof AsyncEndPoint) {
				((AsyncEndPoint)endPoint).cancelIdle();
			}
			if (endPoint instanceof SelectChannelEndPoint) {
				final SelectChannelEndPoint scep = (SelectChannelEndPoint)endPoint;
				scep.cancelIdle();
				return new IdleCheck() {
					@Override
					public void activity() {
						scep.scheduleIdle();
					}
				};
			} else {
				return new IdleCheck() {
					@Override
					public void activity() {
						// Do nothing
					}
				};
			}
		} else {
			return null;
		}
		*/
		
		return new IdleCheck() {
			@Override
			public void activity() {
				// Do nothing
			}
		};
		
	}
}

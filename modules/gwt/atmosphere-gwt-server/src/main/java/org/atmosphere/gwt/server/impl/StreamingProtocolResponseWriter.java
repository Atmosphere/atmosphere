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

package org.atmosphere.gwt.server.impl;

import com.google.gwt.rpc.server.ClientOracle;
import com.google.gwt.user.server.rpc.SerializationPolicy;
import java.io.IOException;
import java.io.Serializable;
import java.util.List;

/**
 *
 * @author p.havelaar
 */
abstract public class StreamingProtocolResponseWriter extends ManagedStreamResponseWriter  {

	private static final int MAX_PADDING_REQUIRED = 2048;
	private static final String PADDING_STRING;
	static {
		char[] padding = new char[MAX_PADDING_REQUIRED+1];
		for (int i = 0; i < padding.length - 1; i++) {
			padding[i] = '*';
		}
		padding[padding.length - 1] = '\n';
		PADDING_STRING = new String(padding);
	}

	public StreamingProtocolResponseWriter(GwtAtmosphereResourceImpl resource, SerializationPolicy serializationPolicy, ClientOracle clientOracle) {
		super(resource, serializationPolicy, clientOracle);

	}

    abstract String getContentType();
    
	@Override
	public void initiate() throws IOException {
		getResponse().setContentType(getContentType());

		String origin = getRequest().getHeader("Origin");
		if (origin != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Origin: " + origin);
            }
			getResponse().setHeader("Access-Control-Allow-Origin", origin);
		}

		super.initiate();

		// send connection parameters to client
		writer.append('!').append(String.valueOf(resource.getHeartBeatInterval())).append(':');
        writer.append(String.valueOf(connectionID)).append('\n');
	}


	static CharSequence escape(CharSequence string) {
		int length = (string != null) ? string.length() : 0;
		int i = 0;
		loop: while (i < length) {
			char ch = string.charAt(i);
			switch (ch) {
			case '\\':
			case '\n':
				break loop;
			}
			i++;
		}

		if (i == length)
			return string;

		StringBuilder str = new StringBuilder(string.length() * 2);
		str.append(string, 0, i);
		while (i < length) {
			char ch = string.charAt(i);
			switch (ch) {
			case '\\':
				str.append("\\\\");
				break;
			case '\n':
				str.append("\\n");
				break;
			default:
				str.append(ch);
			}
			i++;
		}
		return str;
	}

	@Override
	protected CharSequence getPadding(int padding) {
		if (padding > PADDING_STRING.length() - 1) {
			StringBuilder result = new StringBuilder(padding);
			while (padding > 0) {
                if (padding > PADDING_STRING.length() -1) {
                    result.append(PADDING_STRING);
                    padding -= PADDING_STRING.length() -1;
                } else {
                    result.append(PADDING_STRING.substring(PADDING_STRING.length() -1 -padding));
                    padding = 0;
                }
			}
            return result.toString();
		}
		else {
			return PADDING_STRING.substring(PADDING_STRING.length() - padding -1);
		}
	}

	@Override
	protected void doSendError(int statusCode, String message) throws IOException {
		getResponse().setStatus(statusCode);
		if (message != null) {
			writer.append(message);
		}
	}

	@Override
	protected void doWrite(List<? extends Serializable> messages) throws IOException {
		for (Serializable message : messages) {
			CharSequence string;
			if (message instanceof CharSequence) {
				string = escape((CharSequence) message);
				if (string == message) {
					writer.append('|');
				}
				else {
					writer.append(']');
				}
			}
			else {
				string = serialize(message);
			}

			writer.append(string).append('\n');
		}
	}

	@Override
	protected boolean isOverRefreshLength(int written) {
		if (length != null) {
			return written > length;
		}
		else {
			return written > 5 * 1024 * 1024;
		}
	}

	@Override
	protected void doHeartbeat() throws IOException {
		writer.append("#\n");
	}

	@Override
	protected void doTerminate() throws IOException {
		writer.append("?\n");
	}

	@Override
	protected void doRefresh() throws IOException {
		writer.append("@\n");
	}

}

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
public class OperaEventSourceResponseWriter extends GwtResponseWriterImpl {

    public OperaEventSourceResponseWriter(GwtAtmosphereResourceImpl resource, SerializationPolicy serializationPolicy, ClientOracle clientOracle) {
        super(resource, serializationPolicy, clientOracle);
    }

	@Override
	public void initiate() throws IOException {
		getResponse().setContentType("application/x-dom-event-stream");

		super.initiate();

		writer.append("Event: c\ndata: c")
                .append(String.valueOf(resource.getHeartBeatInterval())).append(':')
                .append(String.valueOf(connectionID)).append("\n\n");
	}

	@Override
	protected void doSendError(int statusCode, String message) throws IOException {
		getResponse().setContentType("application/x-dom-event-stream");
		writer.append("Event: c\ndata: e").append(String.valueOf(statusCode));
		if (message != null) {
			writer.append(' ').append(HTTPRequestResponseWriter.escape(message));
		}
		writer.append("\n\n");
	}

	@Override
	protected void doSuspend() throws IOException {
	}

	@Override
	protected void doWrite(List<? extends Serializable> messages) throws IOException {
		for (Serializable message : messages) {
			CharSequence string;
			char event;
			if (message instanceof CharSequence) {
				string = HTTPRequestResponseWriter.escape((CharSequence) message);
				event = 's';
			}
			else {
				string = serialize(message);
				event = 'o';
			}
			writer.append("Event: ").append(event).append('\n');
			writer.append("data: ").append(string).append("\n\n");
		}
	}

	@Override
	protected void doHeartbeat() throws IOException {
		writer.append("Event: c\ndata: h\n\n");
	}

	@Override
	public void doTerminate() throws IOException {
		writer.append("Event: c\ndata: d\n\n");
	}

}

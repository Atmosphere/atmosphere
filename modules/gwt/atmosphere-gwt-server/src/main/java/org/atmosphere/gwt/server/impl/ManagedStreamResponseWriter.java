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
import java.io.OutputStream;
import java.io.Serializable;
import java.util.List;

/**
 *
 * @author p.havelaar
 */
abstract public class ManagedStreamResponseWriter extends GwtResponseWriterImpl {

	private CountOutputStream countOutputStream;
	private boolean refresh;

	protected Integer length;

	protected final boolean chrome;

	public ManagedStreamResponseWriter(GwtAtmosphereResourceImpl resource, SerializationPolicy serializationPolicy, ClientOracle clientOracle) {
		super(resource, serializationPolicy, clientOracle);

		String userAgent = resource.getAtmosphereResource().getRequest().getHeader("User-Agent");
		chrome = userAgent != null && userAgent.contains("Chrome");
	}

	@Override
	protected OutputStream getOutputStream(OutputStream outputStream) {
		countOutputStream = new CountOutputStream(outputStream);
		return countOutputStream;
	}

	@Override
	protected void doSuspend() throws IOException {
		int paddingRequired;
		String paddingParameter = getRequest().getParameter("padding");
		if (paddingParameter != null) {
			paddingRequired = Integer.parseInt(paddingParameter);
		}
		else {
			paddingRequired = getPaddingRequired();
		}

		String lengthParameter = getRequest().getParameter("length");
		if (lengthParameter != null) {
			length = Integer.parseInt(lengthParameter);
		}

		if (paddingRequired > 0) {
			countOutputStream.setIgnoreFlush(true);
			writer.flush();

			int written = countOutputStream.getCount();
            
			if (paddingRequired > written) {
				CharSequence padding = getPadding(paddingRequired - written);
				if (padding != null) {
					writer.append(padding);
				}
			}

			countOutputStream.setIgnoreFlush(false);
		}
	}

	@Override
	public synchronized void write(List<? extends Serializable> messages, boolean flush) throws IOException {
		super.write(messages, flush);
		checkLength();
	}

	@Override
	public synchronized void heartbeat() throws IOException {
		super.heartbeat();
		checkLength();
	}

	private void checkLength() throws IOException {
		int count = countOutputStream.getCount();
        // Chrome seems to have a problem with lots of small messages consuming lots of memory.
        // I'm guessing for each readyState = 3 event it copies the responseText from its IO system to its
        // JavaScript
        // engine and does not clean up all the events until the HTTP request is finished.
        if (chrome) {
            count = 2 * count;
        }
        if (!refresh && isOverRefreshLength(count)) {
            refresh = true;
            doRefresh();
        }
	}

	protected abstract void doRefresh() throws IOException;

	protected abstract int getPaddingRequired();

	protected abstract CharSequence getPadding(int padding);

	protected abstract boolean isOverRefreshLength(int written);
}

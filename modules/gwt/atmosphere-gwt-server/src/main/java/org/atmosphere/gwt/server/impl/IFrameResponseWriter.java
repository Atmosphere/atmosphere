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
public class IFrameResponseWriter extends ManagedStreamResponseWriter {

    public IFrameResponseWriter(GwtAtmosphereResourceImpl resource, SerializationPolicy serializationPolicy, ClientOracle clientOracle) {
        super(resource, serializationPolicy, clientOracle);
    }

	// IE requires padding to start processing the page.
	private static final int PADDING_REQUIRED = 256;

	private static final String HEAD = "<html><body onload='parent.d()'><script>";
	private static final String MID = "parent.c(";
	private static final String TAIL = ");var m=parent.m;var h=parent.h;</script>";

	private static final String PADDING_STRING;
	static {
		// the required padding minus the length of the heading
		int capacity = PADDING_REQUIRED - HEAD.length() - MID.length() - TAIL.length();
		char[] padding = new char[capacity];
		for (int i = 0; i < capacity; i++) {
			padding[i] = ' ';
		}
		PADDING_STRING = new String(padding);
	}

	@Override
	public void initiate() throws IOException {
		getResponse().setContentType("text/html");

		String origin = getRequest().getHeader("Origin");
		if (origin != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Origin: " + origin);
            }
			getResponse().setHeader("Access-Control-Allow-Origin", origin);
		}

		super.initiate();

		writer.append(HEAD);
		String domain = getRequest().getParameter("d");
		if (domain != null) {
			writer.append("document.domain='");
			writer.append(domain);
			writer.append("';");
		}
		writer.append(MID);
		writer.append(Integer.toString(resource.getHeartBeatInterval()));
        writer.append(',').append(String.valueOf(connectionID));
		writer.append(TAIL);
	}

	@Override
	protected int getPaddingRequired() {
		return PADDING_REQUIRED;
	}

	@Override
	protected CharSequence getPadding(int padding) {
		if (padding > PADDING_STRING.length()) {
			StringBuilder result = new StringBuilder(padding);
			for (int i = 0; i < padding; i++) {
				result.append(' ');
			}
			return result;
		}
		else {
			return PADDING_STRING.substring(0, padding);
		}
	}

	@Override
	protected void doSendError(int statusCode, String message) throws IOException {
		getResponse().setContentType("text/html");
		writer.append("<html><script>parent.e(").append(Integer.toString(statusCode));
		if (message != null) {
			writer.append(",'").append(escapeString(message)).append('\'');
		}
		writer.append(")</script></html>");
	}

	@Override
	protected void doWrite(List<? extends Serializable> messages) throws IOException {
		writer.append("<script>m(");
		boolean first = true;
		for (Serializable message : messages) {
			CharSequence string;
			if (message instanceof CharSequence) {
				string = "]" + escapeString((CharSequence) message);
			}
			else {
				string = escapeObject(serialize(message));
			}
			if (first) {
				first = false;
			}
			else {
				writer.append(',');
			}
			writer.append('\'').append(string).append('\'');
		}
		writer.append(")</script>");
	}

	@Override
	protected void doHeartbeat() throws IOException {
		writer.append("<script>h();</script>");
	}

	@Override
	protected void doTerminate() throws IOException {
		writer.append("<script>parent.t();</script>");
	}

	@Override
	protected void doRefresh() throws IOException {
		writer.append("<script>parent.r();</script>");
	}

	@Override
	protected boolean isOverRefreshLength(int written) {
		if (length != null) {
			return written > length;
		}
		else {
			return written > 4 * 1024 * 1024;
		}
	}

	private static CharSequence escapeString(CharSequence string) {
		int length = (string != null) ? string.length() : 0;
		int i = 0;
		loop: while (i < length) {
			char ch = string.charAt(i);
			switch (ch) {
			case '\'':
			case '\\':
			case '/':
			case '\b':
			case '\f':
			case '\n':
			case '\r':
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
			case '\'':
				str.append("\\\'");
				break;
			case '\\':
				str.append("\\\\");
				break;
			case '/':
				str.append("\\/");
				break;
			case '\b':
				str.append("\\b");
				break;
			case '\f':
				str.append("\\f");
				break;
			case '\n':
				str.append("\\n");
				break;
			case '\r':
				str.append("\\r");
				break;
			case '\t':
				str.append("\\t");
				break;
			default:
				str.append(ch);
			}
			i++;
		}
		return str;
	}

	private static CharSequence escapeObject(CharSequence string) {
		int length = (string != null) ? string.length() : 0;
		int i = 0;
		loop: while (i < length) {
			char ch = string.charAt(i);
			switch (ch) {
			case '\'':
			case '\\':
			case '/':
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
			case '\'':
				str.append("\\\'");
				break;
			case '\\':
				str.append("\\\\");
				break;
			case '/':
				str.append("\\/");
				break;
			default:
				str.append(ch);
			}
			i++;
		}
		return str;
	}
}
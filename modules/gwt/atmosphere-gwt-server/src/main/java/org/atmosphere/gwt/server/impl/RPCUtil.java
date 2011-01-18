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
import com.google.gwt.rpc.server.HostedModeClientOracle;
import com.google.gwt.rpc.server.WebModeClientOracle;
import com.google.gwt.user.server.rpc.SerializationPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import org.atmosphere.gwt.shared.Constants;

/**
 *
 * @author p.havelaar
 */
public class RPCUtil {

	protected static final String CLIENT_ORACLE_EXTENSION = ".gwt.rpc";

	public static SerializationPolicy createSimpleSerializationPolicy() {
        if (policy != null) {
            return policy;
        }
		policy = new SerializationPolicy() {
			@Override
			public boolean shouldDeserializeFields(final Class<?> clazz) {
				return Object.class != clazz;
			}

			@Override
			public boolean shouldSerializeFields(final Class<?> clazz) {
				return Object.class != clazz;
			}

			@Override
			public void validateDeserialize(final Class<?> clazz) {
			}

			@Override
			public void validateSerialize(final Class<?> clazz) {
			}
		};
        return policy;
	}

	protected static ClientOracle getClientOracle(HttpServletRequest request, ServletContext context) throws IOException {
		String permutationStrongName = request.getParameter(Constants.STRONG_NAME_PARAMETER);
		if (permutationStrongName == null) {
			return null;
		}

		ClientOracle toReturn;
		synchronized (clientOracleCache) {
			if (clientOracleCache.containsKey(permutationStrongName)) {
				toReturn = clientOracleCache.get(permutationStrongName).get();
				if (toReturn != null) {
					return toReturn;
				}
			}

			if ("HostedMode".equals(permutationStrongName)) {
				// if (!allowHostedModeConnections()) {
				// throw new SecurityException("Blocked hosted mode request");
				// }
				toReturn = new HostedModeClientOracle();
			}
			else {
				String moduleBase = request.getParameter(Constants.MODULE_BASE_PARAMETER);
				if (moduleBase == null) {
					return null;
				}

				String basePath = new URL(moduleBase).getPath();
				if (basePath == null) {
					throw new MalformedURLException("Blocked request without GWT base path parameter (XSRF attack?)");
				}

				String contextPath = context.getContextPath();
				if (!basePath.startsWith(contextPath)) {
					throw new MalformedURLException("Blocked request with invalid GWT base path parameter (XSRF attack?)");
				}
				basePath = basePath.substring(contextPath.length());

				InputStream in = findClientOracleData(basePath, permutationStrongName, context);

				toReturn = WebModeClientOracle.load(in);
			}
			clientOracleCache.put(permutationStrongName, new SoftReference<ClientOracle>(toReturn));
		}

		return toReturn;
	}

	private static InputStream findClientOracleData(String requestModuleBasePath, String permutationStrongName
            , ServletContext context) throws IOException {
		String resourcePath = requestModuleBasePath + permutationStrongName + CLIENT_ORACLE_EXTENSION;
		InputStream in = context.getResourceAsStream(resourcePath);
		if (in == null) {
			throw new IOException("Could not find ClientOracle data for permutation " + permutationStrongName);
		}
		return in;
	}

	private static final Map<String, SoftReference<ClientOracle>> clientOracleCache = new HashMap<String, SoftReference<ClientOracle>>();
    private static SerializationPolicy policy;

}

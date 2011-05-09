/*
 * Copyright 2011 Jeanfrancois Arcand
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

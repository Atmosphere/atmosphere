/*
 * Copyright 2015 Async-IO.org
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
package org.atmosphere.util.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Fork of https://code.google.com/p/jgoogleanalytics/
 * Simple class peforming HTTP Get method on the requested url
 *
 * @author : Siddique Hameed
 * @version : 0.1
 */
public class HTTPGetMethod {
    private static final String GET_METHOD_NAME = "GET";
    private static final String SUCCESS_MESSAGE = "JGoogleAnalytics: Tracking Successful!";
    private static String uaName; // User Agent name
    private static String osString = "Unknown";
    private final Logger logger = LoggerFactory.getLogger(HTTPGetMethod.class);

    HTTPGetMethod() {
        // Initialise the static parameters if we need to.
        if (uaName == null) {
            uaName = "Java/" + System.getProperty("java.version"); // java version info appended
            // os string is architecture+osname+version concatenated with _
            osString = System.getProperty("os.arch");
            if (osString == null || osString.length() < 1) {
                osString = "";
            } else {
                osString += "; ";
                osString += System.getProperty("os.name") + " "
                        + System.getProperty("os.version");
            }
        }
    }

    public void request(String urlString) {
        HttpURLConnection urlConnection = null;
        try {
            URL url = new URL(urlString);
            urlConnection = openURLConnection(url);
            urlConnection.setInstanceFollowRedirects(true);
            urlConnection.setRequestMethod(GET_METHOD_NAME);
            urlConnection.setRequestProperty("User-agent", uaName + " (" + osString + ")");

            logger.debug("Sending Server's information to Atmosphere's Google Analytics {} {}", urlString, uaName + " (" + osString + ")");
            urlConnection.connect();
            int responseCode = getResponseCode(urlConnection);
            if (responseCode != HttpURLConnection.HTTP_OK) {
                logError("JGoogleAnalytics: Error tracking, url=" + urlString);
            } else {
                logMessage(SUCCESS_MESSAGE);
            }
        } catch (Exception e) {
            logError(e.getMessage());
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    protected int getResponseCode(HttpURLConnection urlConnection)
            throws IOException {
        return urlConnection.getResponseCode();
    }

    private HttpURLConnection openURLConnection(URL url) throws IOException {
        return (HttpURLConnection) url.openConnection();
    }

    private void logMessage(String message) {
        logger.trace("{}", message);
    }

    private void logError(String errorMesssage) {
        logger.trace("{}", errorMesssage);
    }
}

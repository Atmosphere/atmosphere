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
package org.atmosphere.ping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.net.URI;

/**
 *  This class ping the http://jfarcand.wordpress.com/ping-atmosphere-{atmosphere.version} page every time Atmosphere is
 *  deployed. All it does is:
 *
 *      GET /jfarcand.wordpress.com/ping-atmosphere-{atmosphere.version}
 *
 * without passing ANY information. To disable, just remove WEB-INF/lib/atmosphere-ping.jar or exclude the jar
 * inside your pom.xml
 * 
 */
public class AtmospherePing {

    private static final Logger logger = LoggerFactory.getLogger(AtmospherePing.class);

    public static void ping(final String version) {
        new Thread(){
            public void run() {
                try {
                    HttpURLConnection urlConnection = (HttpURLConnection)
                            URI.create(String.format("http://jfarcand.wordpress.com/ping-atmosphere-%s", version)).toURL().openConnection();
                    logger.info("AtmospherePing sent for project statistics: {}. " +
                            "Remove WEB-INF/lib/atmosphere-ping.jar to remove that message or exclude the jar in your pom.xml",
                            urlConnection.getResponseCode());
                } catch (Exception e) {
                }
            }
        }.start();
    }
}

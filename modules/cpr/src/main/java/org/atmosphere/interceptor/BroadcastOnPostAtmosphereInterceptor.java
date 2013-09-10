/*
 * Copyright 2013 Jeanfrancois Arcand
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
package org.atmosphere.interceptor;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;

/**
 * This read the request's body and invoke the associated {@link org.atmosphere.cpr.Broadcaster} of an {@link AtmosphereResource}.
 * The broadcast always happens AFTER the request has been delivered to an {@link org.atmosphere.cpr.AtmosphereHandler}
 *
 * @author Jeanfrancois Arcand
 */
public class BroadcastOnPostAtmosphereInterceptor extends AtmosphereInterceptorAdapter {

    private final static Logger logger = LoggerFactory.getLogger(BroadcastOnPostAtmosphereInterceptor.class);

    @Override
    public Action inspect(AtmosphereResource r) {
        return Action.CONTINUE;
    }

    public StringBuilder read(AtmosphereResource r) {
        StringBuilder stringBuilder = new StringBuilder();
        BufferedReader bufferedReader = null;
        try {
            try {
                InputStream inputStream = r.getRequest().getInputStream();
                if (inputStream != null) {
                    bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
                }
            } catch (IllegalStateException ex) {
                logger.trace("",ex);
                Reader reader = r.getRequest().getReader();
                if (reader != null) {
                    bufferedReader = new BufferedReader(reader);
                }
            }

            if (bufferedReader != null) {
                char[] charBuffer = new char[8192];
                int bytesRead = -1;
                while ((bytesRead = bufferedReader.read(charBuffer)) > 0) {
                    stringBuilder.append(charBuffer, 0, bytesRead);
                }
            } else {
                stringBuilder.append("");
            }
        } catch (IOException ex) {
            logger.warn("", ex);
        } finally {
            if (bufferedReader != null) {
                try {
                    bufferedReader.close();
                } catch (IOException ex) {
                    logger.warn("", ex);
                }
            }
        }
        return stringBuilder;
    }

    @Override
    public void postInspect(AtmosphereResource r) {
        if (r.getRequest().getMethod().equalsIgnoreCase("POST")) {

            StringBuilder b = read(r);
            if (b.length() > 0) {
                r.getBroadcaster().broadcast(b.toString());
            } else {
                logger.warn("{} received an empty body", BroadcastOnPostAtmosphereInterceptor.class.getSimpleName());
            }
        }
    }
}

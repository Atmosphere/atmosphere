/*
 * Copyright 2012 Jeanfrancois Arcand
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
import org.atmosphere.cpr.AsyncIOInterceptorAdapter;
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereInterceptorWriter;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResource.TRANSPORT;
import org.atmosphere.cpr.AtmosphereResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * An interceptor that send whitespace every 30 seconds
 *
 * @author Jeanfrancois Arcand
 */
public class HeartbeatInterceptor extends AtmosphereInterceptorAdapter {

    public final static String HEARTBEAT_INTERVAL_IN_SECONDS = HeartbeatInterceptor.class.getName() + ".heartbeatFrequencyInSeconds";

    private static final Logger logger = LoggerFactory.getLogger(HeartbeatInterceptor.class);
    private final ScheduledExecutorService heartBeat = Executors.newSingleThreadScheduledExecutor();
    private static final byte[] padding;
    private static final String paddingText;
    private int heartbeatFrequencyInSeconds = 30;

    static {
        StringBuffer whitespace = new StringBuffer();
        for (int i = 0; i < 8192; i++) {
            whitespace.append(" ");
        }
        whitespace.append("\n");
        paddingText = whitespace.toString();
        padding = paddingText.getBytes();
    }

    @Override
    public void configure(AtmosphereConfig config) {
        String s = config.getInitParameter(HEARTBEAT_INTERVAL_IN_SECONDS);
        if (s != null) {
            heartbeatFrequencyInSeconds = Integer.valueOf(s);
        }
    }

    @Override
    public Action inspect(final AtmosphereResource r) {

        final AtmosphereResponse response = r.getResponse();
        if (r.transport().equals(TRANSPORT.STREAMING)
                || r.transport().equals(TRANSPORT.SSE)
                || r.transport().equals(TRANSPORT.WEBSOCKET)) {

            super.inspect(r);

            AsyncIOWriter writer = response.getAsyncIOWriter();
            if (AtmosphereInterceptorWriter.class.isAssignableFrom(writer.getClass())) {
                AtmosphereInterceptorWriter.class.cast(writer).interceptor(new AsyncIOInterceptorAdapter() {

                    Future<?> writeFuture;

                    @Override
                    public byte[] transformPayload(AtmosphereResponse response, byte[] responseDraft, byte[] data) throws IOException {
                        if (writeFuture != null) {
                            writeFuture.cancel(true);
                        }
                        return responseDraft;
                    }

                    @Override
                    public void postPayload(final AtmosphereResponse response, byte[] data, int offset, int length) {
                        writeFuture = heartBeat.schedule(new Callable<Object>() {
                            @Override
                            public Object call() throws Exception {
                                response.write(paddingText, true);
                                return null;
                            }
                        }, heartbeatFrequencyInSeconds, TimeUnit.SECONDS);
                    }
                });
            }
        }
        return Action.CONTINUE;
    }

    @Override
    public String toString() {
        return "Heartbeat Interceptor Support";
    }
}

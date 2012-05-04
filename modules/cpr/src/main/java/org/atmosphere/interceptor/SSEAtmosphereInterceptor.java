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
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * HTML 5 Server Side Events implementation.
 *
 * @author Jeanfrancois Arcand
 */
public class SSEAtmosphereInterceptor implements AtmosphereInterceptor {

    @Override
    public Action inspect(final AtmosphereResource r) {
        final AtmosphereResponse response = r.getResponse();

        if (r.transport().equals(AtmosphereResource.TRANSPORT.SSE)) {

            String contentType = response.getContentType();
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("utf-8");
            PrintWriter writer = null;
            try {
                writer = response.getWriter();
            } catch (IOException e) {
                //
            }

            for (int i = 0; i < 2000; i++) {
                writer.print(' ');
            }

            writer.print("\n");
            writer.flush();
            response.setContentType(contentType);

            response.asyncIOWriter(new AsyncIOWriter() {
                @Override
                public void redirect(String location) throws IOException {
                    response.sendRedirect(location);
                }

                @Override
                public void writeError(int errorCode, String message) throws IOException {
                    response.sendError(errorCode);
                }

                @Override
                public void write(String data) throws IOException {
                    response.write("data:" + data + "\n\n");
                }

                // TODO: Performance: execute a single write
                @Override
                public void write(byte[] data) throws IOException {
                    response.write("data:").write(data).write("\n\n");
                }

                @Override
                public void write(byte[] data, int offset, int length) throws IOException {
                    response.write("data:").write(data, offset, length).write("\n\n");
                }

                @Override
                public void close() throws IOException {
                    response.closeStreamOrWriter();
                }

                @Override
                public void flush() throws IOException {
                    response.flushBuffer();
                }
            });
        }
        return Action.CONTINUE;
    }

    @Override
    public String toString() {
        return "SSE Interceptor Support";
    }
}

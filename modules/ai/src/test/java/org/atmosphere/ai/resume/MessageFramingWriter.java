/*
 * Copyright 2008-2026 Async-IO.org
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
package org.atmosphere.ai.resume;

import java.io.PrintWriter;
import java.io.Writer;

/**
 * A response writer standing for a transport that delivers each write as one
 * message (a WebSocket, a WebTransport stream, an SSE event): each write is
 * rendered as one line, so a test reads the frames a reattach wrote with
 * {@code split("\n")}. A write that already ends with a newline (a
 * long-polling reply carrying several frames in one write) is kept as it is.
 */
public final class MessageFramingWriter extends PrintWriter {

    public MessageFramingWriter(Writer out) {
        super(out);
    }

    @Override
    public void write(String s) {
        super.write(s.endsWith("\n") ? s : s + "\n");
    }
}

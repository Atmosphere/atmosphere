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
package org.atmosphere.ai.llm;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * A response body that reports each read as activity, so a stream idle bound
 * ({@link org.atmosphere.ai.StreamIdleWatchdog}) counts every byte the
 * provider sends: keep-alive comments, {@code ping} events and reasoning
 * chunks included, not only the text that reaches the session.
 *
 * <p>Every read that returns zero or more bytes (not end of stream) runs the
 * activity callback once, after the bytes are read. The bulk reads
 * {@link InputStream} builds on {@link #read(byte[], int, int)}
 * ({@code readAllBytes}, {@code readNBytes}, {@code transferTo}) report too.
 * {@link #close()} closes the wrapped body, so this stream is itself the
 * target to {@link CancellableExchange#registerBody registerBody} for an abort.</p>
 */
public final class ActivityInputStream extends FilterInputStream {

    private final Runnable onActivity;

    /**
     * @param body       the response body
     * @param onActivity called after each read that is not end of stream
     */
    public ActivityInputStream(InputStream body, Runnable onActivity) {
        super(Objects.requireNonNull(body, "body"));
        this.onActivity = Objects.requireNonNull(onActivity, "onActivity");
    }

    @Override
    public int read() throws IOException {
        var b = in.read();
        if (b >= 0) {
            onActivity.run();
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        var n = in.read(b, off, len);
        if (n >= 0) {
            onActivity.run();
        }
        return n;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}

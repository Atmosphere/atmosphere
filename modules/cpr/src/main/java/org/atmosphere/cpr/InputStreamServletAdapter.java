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
package org.atmosphere.cpr;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

import java.io.InputStream;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A {@link ServletInputStream} adapter that wraps a standard {@link InputStream}.
 * <p>
 * This class was originally the inner class {@code IS} in {@link AtmosphereRequestImpl}.
 *
 * @author Jeanfrancois Arcand
 */
final class InputStreamServletAdapter extends ServletInputStream {

    private final InputStream innerStream;
    private final ReentrantLock markLock = new ReentrantLock();

    InputStreamServletAdapter(InputStream innerStream) {
        super();
        this.innerStream = innerStream;
    }

    @Override
    public int read() throws java.io.IOException {
        return innerStream.read();
    }

    @Override
    public int read(byte[] bytes) throws java.io.IOException {
        return innerStream.read(bytes);
    }

    @Override
    public int read(byte[] bytes, int i, int i1) throws java.io.IOException {
        return innerStream.read(bytes, i, i1);
    }

    @Override
    public long skip(long l) throws java.io.IOException {
        return innerStream.skip(l);
    }

    @Override
    public int available() throws java.io.IOException {
        return innerStream.available();
    }

    @Override
    public void close() throws java.io.IOException {
        innerStream.close();
    }

    @Override
    public void mark(int i) {
        markLock.lock();
        try {
            innerStream.mark(i);
        } finally {
            markLock.unlock();
        }
    }

    @Override
    public void reset() throws java.io.IOException {
        markLock.lock();
        try {
            innerStream.reset();
        } finally {
            markLock.unlock();
        }
    }

    @Override
    public boolean markSupported() {
        return innerStream.markSupported();
    }

    @Override
    public boolean isFinished() {
        return false;
    }

    @Override
    public boolean isReady() {
        return false;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
    }
}

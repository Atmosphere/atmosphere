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

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * A {@link ServletInputStream} backed by a byte array.
 * <p>
 * This class was originally the inner class {@code ByteInputStream} in {@link AtmosphereRequestImpl}.
 *
 * @author Jeanfrancois Arcand
 */
final class ByteArrayServletInputStream extends ServletInputStream {

    private final ByteArrayInputStream bis;

    ByteArrayServletInputStream(byte[] data, int offset, int length) {
        this.bis = new ByteArrayInputStream(data, offset, length);
    }

    @Override
    public int read() throws IOException {
        return bis.read();
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

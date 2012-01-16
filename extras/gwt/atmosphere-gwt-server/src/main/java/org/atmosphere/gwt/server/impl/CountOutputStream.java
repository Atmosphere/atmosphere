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
/*
 * Copyright 2009 Richard Zschech.
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

import java.io.IOException;
import java.io.OutputStream;

public class CountOutputStream extends OutputStream {

    private int count;
    private final OutputStream out;
    private boolean ignoreFlush;

    public CountOutputStream(OutputStream out) {
        this.out = out;
    }

    public int getCount() {
        return count;
    }

    public void setIgnoreFlush(boolean ignoreFlush) {
        this.ignoreFlush = ignoreFlush;
    }

    public boolean isIgnoreFlush() {
        return ignoreFlush;
    }

    @Override
    public void write(int b) throws IOException {
        out.write(b);
        count++;
    }

    @Override
    public void write(byte[] b) throws IOException {
        out.write(b);
        count += b.length;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        count += len;
    }

    @Override
    public void flush() throws IOException {
        if (!ignoreFlush) {
            out.flush();
        }
    }
}

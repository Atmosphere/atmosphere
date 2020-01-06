/*
 * Copyright 2008-2020 Async-IO.org
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
package org.atmosphere.util;

import java.io.IOException;
import java.io.Reader;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates a signle reader instance from a sequence of readers.
 * @author elakito
 */

public class ChunkConcatReaderPool {
    private Map<String, ChunkConcatReader> readersPool = new ConcurrentHashMap<String, ChunkConcatReader>();
    private static final long DEFAULT_TIMEOUT = 300000;
    private long timeout = DEFAULT_TIMEOUT;

    /**
     * Return a reader if the reader specified by the key has not been previously created.
     * If the reader has been created, the content is added to that reader and returns null.
     *
     * @param key
     * @param chunk
     * @param continued
     * @return
     */
    public void addChunk(String key, Reader chunk, boolean continued) throws IllegalArgumentException {
        ChunkConcatReader reader = readersPool.get(key);
        // assume there is no concurrent request for the same key
        if (reader == null) {
            throw new IllegalArgumentException("No reader with key: " + key);
        }
        reader.addChunk(chunk, continued);
    }

    /**
     * Returns the specified reader. If the reader is absent, returns null. If create is set to true, a new reader is created.
     * 
     * @param key
     * @param create
     * @return
     */
    public Reader getReader(String key, boolean create) {
        ChunkConcatReader reader = readersPool.get(key);
        // assume there is no concurrent request for the same key
        if (create && reader == null) {
            reader = new ChunkConcatReader(key);
            readersPool.put(key, reader);
        }
        return reader;
    }

    public long getTimeout() {
        return timeout;
    }

    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    void releaseReader(String key) {
        readersPool.remove(key);
    }

    Map<String, ChunkConcatReader> getReadersPool() {
        return readersPool;
    }

    class ChunkConcatReader extends Reader {
        private String key;
        private Deque<Reader> readers = new LinkedList<Reader>();
        private boolean continued;
        private boolean closed;

        public ChunkConcatReader(String key) {
            this.key = key;
        }

        @Override
        public int read(char[] cbuf, int off, int len) throws IOException {
            // assume only one thread is reading at time while other threads may add a reader at any time
            int count = 0;
            while (!closed && count < len) {
                if (readers.isEmpty()) {
                    if (continued) {
                        // if some data has been read, return it
                        if (count > 0) {
                            break;
                        }
                        synchronized (readers) {
                            if (!readers.isEmpty()) {
                                continue;
                            }
                            // if no data has been read and no data is available, wait for new data
                            try {
                                readers.wait(timeout);
                            } catch (InterruptedException e) {
                                //ignore
                            }
                            if (readers.isEmpty()) {
                                throw new IOException("Read timeout");
                            }
                            continue;
                        }
                    }
                    break;
                } else {
                    Reader reader = readers.getFirst();
                    int c = reader.read(cbuf, off + count, len - count);
                    if (c == -1) {
                        synchronized (readers) {
                            readers.removeFirst();
                        }
                        try {
                            reader.close();
                        } catch (IOException e) {
                            //ignore
                        }
                        continue;
                    }
                    count += c;
                }
            }
            return count == 0 ? -1 : count;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }

            // close all sub-readers associated with this reader before releasing the reader
            for (Reader r : readers) {
                try {
                    r.close();
                } catch (IOException e) {
                    //ignore
                }
            }
            releaseReader(key);
            closed = true;
            // release any blocked waiting threads
            synchronized (readers) {
                readers.notifyAll();
            }
        }

        @Override
        public boolean ready() throws IOException {
            // assuming no other thread is concurrently reading
            if (readers.isEmpty()) {
                return false;
            }
            return readers.getFirst().ready();
        }

        void addChunk(Reader chunk, boolean continued) {
            synchronized (readers) {
                readers.addLast(chunk);
                readers.notifyAll();
                this.continued = continued;
            }
        }
    }
}

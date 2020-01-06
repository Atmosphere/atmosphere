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

import org.testng.annotations.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;

import static org.testng.Assert.*;

public class ChunkConcatReaderPoolTest {
    static final String[] TEST_MESSAGES = {"This is the first line.", "And this is the second line.", "Finally, this is the last line."};
    static final String TEST_MESSAGES_CONCATENATED;
    static {
        StringBuilder sb = new StringBuilder();
        for (String s : TEST_MESSAGES) {
            sb.append(s);
        }
        TEST_MESSAGES_CONCATENATED = sb.toString();
    }

    @Test
    public void testReassembleChunkedMessagesUsingSmallBuffer() throws Exception {
        performReaasmbleChunkedMessges(5);
    }

    @Test
    public void testReassembleChunkedMessagesUsingBigBuffer() throws Exception {
        performReaasmbleChunkedMessges(512);
    }

   @Test
    public void testReassembleDelayedChunkedMessagesUsingSmallBuffer() throws Exception {
        performtestReassembleDelayedChunkedMessages(5);
    }

    @Test
    public void testReassembleDelayedChunkedMessagesUsingLargeBuffer() throws Exception {
        performtestReassembleDelayedChunkedMessages(512);
    }

    @Test
    public void testIncorementalNonBlockingRead() throws Exception {
        final ChunkConcatReaderPool pool = new ChunkConcatReaderPool();
        Reader reader = pool.getReader("123", true);
        assertNotNull(reader);
        pool.addChunk("123", new StringReader(TEST_MESSAGES[0]), true);
        assertTrue(reader.ready());
        String s = readOnlyAvailable(reader);
        assertEquals(s, TEST_MESSAGES[0]);
        assertFalse(reader.ready());
        for (int i = 1; i < TEST_MESSAGES.length; i++) {
            pool.addChunk("123", new StringReader(TEST_MESSAGES[i]), i < TEST_MESSAGES.length - 1);
            assertTrue(reader.ready());
            s = readOnlyAvailable(reader);
            assertEquals(s, TEST_MESSAGES[i]);
            assertFalse(reader.ready());
        }
    }

    @Test
    public void testInterruptReadWithTimeout() throws Exception {
        final ChunkConcatReaderPool pool = new ChunkConcatReaderPool();
        pool.setTimeout(100);
        Reader reader = pool.getReader("123", true);
        pool.addChunk("123", new StringReader(TEST_MESSAGES[0]), true);
        assertNotNull(reader);
        try {
            String s = readAll(reader, 512, false);
            fail("IOException expected");
        } catch (IOException e) {
            // ignore
        }
        reader.close();
        assertNull(pool.getReader("123", false));
    }

    private void performReaasmbleChunkedMessges(int limit) throws Exception {
        final ChunkConcatReaderPool pool = new ChunkConcatReaderPool();
        Reader reader = pool.getReader("123", true);
        pool.addChunk("123", new StringReader(TEST_MESSAGES[0]), true);
        assertNotNull(reader);
        for (int i = 1; i < TEST_MESSAGES.length; i++) {
            pool.addChunk("123", new StringReader(TEST_MESSAGES[i]), i < TEST_MESSAGES.length - 1);
        }
        String data = readAll(reader, limit, TEST_MESSAGES_CONCATENATED.length() < limit);
        assertEquals(data, TEST_MESSAGES_CONCATENATED);
    }

    private void performtestReassembleDelayedChunkedMessages(int limit) throws Exception {
        final ChunkConcatReaderPool pool = new ChunkConcatReaderPool();
        Reader reader = pool.getReader("123", true);
        pool.addChunk("123", new StringReader(TEST_MESSAGES[0]), true);
        assertNotNull(reader);
        new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    //ignore
                }
                for (int i = 1; i < TEST_MESSAGES.length; i++) {
                    pool.addChunk("123", new StringReader(TEST_MESSAGES[i]), i < TEST_MESSAGES.length - 1);
                }
            }
        }).start();
        String data = readAll(reader, 5, false);
        assertEquals(TEST_MESSAGES_CONCATENATED, data);
    }

    private static String readAll(Reader reader, int limit, boolean once) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[limit];
        for (;;) {
            int c = reader.read(buf, 0, buf.length);
            if (c == -1) {
                break;
            }
            sb.append(buf, 0, c);
            if (once) {
                break;
            }
        }
        return sb.toString();
    }

    private static String readOnlyAvailable(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[512];
        while (reader.ready()) {
            try {
                int c = reader.read(buf, 0, buf.length);
                assertNotEquals(c, -1);
                sb.append(buf, 0, c);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return sb.toString();
    }
}

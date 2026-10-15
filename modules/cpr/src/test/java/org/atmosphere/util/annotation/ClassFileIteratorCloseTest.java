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
package org.atmosphere.util.annotation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An annotation scan stopped before its end (a processor failing the
 * framework's initialization) releases what the iteration still holds: the
 * JAR being read, and the streams not returned yet.
 */
class ClassFileIteratorCloseTest {

    @Test
    void closingStopsReadingTheOpenJar(@TempDir Path dir) throws IOException {
        var jar = dir.resolve("classes.jar").toFile();
        try (var out = new ZipOutputStream(new FileOutputStream(jar))) {
            for (var name : new String[]{"a/First.class", "a/Second.class"}) {
                out.putNextEntry(new ZipEntry(name));
                out.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
                out.closeEntry();
            }
        }
        var iterator = new ClassFileIterator(new File[]{jar}, null);
        try (InputStream first = iterator.next()) {
            assertNotNull(first, "the first entry opens the jar");
        }

        iterator.close();

        assertNull(iterator.next(), "the jar was closed: its second entry is not read");
    }

    @Test
    void closingClosesTheStreamsNotReturnedYet() throws IOException {
        var returned = new TrackedStream();
        var pending = new TrackedStream();
        var iterator = new ClassFileIterator(new InputStream[]{returned, pending}, null);
        assertNotNull(iterator.next());

        iterator.close();

        assertTrue(pending.closed, "the stream not returned yet is closed");
        assertFalse(returned.closed, "the stream returned is the caller's to close");
        assertNull(iterator.next());
    }

    private static final class TrackedStream extends ByteArrayInputStream {
        private boolean closed;

        TrackedStream() {
            super(new byte[]{1});
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}

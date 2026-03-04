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
package org.atmosphere.ai;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link Content} sealed interface hierarchy.
 */
public class ContentTest {

    @Test
    public void testTextContent() {
        var content = Content.text("Hello world");
        assertInstanceOf(Content.Text.class, content);
        assertEquals("Hello world", ((Content.Text) content).text());
    }

    @Test
    public void testTextContentNullThrows() {
        assertThrows(IllegalArgumentException.class, () -> Content.text(null));
    }

    @Test
    public void testImageContent() {
        var data = "PNG data".getBytes(StandardCharsets.UTF_8);
        var content = Content.image(data, "image/png");
        assertInstanceOf(Content.Image.class, content);
        var image = (Content.Image) content;
        assertArrayEquals(data, image.data());
        assertEquals("image/png", image.mimeType());
    }

    @Test
    public void testImageBase64Encoding() {
        var data = "test image".getBytes(StandardCharsets.UTF_8);
        var image = new Content.Image(data, "image/jpeg");
        var expected = Base64.getEncoder().encodeToString(data);
        assertEquals(expected, image.dataBase64());
    }

    @Test
    public void testImageNullDataThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> Content.image(null, "image/png"));
    }

    @Test
    public void testImageEmptyDataThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> Content.image(new byte[0], "image/png"));
    }

    @Test
    public void testImageNullMimeTypeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> Content.image(new byte[]{1}, null));
    }

    @Test
    public void testFileContent() {
        var data = "CSV,data".getBytes(StandardCharsets.UTF_8);
        var content = Content.file(data, "text/csv", "report.csv");
        assertInstanceOf(Content.File.class, content);
        var file = (Content.File) content;
        assertEquals("text/csv", file.mimeType());
        assertEquals("report.csv", file.fileName());
    }

    @Test
    public void testFileBase64Encoding() {
        var data = "file content".getBytes(StandardCharsets.UTF_8);
        var file = new Content.File(data, "text/plain", "test.txt");
        var expected = Base64.getEncoder().encodeToString(data);
        assertEquals(expected, file.dataBase64());
    }

    @Test
    public void testFileNullFileNameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> Content.file(new byte[]{1}, "text/csv", null));
    }

    @Test
    public void testPatternMatchingOnContent() {
        Content content = Content.text("hello");
        var result = switch (content) {
            case Content.Text t -> "text: " + t.text();
            case Content.Image i -> "image: " + i.mimeType();
            case Content.File f -> "file: " + f.fileName();
        };
        assertEquals("text: hello", result);
    }
}

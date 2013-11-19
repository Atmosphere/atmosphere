package org.atmosphere.util.annotation;/*
 * Copyright 2013 Jeanfrancois Arcand
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

import java.io.IOException;
import java.io.InputStream;
import java.util.Deque;
import java.util.LinkedList;

/**
 * {@code FileIterator} enables iteration over all files in a directory and all
 * its sub directories.
 * <br/>
 * Usage:
 * <pre>
 * FileIterator iter = new FileIterator(new File("./src"));
 * File f;
 * while ((f = iter.next()) != null) {
 *     // do something with f
 *     assert f == iter.getCurrent();
 * }
 * </pre>
 *
 * @author <a href="mailto:rmuller@xiam.nl">Ronald K. Muller</a>
 * @since annotation-detector 3.0.0
 */
public final class InputStreamIterator {

    private final Deque<InputStream> stack = new LinkedList<InputStream>();
    private int rootCount;
    private InputStream current;

    /**
     * Create a new {@code FileIterator} using the specified 'filesOrDirectories' as root.
     * <br/>
     * If 'filesOrDirectories' contains a file, the iterator just returns that single file.
     * If 'filesOrDirectories' contains a directory, all files in that directory
     * and its sub directories are returned (depth first).
     *
     * @param filesOrDirectories Zero or more {@link java.io.File} objects, which are iterated
     * in the specified order (depth first)
     */
    public InputStreamIterator(final InputStream... filesOrDirectories) {
        addReverse(filesOrDirectories);
        rootCount = stack.size();
    }

    /**
     * Return the next {@link java.io.File} object or {@code null} if no more files are
     * available.
     *
     */
    public InputStream next() throws IOException {
        if (stack.isEmpty()) {
            current = null;
            return null;
        } else {
            current = stack.removeLast();
            return current;
        }
    }

    /**
     * Add the specified files in reverse order.
     */
    private void addReverse(final InputStream[] files) {
        for (int i = files.length - 1; i >=0; --i) {
            stack.add(files[i]);
        }
    }

}


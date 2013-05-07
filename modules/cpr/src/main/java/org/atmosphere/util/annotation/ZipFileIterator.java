/*
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
/* ZipFileIterator.java
 * 
 ******************************************************************************
 *
 * Created: Oct 10, 2011
 * Character encoding: UTF-8
 * 
 * Copyright (c) 2011 - XIAM Solutions B.V. The Netherlands, http://www.xiam.nl
 * 
 ********************************* LICENSE ************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.atmosphere.util.annotation;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * {@code ZipFileIterator} is used to iterate over all entries in a {@code zip}
 * or {@code jar} file and returning the {@link java.io.InputStream} of these entries.
 * The most efficient way of iterating is used, see benchmark in test classes.
 *
 * @author <a href="mailto:rmuller@xiam.nl">Ronald K. Muller</a>
 * @since annotation-detector 3.0.0
 */
final class ZipFileIterator {
    
    private final ZipFile zipFile;
    private final Enumeration<? extends ZipEntry> entries;
    private ZipEntry current;
    
    ZipFileIterator(final File file) throws IOException {
        zipFile = new ZipFile(file);
        entries = zipFile.entries();
    }
    
    public ZipEntry getEntry() {
        return current;
    }
    
    public InputStream next() throws IOException {
        while (entries.hasMoreElements()) {
            current = entries.nextElement();
            if (!current.isDirectory()) {
                return zipFile.getInputStream(current);
            }
        }
        // no more entries in this ZipFile, so close ZipFile
        try {
            // zipFile is never null here
            zipFile.close();
        } catch (IOException ex) {
            // suppress IOException, otherwise close() is called twice
        }
        return null;
    }
    
}

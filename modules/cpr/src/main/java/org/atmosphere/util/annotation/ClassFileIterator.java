/*
 * Copyright 2015 Async-IO.org
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
/* ClassFileIterator.java
 *
 * Created: 2011-10-10 (Year-Month-Day)
 * Character encoding: UTF-8
 *
 ****************************************** LICENSE *******************************************
 *
 * Copyright (c) 2011 - 2013 XIAM Solutions B.V. (http://www.xiam.nl)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.atmosphere.util.annotation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipFile;

/**
 * {@code ClassFileIterator} is used to iterate over all Java ClassFile files available within
 * a specific context.
 * <p/>
 * For every Java ClassFile ({@code .class}) an {@link InputStream} is returned.
 *
 * @author <a href="mailto:rmuller@xiam.nl">Ronald K. Muller</a>
 * @since annotation-detector 3.0.0
 */
final class ClassFileIterator {
    private final Logger logger = LoggerFactory.getLogger(AnnotationDetector.class);

    private final FileIterator fileIterator;
    private final String[] pkgNameFilter;
    private ZipFileIterator zipIterator;
    private boolean isFile;
    private final InputStreamIterator inputStreamIterator;

    /**
     * Create a new {@code ClassFileIterator} returning all Java ClassFile files available
     * from the class path ({@code System.getProperty("java.class.path")}).
     */
    ClassFileIterator() throws IOException {
        this(classPath(), null);
    }

    /**
     * Create a new {@code ClassFileIterator} returning all Java ClassFile files available
     * from the specified files and/or directories, including sub directories.
     * <p/>
     * If the (optional) package filter is defined, only class files staring with one of the
     * defined package names are returned.
     * NOTE: package names must be defined in the native format (using '/' instead of '.').
     */
    ClassFileIterator(final File[] filesOrDirectories, final String[] pkgNameFilter)
            throws IOException {

        this.fileIterator = new FileIterator(filesOrDirectories);
        this.pkgNameFilter = pkgNameFilter;
        this.inputStreamIterator = null;
    }

    /**
     * Create a new {@code ClassFileIterator} returning all Java ClassFile files available
     * from the specified files and/or directories, including sub directories.
     * <p/>
     * If the (optional) package filter is defined, only class files staring with one of the
     * defined package names are returned.
     * NOTE: package names must be defined in the native format (using '/' instead of '.').
     */
    ClassFileIterator(final InputStream[] filesOrDirectories, final String[] pkgNameFilter)
            throws IOException {

        this.fileIterator = null;
        this.pkgNameFilter = pkgNameFilter;
        this.inputStreamIterator = new InputStreamIterator(filesOrDirectories);
    }

    /**
     * Return the name of the Java ClassFile returned from the last call to {@link #next()}.
     * The name is either the path name of a file or the name of an ZIP/JAR file entry.
     */
    public String getName() {
        // Both getPath() and getName() are very light weight method calls
        return zipIterator == null ?
                fileIterator.getFile().getPath() :
                zipIterator.getEntry().getName();
    }

    /**
     * Return {@code true} if the current {@link InputStream} is reading from a plain
     * {@link File}. Return {@code false} if the current {@link InputStream} is reading from a
     * ZIP File Entry.
     */
    public boolean isFile() {
        return isFile;
    }

    /**
     * Return the next Java ClassFile as an {@code InputStream}.
     * <p/>
     * NOTICE: Client code MUST close the returned {@code InputStream}!
     */
    public InputStream next() throws IOException {
        try {
            while (true) {
                if (fileIterator != null) {
                    if (zipIterator == null) {
                        final File file = fileIterator.next();
                        if (file == null) {
                            return null;
                        } else {
                            final String name = file.getName();
                            if (name.endsWith(".class")) {
                                isFile = true;
                                return new FileInputStream(file);
                            } else if (fileIterator.isRootFile() && (endsWithIgnoreCase(name, ".jar") || isZipFile(file)) && file.exists()) {
                                try {
                                    zipIterator = new ZipFileIterator(new ZipFile(file), pkgNameFilter);
                                } catch (Exception ex) {
                                    logger.debug("Unable to construct file {}", file);
                                    return null;
                                }
                            } // else just ignore
                        }
                    } else {
                        final InputStream is = zipIterator.next();
                        if (is == null) {
                            zipIterator = null;
                        } else {
                            isFile = false;
                            return is;
                        }
                    }
                } else {
                    return inputStreamIterator.next();
                }
            }
        } catch (Exception ex) {
            logger.error("Unable to scan classes", ex);
            return null;
        }
    }

    // private

    private boolean isZipFile(File file) throws IOException {
        DataInputStream in = null;
        try {
            in = new DataInputStream(new FileInputStream(file));
            int n = in.readInt();
            in.close();
            return n == 0x504b0304;
        } catch (Exception ex) {
            if (in != null) in.close();
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ex) {
                    // ignore
                }
            }
        }
    }

    /**
     * Returns the class path of the current JVM instance as an array of {@link File} objects.
     */
    private static File[] classPath() {
        final String[] fileNames = System.getProperty("java.class.path")
                .split(File.pathSeparator);
        final File[] files = new File[fileNames.length];
        for (int i = 0; i < files.length; ++i) {
            files[i] = new File(fileNames[i]);
        }
        return files;
    }

    private static boolean endsWithIgnoreCase(final String value, final String suffix) {
        final int n = suffix.length();
        return value.regionMatches(true, value.length() - n, suffix, 0, n);
    }

}
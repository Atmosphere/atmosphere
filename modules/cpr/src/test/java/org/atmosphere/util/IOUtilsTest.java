/*
 * Copyright 2017 Async-IO.org
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

import static org.testng.Assert.assertEquals;

/**
 * Created by Romain on 17/03/14.
 */
public class IOUtilsTest {

    @Test
    public void testGetCleanedServletPath() {
        String testFullPath;
        String testCleanedPath;

        testFullPath = "/foo/bar/*";
        testCleanedPath = IOUtils.getCleanedServletPath(testFullPath);
        assertEquals(testCleanedPath, "/foo/bar");

        testFullPath = "foo/bar/**/*";
        testCleanedPath = IOUtils.getCleanedServletPath(testFullPath);
        assertEquals(testCleanedPath, "/foo/bar/**");

        testFullPath = "/com.zyxabc.abc.Abc/gwtCometEvent*";
        testCleanedPath = IOUtils.getCleanedServletPath(testFullPath);
        assertEquals(testCleanedPath, "/com.zyxabc.abc.Abc/gwtCometEvent");
    }

}

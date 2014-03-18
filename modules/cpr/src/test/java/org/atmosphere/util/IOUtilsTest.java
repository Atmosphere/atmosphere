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

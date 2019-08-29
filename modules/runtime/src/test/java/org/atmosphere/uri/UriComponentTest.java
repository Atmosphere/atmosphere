package org.atmosphere.util.uri;


import com.diffblue.deeptestutils.Reflector;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UriComponentTest {

    @Rule
    public final ExpectedException thrown = ExpectedException.none();

    @Test
    public void creatingEncodingTableInput0Output128()
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        final ArrayList<String> allowed = new ArrayList<String>();
        final Class<?> classUnderTest = Reflector.forName("org.atmosphere.util.uri.UriComponent");
        final Method methodUnderTest = classUnderTest.getDeclaredMethod(
                "creatingEncodingTable", Reflector.forName("java.util.List"));
        methodUnderTest.setAccessible(true);
        final boolean[] actual = (boolean[]) methodUnderTest.invoke(null, allowed);
        Assert.assertArrayEquals(
                new boolean[]{false, false, false, false, false, false, false, false, false, false, false,
                        false, false, false, false, false, false, false, false, false, false, false,
                        false, false, false, false, false, false, false, false, false, false, false,
                        false, false, false, false, false, false, false, false, false, false, false,
                        false, false, false, false, false, false, false, false, false, false, false,
                        false, false, false, false, false, false, false, false, false, false, false,
                        false, false, false, false, false, false, false, false, false, false, false,
                        false, false, false, false, false, false, false, false, false, false, false,
                        false, false, false, false, false, false, false, false, false, false, false,
                        false, false, false, false, false, false, false, false, false, false, false,
                        false, false, false, false, false, false, false, false, false, false, false,
                        false, false, false, false, false, false, false},
                actual);
    }

    @Test
    public void decodeHexInputNotNullPositiveOutputStringIndexOutOfBoundsException()
            throws Throwable {
        final Class<?> classUnderTest = Reflector.forName("org.atmosphere.util.uri.UriComponent");
        final Method methodUnderTest = classUnderTest.getDeclaredMethod(
                "decodeHex", Reflector.forName("java.lang.String"), Reflector.forName("int"));
        methodUnderTest.setAccessible(true);
        try {
            thrown.expect(StringIndexOutOfBoundsException.class);
            methodUnderTest.invoke(null, "foo", 261);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }

    @Test
    public void decodeInputNotNullNotNullOutputIllegalArgumentException() {
        thrown.expect(IllegalArgumentException.class);
        UriComponent.decode("%", UriComponent.Type.HOST);
    }

    @Test
    public void decodeInputNotNullNotNullOutputIllegalArgumentException2() {
        thrown.expect(IllegalArgumentException.class);
        UriComponent.decode("%?", UriComponent.Type.HOST);
    }

    @Test
    public void decodeInputNotNullNotNullOutputNotNull() {
        Assert.assertEquals("foo",
                UriComponent.decode("foo", UriComponent.Type.QUERY_PARAM));
    }

    @Test
    public void decodeInputNotNullNotNullOutputNotNull2() {
        Assert.assertEquals("2",
                UriComponent.decode("2", UriComponent.Type.FRAGMENT));
    }

    @Test
    public void decodeInputNotNullNotNullOutputNotNull3() {
        Assert.assertEquals("",
                UriComponent.decode("", UriComponent.Type.HOST));
    }

    @Test
    public void decodeMatrixInputNotNullFalseOutput0() {
        Assert.assertEquals(new HashMap<String, String>(),
                UriComponent.decodeMatrix("3", false));
    }

    @Test
    public void decodeMatrixInputNotNullFalseOutput02() {
        final String pathSegment =
                ":::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::;;;";
        final Map<String, String> actual = UriComponent.decodeMatrix(pathSegment, false);
        final HashMap<String, String> hashMap = new HashMap<String, String>();
        Assert.assertEquals(hashMap, actual);
    }

    @Test
    public void decodePathInputNotNullFalseOutput1() {
        Assert.assertNotNull(UriComponent.decodePath("", false));
        Assert.assertEquals(1, UriComponent.decodePath("", false).size());
        Assert.assertNotNull(((List<?>) UriComponent.decodePath("", false)).get(0));
        Assert.assertEquals(new HashMap<String, String>(),
                Reflector.getInstanceField(((List<?>) UriComponent.decodePath("", false)).get(0),
                        "matrixParameters"));
        Assert.assertEquals("", Reflector.getInstanceField(((List<?>) UriComponent.decodePath("", false)).get(0),
                "path"));
    }

    @Test
    public void decodeQueryInputNotNullFalseOutput0() {
        Assert.assertEquals(new HashMap<String, String>(),
                UriComponent.decodeQuery("", false));
    }

    @Test
    public void decodeQueryInputNotNullFalseOutput02() {
        final boolean decode = false;
        Assert.assertEquals(new HashMap<String, String>(),
                UriComponent.decodeQuery("&&", decode));
    }

    @Test
    public void encodeTemplateNamesInputNotNullOutputNotNull() {
        Assert.assertEquals("foo", UriComponent.encodeTemplateNames("foo"));
    }

    @Test
    public void getMatrixParametersOutputNull()
            throws NoSuchMethodException, IllegalAccessException,
            InvocationTargetException {
        final Object uriComponentPathSegmentImpl =
                Reflector.getInstance("org.atmosphere.util.uri.UriComponent$PathSegmentImpl");
        Reflector.setField(uriComponentPathSegmentImpl, "matrixParameters", null);
        Reflector.setField(uriComponentPathSegmentImpl, "path", null);
        final Class<?> classUnderTest =
                Reflector.forName("org.atmosphere.util.uri.UriComponent$PathSegmentImpl");
        final Method methodUnderTest = classUnderTest.getDeclaredMethod("getMatrixParameters");
        methodUnderTest.setAccessible(true);
        Assert.assertNull(methodUnderTest.invoke(uriComponentPathSegmentImpl));
    }

    @Test
    public void getPathOutputNull() throws NoSuchMethodException, IllegalAccessException,
            InvocationTargetException {
        final Object uriComponentPathSegmentImpl =
                Reflector.getInstance("org.atmosphere.util.uri.UriComponent$PathSegmentImpl");
        Reflector.setField(uriComponentPathSegmentImpl, "matrixParameters", null);
        Reflector.setField(uriComponentPathSegmentImpl, "path", null);
        final Class<?> classUnderTest =
                Reflector.forName("org.atmosphere.util.uri.UriComponent$PathSegmentImpl");
        final Method methodUnderTest = classUnderTest.getDeclaredMethod("getPath");
        methodUnderTest.setAccessible(true);
        Assert.assertNull(methodUnderTest.invoke(uriComponentPathSegmentImpl));
    }

    @Test
    public void valueOfInputNullOutputNullPointerException() {
        thrown.expect(NullPointerException.class);
        UriComponent.Type.valueOf(null);
    }
}

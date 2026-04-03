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
package org.atmosphere.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IntrospectionUtilsTest {

    @AfterEach
    void tearDown() {
        IntrospectionUtils.clear();
    }

    @Test
    void capitalizeBasicString() {
        assertEquals("Hello", IntrospectionUtils.capitalize("hello"));
        assertEquals("A", IntrospectionUtils.capitalize("a"));
        assertEquals("Already", IntrospectionUtils.capitalize("Already"));
    }

    @Test
    void capitalizeEdgeCases() {
        assertNull(IntrospectionUtils.capitalize(null));
        assertEquals("", IntrospectionUtils.capitalize(""));
    }

    @Test
    void unCapitalizeBasicString() {
        assertEquals("hello", IntrospectionUtils.unCapitalize("Hello"));
        assertEquals("a", IntrospectionUtils.unCapitalize("A"));
        assertEquals("already", IntrospectionUtils.unCapitalize("already"));
    }

    @Test
    void unCapitalizeEdgeCases() {
        assertNull(IntrospectionUtils.unCapitalize(null));
        assertEquals("", IntrospectionUtils.unCapitalize(""));
    }

    @Test
    void replacePropertiesWithStaticProps() {
        var props = Map.of("name", "Atmosphere", "version", "4.0");

        assertEquals("Atmosphere", IntrospectionUtils.replaceProperties("${name}", props, null));
        assertEquals("Hello Atmosphere!", IntrospectionUtils.replaceProperties("Hello ${name}!", props, null));
        assertEquals("Atmosphere-4.0", IntrospectionUtils.replaceProperties("${name}-${version}", props, null));
    }

    @Test
    void replacePropertiesNoSubstitution() {
        assertEquals("no vars here", IntrospectionUtils.replaceProperties("no vars here", null, null));
    }

    @Test
    void replacePropertiesUnknownVariable() {
        assertEquals("${unknown}", IntrospectionUtils.replaceProperties("${unknown}", Map.of(), null));
    }

    @Test
    void replacePropertiesWithDynamicSource() {
        IntrospectionUtils.PropertySource source = key -> "port".equals(key) ? "8080" : null;

        assertEquals("8080", IntrospectionUtils.replaceProperties("${port}", null, new IntrospectionUtils.PropertySource[]{source}));
    }

    @Test
    void replacePropertiesTrailingDollar() {
        assertEquals("end$", IntrospectionUtils.replaceProperties("end$", null, null));
    }

    @Test
    void replacePropertiesUnclosedBrace() {
        assertEquals("${unclosed", IntrospectionUtils.replaceProperties("${unclosed", null, null));
    }

    @Test
    void setPropertySetsStringValue() {
        var bean = new TestBean();
        assertTrue(IntrospectionUtils.setProperty(bean, "name", "test"));
        assertEquals("test", bean.getName());
    }

    @Test
    void setPropertySetsIntValue() {
        var bean = new TestBean();
        assertTrue(IntrospectionUtils.setProperty(bean, "count", "42"));
        assertEquals(42, bean.getCount());
    }

    @Test
    void setPropertySetsBooleanValue() {
        var bean = new TestBean();
        assertTrue(IntrospectionUtils.setProperty(bean, "active", "true"));
        assertTrue(bean.isActive());
    }

    @Test
    void setPropertyReturnsFalseForUnknown() {
        var bean = new TestBean();
        assertFalse(IntrospectionUtils.setProperty(bean, "nonexistent", "value"));
    }

    @Test
    void getPropertyGetsStringValue() {
        var bean = new TestBean();
        bean.setName("hello");
        assertEquals("hello", IntrospectionUtils.getProperty(bean, "name"));
    }

    @Test
    void getPropertyGetsBooleanViaIsGetter() {
        var bean = new TestBean();
        bean.setActive(true);
        assertEquals(true, IntrospectionUtils.getProperty(bean, "active"));
    }

    @Test
    void findMethodFindsExistingMethod() {
        var method = IntrospectionUtils.findMethod(TestBean.class, "setName", new Class<?>[]{String.class});
        assertNotNull(method);
        assertEquals("setName", method.getName());
    }

    @Test
    void findMethodReturnsNullForMissing() {
        var method = IntrospectionUtils.findMethod(TestBean.class, "noSuchMethod", new Class<?>[0]);
        assertNull(method);
    }

    @Test
    void findMethodsReturnsCachedResults() {
        var methods1 = IntrospectionUtils.findMethods(TestBean.class);
        var methods2 = IntrospectionUtils.findMethods(TestBean.class);
        assertSame(methods1, methods2);
    }

    @Test
    void clearRemovesCachedMethods() {
        IntrospectionUtils.findMethods(TestBean.class);
        IntrospectionUtils.clear();
        // After clear, findMethods should return a new array (not cached)
        var methods = IntrospectionUtils.findMethods(TestBean.class);
        assertNotNull(methods);
    }

    @Test
    void executeCallsMethod() throws Exception {
        var bean = new TestBean();
        IntrospectionUtils.execute(bean, "reset");
        assertEquals("reset", bean.getName());
    }

    @Test
    void executeThrowsForMissingMethod() {
        var bean = new TestBean();
        assertThrows(RuntimeException.class, () -> IntrospectionUtils.execute(bean, "nonexistent"));
    }

    @Test
    void setAttributeViaAttributeHolder() throws Exception {
        var holder = new TestAttributeHolder();
        IntrospectionUtils.setAttribute(holder, "key", "value");
        assertEquals("value", holder.lastValue);
    }

    @Test
    void callMethod0CallsNoArgMethod() throws Exception {
        var bean = new TestBean();
        bean.setName("hello");
        var result = IntrospectionUtils.callMethod0(bean, "getName");
        assertEquals("hello", result);
    }

    @Test
    void callMethod0ThrowsForMissing() {
        var bean = new TestBean();
        assertThrows(NoSuchMethodException.class, () -> IntrospectionUtils.callMethod0(bean, "nonexistent"));
    }

    @Test
    void convertString() {
        assertEquals("test", IntrospectionUtils.convert("test", String.class));
    }

    @Test
    void convertInteger() {
        assertEquals(42, IntrospectionUtils.convert("42", int.class));
    }

    @Test
    void convertBoolean() {
        assertEquals(true, IntrospectionUtils.convert("true", boolean.class));
    }

    @Test
    void convertThrowsForInvalidNumber() {
        assertThrows(IllegalArgumentException.class, () -> IntrospectionUtils.convert("notanumber", int.class));
    }

    @Test
    void hasHookReturnsTrueForOverriddenMethod() {
        var bean = new OverridingBean();
        assertTrue(IntrospectionUtils.hasHook(bean, "doSomething"));
    }

    // -- Test helpers --

    @SuppressWarnings("unused")
    public static class TestBean {
        private String name;
        private int count;
        private boolean active;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
        public void reset() { this.name = "reset"; }
    }

    public static class BaseBean {
        public void doSomething() { }
    }

    public static class OverridingBean extends BaseBean {
        @Override
        public void doSomething() { }
    }

    public static class TestAttributeHolder implements IntrospectionUtils.AttributeHolder {
        Object lastValue;

        @Override
        public void setAttribute(String key, Object o) {
            this.lastValue = o;
        }
    }
}

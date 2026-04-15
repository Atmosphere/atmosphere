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
package org.atmosphere.config.managed;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InvokerTest {

    // --- Test Decoder/Encoder implementations ---

    public static class StringToIntDecoder implements Decoder<String, Integer> {
        @Override
        public Integer decode(String s) {
            return Integer.parseInt(s);
        }
    }

    public static class IntToStringEncoder implements Encoder<Integer, String> {
        @Override
        public String encode(Integer i) {
            return "encoded:" + i;
        }
    }

    public static class StringUpperEncoder implements Encoder<String, String> {
        @Override
        public String encode(String s) {
            return s.toUpperCase();
        }
    }

    public static class FailingDecoder implements Decoder<String, String> {
        @Override
        public String decode(String s) {
            throw new RuntimeException("decode failed");
        }
    }

    // --- Test target object ---

    public static class Target {
        public String echo(String input) {
            return "echo:" + input;
        }

        public String noArgs() {
            return "no-args";
        }

        public void throwError() {
            throw new UnsupportedOperationException("test error");
        }

        public String throwCheckedException() throws Exception {
            throw new Exception("checked");
        }
    }

    // --- decode tests ---

    @Test
    void decodeWithMatchingDecoder() {
        List<Decoder<?, ?>> decoders = List.of(new StringToIntDecoder());
        Object result = Invoker.decode(decoders, "42");
        assertEquals(42, result);
    }

    @Test
    void decodeWithNoDecodersReturnsOriginal() {
        List<Decoder<?, ?>> decoders = List.of();
        Object result = Invoker.decode(decoders, "hello");
        assertEquals("hello", result);
    }

    @Test
    void decodeWithNonMatchingDecoderReturnsNull() {
        List<Decoder<?, ?>> decoders = List.of(new StringToIntDecoder());
        Object result = Invoker.decode(decoders, 42);
        // Int doesn't match String decoder, returns null
        assertNull(result);
    }

    @Test
    void decodeWithNullInputReturnsNull() {
        List<Decoder<?, ?>> decoders = List.of(new StringToIntDecoder());
        Object result = Invoker.decode(decoders, null);
        assertNull(result);
    }

    @Test
    void decodeWithFailingDecoderReturnsNull() {
        List<Decoder<?, ?>> decoders = List.of(new FailingDecoder());
        Object result = Invoker.decode(decoders, "input");
        // Exception is caught, returns null
        assertNull(result);
    }

    // --- encode tests ---

    @Test
    void encodeWithMatchingEncoder() {
        List<Encoder<?, ?>> encoders = List.of(new IntToStringEncoder());
        Object result = Invoker.encode(encoders, 42);
        assertEquals("encoded:42", result);
    }

    @Test
    void encodeWithNoEncodersReturnsOriginal() {
        List<Encoder<?, ?>> encoders = List.of();
        Object result = Invoker.encode(encoders, "hello");
        assertEquals("hello", result);
    }

    @Test
    void encodeWithNonMatchingEncoderReturnsNull() {
        List<Encoder<?, ?>> encoders = List.of(new IntToStringEncoder());
        Object result = Invoker.encode(encoders, "not-an-int");
        assertNull(result);
    }

    @Test
    void encodeWithNullInputReturnsNull() {
        List<Encoder<?, ?>> encoders = List.of(new IntToStringEncoder());
        Object result = Invoker.encode(encoders, null);
        assertNull(result);
    }

    // --- invokeMethod tests ---

    @Test
    void invokeMethodWithArgument() throws Exception {
        var target = new Target();
        var method = Target.class.getMethod("echo", String.class);
        Object result = Invoker.invokeMethod(method, target, "test");
        assertEquals("echo:test", result);
    }

    @Test
    void invokeMethodWithNoArgs() throws Exception {
        var target = new Target();
        var method = Target.class.getMethod("noArgs");
        Object result = Invoker.invokeMethod(method, target);
        assertEquals("no-args", result);
    }

    @Test
    void invokeMethodWithWrongArgumentReturnsNull() throws Exception {
        var target = new Target();
        var method = Target.class.getMethod("echo", String.class);
        // Pass wrong type
        Object result = Invoker.invokeMethod(method, target, 42);
        assertNull(result);
    }

    @Test
    void invokeMethodThatThrowsCheckedExceptionReturnsNull() throws Exception {
        var target = new Target();
        var method = Target.class.getMethod("throwCheckedException");
        Object result = Invoker.invokeMethod(method, target);
        assertNull(result);
    }

    @Test
    void invokeMethodThatThrowsErrorPropagates() throws Exception {
        var target = new Target();
        var method = Target.class.getMethod("throwError");
        // UnsupportedOperationException wraps in InvocationTargetException;
        // the cause is NOT an Error so it's logged, returns null
        Object result = Invoker.invokeMethod(method, target);
        assertNull(result);
    }

    // --- matchDecoder tests ---

    @Test
    void matchDecoderWithMatchingType() {
        List<Decoder<?, ?>> decoders = List.of(new StringToIntDecoder());
        Object result = Invoker.matchDecoder("123", decoders);
        assertEquals(123, result);
    }

    @Test
    void matchDecoderWithEmptyListReturnsOriginal() {
        Object result = Invoker.matchDecoder("hello", List.of());
        assertEquals("hello", result);
    }

    // --- matchEncoder tests ---

    @Test
    void matchEncoderWithMatchingType() {
        List<Encoder<?, ?>> encoders = List.of(new IntToStringEncoder());
        Object result = Invoker.matchEncoder(42, encoders);
        assertEquals("encoded:42", result);
    }

    @Test
    void matchEncoderWithEmptyListReturnsOriginal() {
        Object result = Invoker.matchEncoder("hello", List.of());
        assertEquals("hello", result);
    }

    @Test
    void matchEncoderWithNullReturnsNull() {
        List<Encoder<?, ?>> encoders = List.of(new IntToStringEncoder());
        Object result = Invoker.matchEncoder(null, encoders);
        assertNull(result);
    }

    // --- all (full pipeline) tests ---

    @Test
    void allWithDecoderMethodEncoder() throws Exception {
        var target = new Target();
        var method = Target.class.getMethod("echo", String.class);
        List<Encoder<?, ?>> encoders = List.of(new StringUpperEncoder());
        List<Decoder<?, ?>> decoders = List.of();

        Object result = Invoker.all(encoders, decoders, "test", target, method);
        assertEquals("ECHO:TEST", result);
    }

    @Test
    void allWithNoEncodersReturnsMethodResult() throws Exception {
        var target = new Target();
        var method = Target.class.getMethod("echo", String.class);

        Object result = Invoker.all(List.of(), List.of(), "test", target, method);
        assertEquals("echo:test", result);
    }
}

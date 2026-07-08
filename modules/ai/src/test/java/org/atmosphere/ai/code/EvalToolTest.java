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
package org.atmosphere.ai.code;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Pins the in-process {@code eval} tool: it computes correctly, is sandboxed
 * against host reach and runaway loops, surfaces errors as data, and is off by
 * default. The sandbox-escape tests deliberately never <em>invoke</em> a host
 * method — they assert the Java bridge is absent — so a hypothetically-broken
 * sandbox cannot harm the test JVM.
 */
public class EvalToolTest {

    /** An enabled evaluator with tight-but-workable bounds for fast tests. */
    private static EvalSupport enabled() {
        return new EvalSupport(new EvalConfig(true, 2_000_000, 2_000L, 100));
    }

    // ── Computation ──

    @Test
    public void computesArithmeticStringsAndJson() {
        var eval = enabled();
        assertEquals("4", eval.evaluate("2 + 2").value());
        assertEquals("HELLO", eval.evaluate("'hello'.toUpperCase()").value());
        assertEquals("[2,4,6]", eval.evaluate("[1,2,3].map(x => x * 2)").value());
        assertEquals("{\"a\":1,\"b\":2}", eval.evaluate("({a: 1, b: 2})").value());
        assertEquals("6", eval.evaluate("[1,2,3].reduce((a, b) => a + b, 0)").value());
    }

    @Test
    public void undefinedResultSerializesCleanly() {
        var r = enabled().evaluate("var x = 5;");
        assertTrue(r.ok(), r.error());
        assertEquals("undefined", r.value());
    }

    // ── Isolation: no host reach (assert the Java bridge is absent) ──

    @Test
    public void javaBridgeIsAbsentFromScope() {
        var eval = enabled();
        // The LiveConnect bridge names must not resolve to anything usable.
        assertEquals("undefined", eval.evaluate("typeof java").value());
        assertEquals("undefined", eval.evaluate("typeof Packages").value());
        assertEquals("undefined", eval.evaluate("typeof getClass").value());
        assertEquals("undefined", eval.evaluate("typeof importClass").value());
    }

    @Test
    public void reachingIntoJavaThrowsReferenceError() {
        // `java` being undefined throws BEFORE any method is reached — no host
        // call ever happens even if this assertion regressed.
        var r = enabled().evaluate("java.lang.Runtime.getRuntime()");
        assertFalse(r.ok());
        assertTrue(r.error().toLowerCase().contains("java"), r.error());
    }

    @Test
    public void functionConstructorCannotReachHost() {
        // Classic escape: reconstruct a function to grab the global. The global
        // is the safe scope, which has no `java`, so this stays undefined.
        var r = enabled().evaluate(
                "var g = (function(){}).constructor('return this')(); typeof g.java");
        assertTrue(r.ok(), r.error());
        assertEquals("undefined", r.value());
    }

    // ── Bounded CPU: runaway loops abort, they do not hang ──

    @Test
    public void infiniteLoopIsCappedNotHung() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var r = enabled().evaluate("while (true) {}");
            assertFalse(r.ok());
            assertTrue(r.error().toLowerCase().contains("loop")
                    || r.error().toLowerCase().contains("budget")
                    || r.error().toLowerCase().contains("limit"), r.error());
        });
    }

    @Test
    public void tryCatchCannotSwallowTheBudgetAbort() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            // A script that tries to trap the abort and keep looping must still die.
            var r = enabled().evaluate("while (true) { try { 1 + 1; } catch (e) {} }");
            assertFalse(r.ok(), "budget abort must not be swallowable by JS try/catch");
        });
    }

    // ── Errors are data, not thrown ──

    @Test
    public void syntaxErrorIsReturnedNotThrown() {
        var r = enabled().evaluate("function (");
        assertFalse(r.ok());
        assertTrue(r.error() != null && !r.error().isBlank());
    }

    @Test
    public void runtimeErrorIsReturnedNotThrown() {
        var r = enabled().evaluate("null.foo");
        assertFalse(r.ok());
        assertTrue(r.error() != null && !r.error().isBlank());
    }

    // ── Fresh scope per call ──

    @Test
    public void noStatePersistsBetweenCalls() {
        var eval = enabled();
        assertTrue(eval.evaluate("var secret = 42; secret").ok());
        assertEquals("undefined", eval.evaluate("typeof secret").value());
    }

    // ── Output cap ──

    @Test
    public void oversizeOutputIsTruncated() {
        // maxOutputChars is 100 in enabled(); build a longer string.
        var r = enabled().evaluate("'x'.repeat(500)");
        assertTrue(r.ok(), r.error());
        assertTrue(r.truncated());
        assertEquals(100, r.value().length());
    }

    // ── Default deny ──

    @Test
    public void disabledByDefaultReturnsNotEnabled() {
        var eval = new EvalSupport(EvalConfig.fromSystemProperties());
        assertFalse(eval.isEnabled());
        var r = eval.evaluate("2 + 2");
        assertFalse(r.ok());
        assertTrue(r.error().toLowerCase().contains("not enabled"), r.error());
    }

    @Test
    public void enabledSupportOffersTheEvalTool() {
        var eval = enabled();
        assertTrue(eval.isEnabled());
        assertEquals(EvalTool.TOOL_NAME, eval.tool().name());
        assertEquals("eval", eval.tool().name());
    }

    @Test
    public void blankScriptIsRejected() {
        var r = enabled().evaluate("   ");
        assertFalse(r.ok());
        assertTrue(r.error().contains("required"), r.error());
    }
}

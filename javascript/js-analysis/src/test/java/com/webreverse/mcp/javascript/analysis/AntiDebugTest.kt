package com.webreverse.mcp.javascript.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AntiDebugDetector + ScriptInterceptor 单元测试。
 *
 * 覆盖报告 §11 A~M 的反调试识别，以及 §7 脚本执行前拦截改写的中和效果。
 */
class AntiDebugTest {

    private val detector = AntiDebugDetector()

    @Test
    fun `detects plain debugger statement`() {
        val src = """
            var a = 1;
            debugger;
            var b = 2;
        """.trimIndent()
        val r = detector.detect(src)
        assertTrue(r.techniques.contains("debugger_statement"))
        assertTrue(r.risk > 0.0)
    }

    @Test
    fun `detects function constructor debugger`() {
        val src = "new Function(\"debugger\");"
        assertTrue(detector.detect(src).techniques.contains("debugger_function_constructor"))
    }

    @Test
    fun `detects timer debugger loop`() {
        val src = """
            setInterval(function() {
                debugger;
            }, 1000);
        """.trimIndent()
        assertTrue(detector.detect(src).techniques.contains("timer_debugger_loop"))
    }

    @Test
    fun `detects eval debugger`() {
        assertTrue(detector.detect("eval(\"debugger\")").techniques.contains("eval_debugger"))
    }

    @Test
    fun `detects devtools size detection`() {
        val src = "if (window.outerWidth - window.innerWidth > 160) { reveal(); }"
        assertTrue(detector.detect(src).techniques.contains("devtools_size_detection"))
    }

    @Test
    fun `detects console getter trap`() {
        val src = "console.log({ get x() { return 1; } });"
        assertTrue(detector.detect(src).techniques.contains("console_getter_trap"))
    }

    @Test
    fun `does not flag benign code`() {
        val src = "function add(a,b){ return a+b; } console.log(add(1,2));"
        val r = detector.detect(src)
        assertEquals(0.0, r.risk, 0.001)
        assertTrue(r.techniques.isEmpty())
    }

    // ---------------- ScriptInterceptor ----------------

    private val interceptor = ScriptInterceptor()

    @Test
    fun `neutralizes plain debugger statements`() {
        val src = """
            (function() {
                debugger;
                var x = 1;
                debugger;
                return x;
            })();
        """.trimIndent()
        val r = interceptor.transform(src)
        assertTrue(r.debuggerNeutralized >= 2)
        assertFalse(r.transformed.contains("\ndebugger"))
        assertTrue(r.transformRules.contains("neutralize_debugger_statements"))
    }

    @Test
    fun `neutralizes function constructor debugger`() {
        val src = "var f = new Function(\"debugger\");"
        val r = interceptor.transform(src)
        assertTrue(r.debuggerNeutralized >= 1)
        assertFalse(r.transformed.contains("debugger"))
    }

    @Test
    fun `neutralizes constructor call debugger`() {
        val src = "var g = (function(){}).constructor(\"debugger\")();"
        val r = interceptor.transform(src)
        assertTrue(r.debuggerNeutralized >= 1)
    }

    @Test
    fun `marks patched script once`() {
        // 标记只在脚本**确实被改写**时注入（未改写的响应保持原样，避免无谓改动 SRI 哈希）
        val r = interceptor.transform("debugger; var a = 1;")
        assertTrue(r.transformed.contains("__WRMCP_SCRIPT_PATCHED__"))
    }

    @Test
    fun `detects javascript mime types`() {
        assertTrue(interceptor.isJavaScriptMime("text/javascript"))
        assertTrue(interceptor.isJavaScriptMime("application/javascript; charset=utf-8"))
        assertTrue(interceptor.isJavaScriptMime("application/x-javascript"))
        assertFalse(interceptor.isJavaScriptMime("application/json"))
        assertFalse(interceptor.isJavaScriptMime(null))
    }

    @Test
    fun `issue sri warning only when patched`() {
        val r = interceptor.transform("debugger;")
        assertTrue(r.sriCompatibility == SriCompatibility.PATCHED_VIOLATES_SRI)
        val w = interceptor.sriWarning("sha384-abc", r)
        assertTrue(w != null && w.contains("integrity"))

        val clean = interceptor.transform("var o=1;")
        assertEquals(SriCompatibility.NONE, clean.sriCompatibility)
    }

    // ---------------- 回归：new Function 前缀 / 构造器调用 / 字符串与注释不被误伤 ----------------

    @Test
    fun `neutralizes new Function debugger and counts it`() {
        // 回归：此前 prev 词边界只判「前一个字符不是标识符」，`new Function(...)` 的
        // `Function` 前是 `w`，整条漏过（实测 AntiDebugTest 失败）
        val r = interceptor.transform("var f = new Function(\"debugger\");")
        assertTrue(r.debuggerNeutralized >= 1)
        assertFalse(r.transformed.contains("\"debugger\""))
    }

    @Test
    fun `neutralizes eval debugger literal`() {
        val r = interceptor.transform("eval('debugger + 1 > 0 && 0');")
        assertTrue(r.debuggerNeutralized >= 1)
    }

    @Test
    fun `keeps debugger text inside strings comments and regex`() {
        val src = """
            var s = "debugger";
            // debugger 注释里的文本
            /* new Function("debugger") 注释 */
            var re = /debugger/g;
        """.trimIndent()
        val r = interceptor.transform(src)
        assertEquals(0, r.debuggerNeutralized)
        assertTrue(r.transformed.contains("\"debugger\""))
        assertTrue(r.transformed.contains("/debugger/g"))
    }

    @Test
    fun `does not touch identifier containing debugger`() {
        val r = interceptor.transform("var mydebugger = 1; obj.debuggerFlag = 2;")
        assertEquals(0, r.debuggerNeutralized)
        assertTrue(r.transformed.contains("mydebugger"))
        assertTrue(r.transformed.contains("debuggerFlag"))
    }
}
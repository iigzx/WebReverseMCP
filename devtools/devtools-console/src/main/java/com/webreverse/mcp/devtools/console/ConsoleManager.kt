package com.webreverse.mcp.devtools.console

import com.webreverse.mcp.browser.engine.BrowserEngine
import com.webreverse.mcp.browser.engine.util.JsScripts
import com.webreverse.mcp.core.common.util.AppError
import com.webreverse.mcp.core.common.util.AppResult
import kotlinx.serialization.json.Json

/** JavaScript 控制台管理器 */
class ConsoleManager {

    suspend fun evaluate(engine: BrowserEngine, expression: String): AppResult<String> {
        if (expression.isBlank()) return AppResult.failure(AppError.INVALID_ARGUMENTS)
        val result = engine.evaluateJavascript(expression)
            ?: return AppResult.failure(AppError.JS_EXECUTION_FAILED)
        return AppResult.success(result)
    }

    /**
     * 异步执行 JavaScript（支持 await），等待 Promise 结算后返回结果。
     *
     * 修复：原实现把表达式包成 async IIFE 直接交给引擎，但
     * Android WebView 的 evaluateJavascript 回调不等待 Promise——结果为
     * Promise 时只拿到 `{}`，js.evaluate_async 永远返回空对象。
     * Promise 等待逻辑已下沉到 WebViewBrowserEngine.evaluateJavascriptAsync
     * （「落盘 + 轮询」），此处仅负责把表达式包进 async IIFE 以支持顶层 await。
     *
     * 修复：补全值丢失——原包裹 `(async function{ $expr })` 对
     * 多语句体（如 `setTimeout(...); 'ok'` 或 `promise.then(...)` 结尾不接
     * return）永远返回 undefined（IIFE 无 return 语句），AI 拿不到尾表达式
     * 的值。改用 eval 补全值语义（与 DevTools Console 一致）：eval 返回最后
     * 一个表达式语句的完成值，再由 await 展开 Promise。
     */
    suspend fun evaluateAsync(engine: BrowserEngine, expression: String): AppResult<String> {
        if (expression.isBlank()) return AppResult.failure(AppError.INVALID_ARGUMENTS)
        val wrapped = "(async function(){\n" +
            "return await eval(${JsScripts.quote(expression)})\n" +
            "})()"
        val result = engine.evaluateJavascriptAsync(wrapped)
            ?: return AppResult.failure(
                AppError(
                    "JS_EXECUTION_FAILED",
                    "异步执行失败（Promise rejected、页面导航或超过引擎 30s 等待上限）",
                ),
            )
        return AppResult.success(result)
    }

    /**
     * 智能求值：表达式返回 thenable 时自动等它结算，否则保持同步语义原样返回。
     *
     * 背景（AI 使用体验）：Android WebView 的 evaluateJavascript 回调不等待 Promise，
     * `js.evaluate("fetch(...).then(r=>r.json())")` 只会得到 `{}`。此前文档要求 AI 用
     * 「暂存 + 轮询」两步法绕开，既费轮次又容易踩空。
     *
     * 实现为 **单次求值**（不重复执行表达式，避免副作用）：包一层脚本，
     * 若求值结果是 thenable 就把它挂到页面槽位 window.__mcpAsyncResults 并返回
     * `{__mcpThenable:true, slot:id}` 标记；Kotlin 侧轮询槽位取回结算值。
     * 非 thenable 时包装函数直接 `return v` —— WebView 的 JSON 编码结果与旧路径完全一致，
     * 因此对既有调用零行为变化（字符串仍是 JSON 引号形式、对象仍是 JSON 文本）。
     *
     * 异常也不再被吞成 `null`：包装内 catch 后写入槽位，返回明确错误（AI 可自行修表达式）。
     */
    suspend fun evaluateAuto(engine: BrowserEngine, expression: String): AppResult<String> {
        if (expression.isBlank()) return AppResult.failure(AppError.INVALID_ARGUMENTS)
        val probe = """
            (function(){
              try {
                window.__mcpAsyncResults = window.__mcpAsyncResults || {};
                var v = eval(${JsScripts.quote(expression)});
                if (v && typeof v.then === 'function') {
                  var seq = (window.__mcpAsyncSeq = (window.__mcpAsyncSeq || 0) + 1);
                  var id = 'auto' + seq;
                  window.__mcpAsyncResults[id] = {done:false};
                  var settle = function(ok, x){
                    var s;
                    try { s = (typeof x === 'string') ? x : JSON.stringify(x); } catch(e){ s = String(x); }
                    if (s === undefined || s === null) s = String(x);
                    window.__mcpAsyncResults[id] = ok
                      ? {done:true, ok:true, value:String(s).substring(0, 1000000)}
                      : {done:true, ok:false, error:String(x && x.message ? x.message : x).substring(0, 10000)};
                  };
                  v.then(function(r){ settle(true, r); }, function(e){ settle(false, e); });
                  return JSON.stringify({__mcpThenable:true, slot:id});
                }
                return v;
              } catch(e) {
                return JSON.stringify({__mcpThrown:true, message:String(e && e.message ? e.message : e)});
              }
            })()
        """.trimIndent()
        val raw = engine.evaluateJavascript(probe) ?: return AppResult.failure(AppError.JS_EXECUTION_FAILED)
        val marker = parseProbeMarker(raw)
        if (marker != null) {
            val thrown = marker["__mcpThrown"]
            if (thrown != null) {
                return AppResult.failure(AppError("JS_EXECUTION_FAILED", "表达式抛异常：${thrown.take(2000)}"))
            }
            val slot = marker["__mcpThenable"] ?: return AppResult.success(raw)
            val value = pollAsyncSlot(engine, slot)
                ?: return AppResult.failure(
                    AppError(
                        "JS_EXECUTION_FAILED",
                        "Promise 未在 30s 内结算（rejected、页面导航或长期 pending）。" +
                            "如需立即拿到 Promise 对象本身，用 awaitPromise=false",
                    ),
                )
            return AppResult.success(value)
        }
        return AppResult.success(raw)
    }

    /** 解析智能求值探针返回的标记对象；非标记返回 null */
    private fun parseProbeMarker(raw: String): Map<String, String>? {
        val text = raw.trim()
        if (!text.contains("__mcpThenable") && !text.contains("__mcpThrown")) return null
        return runCatching {
            val outer = Json.parseToJsonElement(text)
            val inner = (outer as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
            val obj = Json.parseToJsonElement(inner) as? kotlinx.serialization.json.JsonObject ?: return null
            obj.mapValues { (_, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: v.toString() }
        }.getOrNull()
    }

    /** 轮询页面槽位取回 Promise 结算值（与引擎 evaluateJavascriptAsync 同一槽位协议） */
    private suspend fun pollAsyncSlot(engine: BrowserEngine, slot: String): String? {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val poll = """
                (function(){
                  var r = window.__mcpAsyncResults && window.__mcpAsyncResults[${JsScripts.quote(slot)}];
                  if (!r || !r.done) return null;
                  var out = JSON.stringify(r);
                  try { delete window.__mcpAsyncResults[${JsScripts.quote(slot)}]; } catch(e){}
                  return out;
                })()
            """.trimIndent()
            val raw = engine.evaluateJavascript(poll)
            val settled = runCatching {
                val outer = Json.parseToJsonElement(raw ?: "")
                val inner = (outer as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@runCatching null
                Json.parseToJsonElement(inner) as? kotlinx.serialization.json.JsonObject
            }.getOrNull()
            if (settled != null) {
                val ok = settled["ok"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } == "true"
                if (!ok) return null
                return settled["value"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            }
            kotlinx.coroutines.delay(120)
        }
        return null
    }

    suspend fun callFunction(engine: BrowserEngine, functionName: String, vararg args: String): AppResult<String> {
        val argsJson = args.joinToString(",") { JsScripts.quote(it) }
        val result = engine.evaluateJavascript("($functionName)($argsJson)")
            ?: return AppResult.failure(AppError.JS_EXECUTION_FAILED)
        return AppResult.success(result)
    }

    suspend fun inspect(engine: BrowserEngine, expression: String): AppResult<String> {
        val script = """
            (function(){
              try {
                var value = eval(${JsScripts.quote(expression)});
                return JSON.stringify({
                  type: typeof value,
                  value: (typeof value === 'object' && value !== null) ? JSON.stringify(value).substring(0, 10000) : String(value),
                  keys: (typeof value === 'object' && value !== null) ? Object.keys(value).slice(0, 100) : [],
                  isArray: Array.isArray(value),
                  isFunction: typeof value === 'function',
                  isNull: value === null,
                  isUndefined: value === undefined,
                  isNumber: typeof value === 'number',
                  isString: typeof value === 'string',
                  isBoolean: typeof value === 'boolean'
                });
              } catch(e) {
                return JSON.stringify({error: e.message});
              }
            })()
        """.trimIndent()
        val result = engine.evaluateJavascript(script)
            ?: return AppResult.failure(AppError.JS_EXECUTION_FAILED)
        return AppResult.success(result)
    }

    suspend fun watch(engine: BrowserEngine, expression: String): AppResult<String> {
        return evaluate(engine, expression)
    }

    suspend fun getGlobal(engine: BrowserEngine, name: String): AppResult<String> {
        val result = engine.evaluateJavascript("JSON.stringify(window[$name])")
            ?: return AppResult.failure(AppError.JS_EXECUTION_FAILED)
        return AppResult.success(result)
    }

    suspend fun getProperty(engine: BrowserEngine, objectExpression: String, property: String): AppResult<String> {
        val result = engine.evaluateJavascript("JSON.stringify(($objectExpression)[${JsScripts.quote(property)}])")
            ?: return AppResult.failure(AppError.JS_EXECUTION_FAILED)
        return AppResult.success(result)
    }

    suspend fun setProperty(engine: BrowserEngine, objectExpression: String, property: String, value: String): AppResult<Boolean> {
        engine.evaluateJavascript("($objectExpression)[${JsScripts.quote(property)}] = $value")
        return AppResult.success(true)
    }

    suspend fun deleteProperty(engine: BrowserEngine, objectExpression: String, property: String): AppResult<Boolean> {
        engine.evaluateJavascript("delete ($objectExpression)[${JsScripts.quote(property)}]")
        return AppResult.success(true)
    }

    suspend fun clearConsole(engine: BrowserEngine) {
        engine.evaluateJavascript("console.clear()")
    }
}

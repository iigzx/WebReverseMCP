package com.webreverse.mcp.mcp.tools

import com.webreverse.mcp.core.common.permission.PermissionScope
import com.webreverse.mcp.core.common.permission.RiskLevel
import com.webreverse.mcp.core.mcp.McpTool
import com.webreverse.mcp.core.mcp.McpToolResult
import com.webreverse.mcp.core.mcp.ToolCategory
import com.webreverse.mcp.browser.engine.BrowserEngine
import com.webreverse.mcp.core.mcp.McpContent
import com.webreverse.mcp.core.common.util.Redactor

/** Browser Tools：浏览器导航与页面操作 */
object BrowserTools {

    fun all(deps: ToolDependencies): List<McpTool> {
        val f = ToolFactory(deps)
        return listOf(
            f.tool(
                "browser.open", "打开 URL 并导航到指定页面", ToolCategory.BROWSER,
                PermissionScope.READ_PAGE, RiskLevel.LOW,
                inputSchema = Schemas.objectSchema(
                    "url" to Schemas.strSchema("要打开的 URL"),
                    required = listOf("url"),
                ),
            ) { args ->
                val url = ToolArgs.str(args, "url")
                if (url.isBlank()) return@tool McpToolResult.error("INVALID_ARGUMENTS", "url 不能为空")
                val session = deps.activeSession()
                session.engine.loadUrl(url)
                deps.log(com.webreverse.mcp.core.logging.LogCategory.BROWSER, "open: $url")
                McpToolResult.text("已打开: $url")
            },
            f.tool(
                "browser.close", "关闭当前页面", ToolCategory.BROWSER,
                PermissionScope.READ_PAGE, RiskLevel.LOW,
            ) { _ ->
                val tabId = deps.browserService.activeTabId.value
                if (tabId != null) {
                    deps.tabManager.closeTab(tabId)
                    McpToolResult.text("已关闭标签页: $tabId")
                } else {
                    McpToolResult.error("NO_ACTIVE_TAB", "没有活动标签页")
                }
            },
            f.tool(
                "browser.reload", "刷新当前页面", ToolCategory.BROWSER,
                PermissionScope.READ_PAGE, RiskLevel.LOW,
            ) { _ ->
                val session = deps.activeSession()
                session.engine.reload()
                McpToolResult.text("已刷新")
            },
            f.tool(
                "browser.back", "页面后退", ToolCategory.BROWSER,
                PermissionScope.READ_PAGE, RiskLevel.LOW,
            ) { _ ->
                val session = deps.activeSession()
                val ok = session.engine.goBack()
                McpToolResult.text(if (ok) "已后退" else "无法后退")
            },
            f.tool(
                "browser.forward", "页面前进", ToolCategory.BROWSER,
                PermissionScope.READ_PAGE, RiskLevel.LOW,
            ) { _ ->
                val session = deps.activeSession()
                val ok = session.engine.goForward()
                McpToolResult.text(if (ok) "已前进" else "无法前进")
            },
            f.tool(
                "browser.stop", "停止加载", ToolCategory.BROWSER,
                PermissionScope.READ_PAGE, RiskLevel.LOW,
            ) { _ ->
                val session = deps.activeSession()
                session.engine.stop()
                McpToolResult.text("已停止加载")
            },
            f.tool(
                "browser.go", "导航到指定 URL（支持相对路径）", ToolCategory.BROWSER,
                PermissionScope.READ_PAGE, RiskLevel.LOW,
                inputSchema = Schemas.objectSchema("url" to Schemas.strSchema("目标 URL")),
            ) { args ->
                val url = ToolArgs.str(args, "url")
                val session = deps.activeSession()
                val current = session.engine.currentUrl() ?: ""
                val resolved = if (url.startsWith("/")) {
                    val base = current.substringBeforeLast('/')
                    "$base$url"
                } else url
                session.engine.loadUrl(resolved)
                McpToolResult.text("已导航: $resolved")
            },
            f.tool(
                "browser.current_url", "获取当前页面 URL", ToolCategory.BROWSER,
                PermissionScope.READ_PAGE, RiskLevel.LOW,
            ) { _ ->
                val session = deps.activeSession()
                val url = session.engine.currentUrl() ?: ""
                McpToolResult.json(
                    kotlinx.serialization.json.buildJsonObject {
                        put("url", kotlinx.serialization.json.JsonPrimitive(Redactor.redactUrl(url)))
                    },
                )
            },
            f.tool(
                "browser.current_title", "获取当前页面标题", ToolCategory.BROWSER,
                PermissionScope.READ_PAGE, RiskLevel.LOW,
            ) { _ ->
                val session = deps.activeSession()
                val title = session.engine.currentTitle() ?: ""
                McpToolResult.json(
                    kotlinx.serialization.json.buildJsonObject {
                        put("title", kotlinx.serialization.json.JsonPrimitive(title))
                    },
                )
            },
            f.tool(
                "browser.screenshot",
                "截取当前页面。默认视口截图；fullPage=true 截整页（CDP captureBeyondViewport）；selector=CSS 选择器 截单个元素（按元素在文档中的坐标裁剪）。" +
                    "fullPage/selector 需要 CDP（未 attach 会先自动 attach）；CDP 不可用时降级为视口截图，并在 structured 里给 degraded=true 与补救动作",
                ToolCategory.BROWSER,
                PermissionScope.SCREENSHOT, RiskLevel.MEDIUM, supportsImage = true,
                inputSchema = Schemas.objectSchema(
                    "format" to Schemas.strSchema("png/jpeg（默认 png）"),
                    "fullPage" to Schemas.boolSchema("是否全页截图（默认 false = 仅视口；需 CDP）"),
                    "selector" to Schemas.strSchema("元素选择器（如 #app、div.login-form）；与 fullPage 同时给时以 selector 为准；需 CDP"),
                ),
            ) { args ->
                val session = deps.activeSession()
                val format = if (ToolArgs.str(args, "format").equals("jpeg", ignoreCase = true)) "jpeg" else "png"
                val fullPage = ToolArgs.bool(args, "fullPage", false)
                val selector = ToolArgs.optStr(args, "selector")?.takeIf { it.isNotBlank() }

                if (fullPage || selector != null) {
                    // 修复：fullPage / selector 此前声明了却从不读取（"全页/元素截图"不成立）。
                    // 走 CDP Page.captureScreenshot（captureBeyondViewport + clip），
                    // 未 attach 时先幂等 attach 一次，避免让 AI 多走一轮。
                    if (deps.debuggerManager.backend != "cdp") {
                        runCatching { deps.debuggerManager.attach(session.engine) }
                    }
                    captureRegionViaCdp(deps, session.engine, format, fullPage, selector)?.let { return@tool it }
                    val bitmap = session.engine.screenshot()
                        ?: return@tool McpToolResult.error(
                            "SCREENSHOT_FAILED",
                            "截图失败：CDP 与视口两条路径都不可用",
                        )
                    return@tool viewportShotResult(
                        bitmap, format,
                        buildJsonObject {
                            put("degraded", JsonPrimitive(true))
                            put("requested", JsonPrimitive(if (selector != null) "selector" else "fullPage"))
                            put("reason", JsonPrimitive("CDP 不可用（未 attach 或会话建立失败）"))
                            put("remedy", JsonPrimitive("先 debugger(action=\"attach\")，再重试本次调用即可拿到全页/元素截图"))
                        },
                    )
                }

                val bitmap = session.engine.screenshot()
                    ?: return@tool McpToolResult.error("SCREENSHOT_FAILED", "截图失败")
                viewportShotResult(bitmap, format, null)
            },
            f.tool(
                "browser.pdf", "将当前页面导出为 PDF（全页分页渲染，保存到工作目录）", ToolCategory.BROWSER,
                PermissionScope.SCREENSHOT, RiskLevel.MEDIUM, timeoutMs = 120_000,
                inputSchema = Schemas.objectSchema(
                    "filename" to Schemas.strSchema("保存的文件名（默认 page-<时间戳>.pdf）"),
                    "base64" to Schemas.boolSchema("是否在结果中附带 PDF 的 base64 内容（大文件慎用）"),
                ),
            ) { args ->
                val webView = deps.browserService.getWebView(null)
                    ?: return@tool McpToolResult.error("NO_ACTIVE_TAB", "没有活动标签页")
                val (bytes, pages) = renderWebViewToPdf(webView)
                    ?: return@tool McpToolResult.error("PDF_FAILED", "PDF 生成失败（页面可能尚未加载完成）")
                val requested = ToolArgs.optStr(args, "filename")?.takeIf { it.isNotBlank() }
                val safeName = (requested ?: "page-${System.currentTimeMillis()}").let {
                    if (it.endsWith(".pdf")) it else "$it.pdf"
                }
                // 统一保存到工作目录（不可写时自动回退应用私有目录，结果中返回真实路径）
                val file = com.webreverse.mcp.core.common.util.WorkDir
                    .resolve(deps.browserService.appContext(), safeName)
                file.writeBytes(bytes)
                McpToolResult.json(
                    kotlinx.serialization.json.buildJsonObject {
                        put("path", kotlinx.serialization.json.JsonPrimitive(file.absolutePath))
                        put("sizeBytes", kotlinx.serialization.json.JsonPrimitive(bytes.size.toLong()))
                        put("pages", kotlinx.serialization.json.JsonPrimitive(pages))
                        if (ToolArgs.bool(args, "base64")) {
                            put(
                                "base64",
                                kotlinx.serialization.json.JsonPrimitive(
                                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
                                ),
                            )
                        }
                    },
                )
            },
            f.tool(
                "browser.print", "调用系统打印服务打印当前页面（会在设备上弹出打印界面）", ToolCategory.BROWSER,
                PermissionScope.READ_PAGE, RiskLevel.LOW,
            ) { _ ->
                val webView = deps.browserService.getWebView(null)
                    ?: return@tool McpToolResult.error("NO_ACTIVE_TAB", "没有活动标签页")
                val title = webView.title?.takeIf { it.isNotBlank() } ?: "WebReverseMCP"
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    try {
                        val pm = webView.context.getSystemService(android.content.Context.PRINT_SERVICE)
                            as? android.print.PrintManager
                        if (pm == null) return@withContext false
                        pm.print(
                            title,
                            webView.createPrintDocumentAdapter(title),
                            android.print.PrintAttributes.Builder().build(),
                        )
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
                if (ok) McpToolResult.text("已打开系统打印界面: $title")
                else McpToolResult.error("PRINT_FAILED", "打印服务不可用或调用失败")
            },
            f.tool(
                "browser.download", "下载文件到工作目录", ToolCategory.BROWSER,
                PermissionScope.DOWNLOAD, RiskLevel.MEDIUM, timeoutMs = 300_000,
                inputSchema = Schemas.objectSchema(
                    "url" to Schemas.strSchema("要下载的文件 URL"),
                    "filename" to Schemas.strSchema("保存文件名（默认从 URL 推断）"),
                    "mimeType" to Schemas.strSchema("文件 MIME 类型（可选）"),
                ),
            ) { args ->
                val url = ToolArgs.str(args, "url")
                if (url.isBlank()) return@tool McpToolResult.error("INVALID_ARGUMENTS", "url 不能为空")
                val context = deps.browserService.appContext()
                val requested = ToolArgs.optStr(args, "filename")?.takeIf { it.isNotBlank() }
                val inferred = url.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }
                // mimeType：此前声明了却从不读取。现在用于——文件名无扩展名时按 MIME 补扩展名
                // （AI 常直接把二进制 URL 存成无后缀文件，落盘后打不开）、并在结果里回显。
                val mimeHint = ToolArgs.optStr(args, "mimeType")?.takeIf { it.isNotBlank() }
                val filename = applyMimeExtension(
                    (requested ?: inferred ?: "download-${System.currentTimeMillis()}")
                        .replace("/", "_").replace("\\", "_"),
                    mimeHint,
                )

                // data: URI 支持：AI Agent 可直接把文本/脚本内容落盘（无需网络）
                if (url.startsWith("data:", ignoreCase = true)) {
                    val decoded = decodeDataUri(url)
                        ?: return@tool McpToolResult.error("INVALID_ARGUMENTS", "data: URI 无效或不受支持（支持 base64 与 URL 编码两种格式）")
                    val safeName = if (filename == inferred) "data-${System.currentTimeMillis()}.txt" else filename
                    val target = com.webreverse.mcp.core.common.util.WorkDir.resolve(context, safeName)
                    return@tool kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        target.writeBytes(decoded.first)
                        McpToolResult.json(
                            kotlinx.serialization.json.buildJsonObject {
                                put("path", kotlinx.serialization.json.JsonPrimitive(target.absolutePath))
                                put("sizeBytes", kotlinx.serialization.json.JsonPrimitive(target.length()))
                                put("directory", kotlinx.serialization.json.JsonPrimitive("工作目录"))
                                put("mimeType", kotlinx.serialization.json.JsonPrimitive(decoded.second))
                            },
                        )
                    }
                }

                // 统一存储目录：无论工作目录是否可写，一律经 WorkDir.resolve 落盘
                // （可写 → 设置里的存储目录；不可写 → 自动回退应用私有目录，路径如实返回）。
                // 不再分流到系统公共下载目录（DownloadManager）——避免文件散落在
                // 与设置存储目录不一致的位置，AI 与用户都只认返回的 path。
                val target = com.webreverse.mcp.core.common.util.WorkDir.resolve(context, filename)
                when (val dl = downloadToFile(url, target)) {
                    is DownloadOutcome.Ok -> {
                        val file = dl.file
                        McpToolResult.json(
                            kotlinx.serialization.json.buildJsonObject {
                                put("path", kotlinx.serialization.json.JsonPrimitive(file.absolutePath))
                                put("sizeBytes", kotlinx.serialization.json.JsonPrimitive(file.length()))
                                put("directory", kotlinx.serialization.json.JsonPrimitive("工作目录"))
                                if (mimeHint != null) {
                                    put("mimeType", kotlinx.serialization.json.JsonPrimitive(mimeHint))
                                }
                            },
                        )
                    }
                    is DownloadOutcome.Failed -> McpToolResult.error(
                        "DOWNLOAD_FAILED",
                        "下载失败（$url）：${dl.reason}。" +
                            "可用 terminal.exec 的 curl/wget 拉取到工作目录（terminalHome）绕过，或先 browser.open 打开页面后在页面内触发下载",
                    )
                }
            },
            f.tool(
                "browser.upload", "向页面的 file input 注入文件并触发上传事件", ToolCategory.BROWSER,
                PermissionScope.UPLOAD, RiskLevel.MEDIUM, timeoutMs = 60_000,
                inputSchema = Schemas.objectSchema(
                    "selector" to Schemas.strSchema("file input 选择器，默认 input[type=file]"),
                    "path" to Schemas.strSchema(
                        "本地文件路径（绝对路径或工作目录相对路径）。" +
                            "提供后服务端直接读取该文件，优先于 content/contentBase64，注入文件名默认取该文件文件名。" +
                            "推荐先 browser.download 或 file.write 把文件落盘，再传 path——避免在工具参数里内联大 base64 导致 JSON 截断"
                    ),
                    "filename" to Schemas.strSchema("注入的文件名（默认 upload.txt 或 path 的文件名）"),
                    "mimeType" to Schemas.strSchema("文件 MIME 类型（默认 application/octet-stream）"),
                    "content" to Schemas.strSchema("文件文本内容（与 path/contentBase64 三选一）"),
                    "contentBase64" to Schemas.strSchema("文件 base64 内容（二进制文件使用；与 path 二选一）"),
                ),
            ) { args ->
                val path = ToolArgs.optStr(args, "path")?.takeIf { it.isNotBlank() }
                val textContent = ToolArgs.optStr(args, "content")?.takeIf { it.isNotBlank() }
                val base64Content = ToolArgs.optStr(args, "contentBase64")?.takeIf { it.isNotBlank() }
                if (path == null && textContent == null && base64Content == null) {
                    return@tool McpToolResult.error(
                        "INVALID_ARGUMENTS",
                        "必须提供 path（本地文件路径，推荐）或 content（文本）或 contentBase64（base64）之一；" +
                            "大文件/二进制请先落盘再用 path，避免参数 JSON 截断",
                    )
                }
                val filename = ToolArgs.optStr(args, "filename")?.takeIf { it.isNotBlank() }
                    ?: path?.let { runCatching { java.io.File(it).name }.getOrNull() }
                    ?: "upload.txt"
                val mimeType = ToolArgs.optStr(args, "mimeType")?.takeIf { it.isNotBlank() }
                    ?: "application/octet-stream"
                val selector = ToolArgs.optStr(args, "selector")?.takeIf { it.isNotBlank() }
                    ?: "input[type=file]"
                // path 优先——服务端读文件，避免把大 base64 内联进工具参数（JSON 截断根因）。
                val pathBase64 = path?.let { p ->
                    val f = resolveUploadFile(deps, p)
                    if (!f.isFile) {
                        return@tool McpToolResult.error(
                            "FILE_NOT_FOUND",
                            "path 对应文件不存在: $p（相对路径基于工作目录 ${com.webreverse.mcp.core.common.util.WorkDir.get()}）",
                        )
                    }
                    if (f.length() > MAX_UPLOAD_BYTES) {
                        return@tool McpToolResult.error(
                            "FILE_TOO_LARGE",
                            "上传文件过大（${f.length()} 字节，上限 ${MAX_UPLOAD_BYTES}）：$p",
                        )
                    }
                    android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
                }
                val base64 = pathBase64 ?: base64Content ?: android.util.Base64.encodeToString(
                    textContent.orEmpty().toByteArray(),
                    android.util.Base64.NO_WRAP,
                )

                val session = deps.activeSession()
                val jsSelector = kotlinx.serialization.json.JsonPrimitive(selector).toString()
                val jsFilename = kotlinx.serialization.json.JsonPrimitive(filename).toString()
                val jsMime = kotlinx.serialization.json.JsonPrimitive(mimeType).toString()
                val jsDataUrl = kotlinx.serialization.json.JsonPrimitive("data:$mimeType;base64,$base64").toString()
                val script = """
                    (async function(){
                      try {
                        var input = document.querySelector($jsSelector);
                        if (!input) return JSON.stringify({ok:false, error:'未找到元素: $selector'});
                        if ((input.type || '').toLowerCase() !== 'file') return JSON.stringify({ok:false, error:'目标元素不是 file input'});
                        if (typeof DataTransfer === 'undefined') return JSON.stringify({ok:false, error:'当前 WebView 内核不支持 DataTransfer'});
                        var res = await fetch($jsDataUrl);
                        var blob = await res.blob();
                        var file = new File([blob], $jsFilename, {type: $jsMime});
                        var dt = new DataTransfer();
                        dt.items.add(file);
                        input.files = dt.files;
                        input.dispatchEvent(new Event('input', {bubbles: true}));
                        input.dispatchEvent(new Event('change', {bubbles: true}));
                        return JSON.stringify({ok:true, fileName: file.name, size: file.size, mimeType: file.type});
                      } catch (e) {
                        return JSON.stringify({ok:false, error: String(e)});
                      }
                    })()
                """.trimIndent()
                val raw = session.engine.evaluateJavascriptAsync(script)
                    ?: return@tool McpToolResult.error("UPLOAD_FAILED", "脚本执行失败")
                val payload = unquoteJsResult(raw)
                return@tool try {
                    val obj = kotlinx.serialization.json.Json.parseToJsonElement(payload)
                        as? kotlinx.serialization.json.JsonObject
                        ?: return@tool McpToolResult.error("UPLOAD_FAILED", "意外的返回格式: $payload")
                    val ok = (obj["ok"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
                    if (ok) {
                        McpToolResult.json(obj)
                    } else {
                        val err = (obj["error"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                            ?: "注入失败"
                        McpToolResult.error("UPLOAD_FAILED", err)
                    }
                } catch (e: Exception) {
                    McpToolResult.error("UPLOAD_FAILED", "结果解析失败: ${e.message}")
                }
            },
            f.tool(
                "browser.fullscreen", "切换全屏模式", ToolCategory.BROWSER,
                PermissionScope.READ_PAGE, RiskLevel.LOW,
            ) { _ ->
                McpToolResult.text("全屏模式切换")
            },
            f.tool(
                "browser.source", "获取当前页面 HTML 源码", ToolCategory.BROWSER,
                PermissionScope.READ_DOM, RiskLevel.LOW,
            ) { _ ->
                val session = deps.activeSession()
                val source = session.engine.getPageSource() ?: ""
                McpToolResult.text(source.take(100_000))
            },
            f.tool(
                "browser.wait", "等待页面加载完成", ToolCategory.BROWSER,
                PermissionScope.READ_PAGE, RiskLevel.LOW,
                inputSchema = Schemas.objectSchema("timeoutMs" to Schemas.intSchema("超时毫秒")),
            ) { args ->
                val timeout = ToolArgs.int(args, "timeoutMs", 10_000)
                val session = deps.activeSession()
                var elapsed = 0L
                while (elapsed < timeout) {
                    if (!session.engine.state.value.isLoading) {
                        return@tool McpToolResult.text("页面已就绪")
                    }
                    kotlinx.coroutines.delay(200)
                    elapsed += 200
                }
                McpToolResult.text("等待超时，页面仍在加载")
            },
        )
    }

    /** 上传文件大小上限：50MB（防止一次性 base64 撑爆内存） */
    private const val MAX_UPLOAD_BYTES = 50L * 1024 * 1024

    /**
     * 单张截图的 base64 字符上限（≈9 MB 原始字节）。
     * 超出后用 jpeg(70) 自动重截一次——整页 PNG 动辄几十 MB，直接塞给客户端
     * 会挤爆响应预算（服务端 MAX_TOOL_RESPONSE_CHARS 保护也会先触发）。
     */
    private const val MAX_SCREENSHOT_B64_CHARS = 12_000_000

    /**
     * 文件名无扩展名时，按 MIME 补一个（`browser.download` 的 mimeType 参数实现）。
     * 只补不覆盖：文件名已带扩展名时原样返回，避免破坏 AI 显式指定的名字。
     */
    private fun applyMimeExtension(filename: String, mime: String?): String {
        if (mime == null) return filename
        val base = filename.substringBefore('?')
        if (base.substringAfterLast('/', base).contains('.')) return filename
        val ext = when (mime.substringBefore(';').trim().lowercase()) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/svg+xml" -> "svg"
            "application/pdf" -> "pdf"
            "application/json" -> "json"
            "application/wasm" -> "wasm"
            "application/zip" -> "zip"
            "application/gzip" -> "gz"
            "text/html" -> "html"
            "text/css" -> "css"
            "text/plain" -> "txt"
            "text/javascript", "application/javascript" -> "js"
            "application/xml", "text/xml" -> "xml"
            "audio/mpeg" -> "mp3"
            "audio/mp4" -> "m4a"
            "audio/flac" -> "flac"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            else -> null
        } ?: return filename
        return "$filename.$ext"
    }

    /** 解析 upload path 参数：绝对路径原样；相对路径基于工作目录并强制约束在工作区内 */
    private fun resolveUploadFile(deps: ToolDependencies, rawPath: String): java.io.File {
        val trimmed = rawPath.trim()
        return when {
            trimmed.isEmpty() -> com.webreverse.mcp.core.common.util.WorkDir.directory(deps.browserService.appContext())
            trimmed.startsWith("/") -> java.io.File(trimmed)
            else -> {
                val ws = com.webreverse.mcp.core.common.util.WorkDir.directory(deps.browserService.appContext())
                val wf = java.io.File(ws, trimmed)
                val wsCanon = try { ws.canonicalPath } catch (e: Exception) { ws.path }
                val cf = try { wf.canonicalPath } catch (e: Exception) { wf.absolutePath }
                val inside = cf == wsCanon || cf.startsWith(wsCanon + java.io.File.separatorChar)
                if (!inside) java.io.File("/proc/self/__ws_escape_blocked") else wf
            }
        }
    }

    /** WebView 全页分页渲染为 PDF（A4 纵向比例），返回 (PDF 字节, 页数) */
    private suspend fun renderWebViewToPdf(webView: android.webkit.WebView): Pair<ByteArray, Int>? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            try {
                // 离屏 WebView 可能从未布局过，手动测量保证有有效宽度
                if (webView.width <= 0) {
                    webView.measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.AT_MOST),
                    )
                    webView.layout(0, 0, webView.measuredWidth, webView.measuredHeight)
                }
                val width = webView.width
                val contentHeight = Math.ceil(webView.contentHeight * webView.scale.toDouble()).toInt()
                if (width <= 0 || contentHeight <= 0) return@withContext null

                val pageHeight = width * 1414 / 1000 // A4 纵向比例
                val pdf = android.graphics.pdf.PdfDocument()
                try {
                    var page = 0
                    var offset = 0
                    while (offset < contentHeight && page < 100) { // 上限 100 页防御超长页面
                        val info = android.graphics.pdf.PdfDocument.PageInfo
                            .Builder(width, pageHeight, page + 1).create()
                        val p = pdf.startPage(info)
                        p.canvas.save()
                        p.canvas.translate(0f, -offset.toFloat())
                        webView.draw(p.canvas)
                        p.canvas.restore()
                        pdf.finishPage(p)
                        offset += pageHeight
                        page++
                    }
                    val bos = java.io.ByteArrayOutputStream()
                    pdf.writeTo(bos)
                    Pair(bos.toByteArray(), page)
                } finally {
                    pdf.close()
                }
            } catch (e: Exception) {
                null
            }
        }

    /** 解码 data: URI，返回 (字节, mime)。支持 data:[mime][;base64],payload 两种格式 */
    /**
     * 视口截图 → MCP image 结果。
     * [extra] 非空时作为附加字段并入 structuredContent（用于降级时如实标注原因与补救动作）。
     */
    private fun viewportShotResult(
        bitmap: android.graphics.Bitmap,
        format: String,
        extra: kotlinx.serialization.json.JsonObject?,
    ): McpToolResult {
        val stream = java.io.ByteArrayOutputStream()
        val isJpeg = format == "jpeg"
        bitmap.compress(
            if (isJpeg) android.graphics.Bitmap.CompressFormat.JPEG else android.graphics.Bitmap.CompressFormat.PNG,
            90,
            stream,
        )
        val bytes = stream.toByteArray()
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val mime = if (isJpeg) "image/jpeg" else "image/png"
        val structured = kotlinx.serialization.json.buildJsonObject {
            put("mode", kotlinx.serialization.json.JsonPrimitive("viewport"))
            put("mimeType", kotlinx.serialization.json.JsonPrimitive(mime))
            put("width", kotlinx.serialization.json.JsonPrimitive(bitmap.width))
            put("height", kotlinx.serialization.json.JsonPrimitive(bitmap.height))
            put("bytes", kotlinx.serialization.json.JsonPrimitive(bytes.size))
            extra?.forEach { (k, v) -> put(k, v) }
        }
        return McpToolResult(
            content = listOf(McpContent(type = "image", mimeType = mime, data = base64)),
            structuredContent = structured,
        )
    }

    /**
     * CDP 精确区域截图（fullPage = 整页 / selector = 元素）。
     * 不可用时返回 null（调用方退回视口截图）。
     * 选择器未匹配时返回错误结果（此时不应退回视口——那会让 AI 误以为截图成功）。
     */
    private suspend fun captureRegionViaCdp(
        deps: ToolDependencies,
        engine: BrowserEngine,
        format: String,
        fullPage: Boolean,
        selector: String?,
    ): McpToolResult? {
        val raw = engine.evaluateJavascript(pageMetricsScript(selector)) ?: return null
        val metrics = RuntimeCaptureBridge.parseJsObject(raw) ?: return null
        if (selector != null && metrics["hasMatch"]?.toString() != "true") {
            return McpToolResult.error(
                "SELECTOR_NOT_FOUND",
                "选择器未匹配到元素: $selector。可先用 dom(action=\"query\", selector=\"$selector\") 确认元素存在（或在 iframe 内——需 frame(action=\"list\") 定位）",
            )
        }
        val clip = if (fullPage) {
            val page = metrics["page"] as? kotlinx.serialization.json.JsonObject ?: return null
            val w = page["w"]?.toString()?.toDoubleOrNull() ?: return null
            val h = page["h"]?.toString()?.toDoubleOrNull() ?: return null
            if (w <= 0 || h <= 0) return null
            clipOf(0.0, 0.0, w, h)
        } else {
            val rect = metrics["rect"] as? kotlinx.serialization.json.JsonObject ?: return null
            val x = rect["x"]?.toString()?.toDoubleOrNull() ?: return null
            val y = rect["y"]?.toString()?.toDoubleOrNull() ?: return null
            val w = rect["w"]?.toString()?.toDoubleOrNull() ?: return null
            val h = rect["h"]?.toString()?.toDoubleOrNull() ?: return null
            if (w <= 0 || h <= 0) {
                return McpToolResult.error("ZERO_SIZE_ELEMENT", "元素可见尺寸为 0（可能 display:none / 未渲染）")
            }
            clipOf(x, y, w, h)
        }
        val singlePass = clip["width"]?.toString()?.toDoubleOrNull().let { w ->
            val h = clip["height"]?.toString()?.toDoubleOrNull() ?: 0.0
            (w ?: 0.0) * h
        }
        if (singlePass > 60_000_000) {
            return McpToolResult.error(
                "REGION_TOO_LARGE",
                "目标区域过大（${singlePass.toLong()} 平方 CSS 像素），超出单张截图上限。请用 selector 缩小范围，或分段截取（配合 browser.download 落盘）",
            )
        }
        var res = deps.debuggerManager.cdpCall(engine, "Page.captureScreenshot", shotParams(format, clip)) ?: return null
        var data = (res["data"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return null
        var usedFormat = format
        // 体积防护：整页 PNG 容易几十 MB（base64 再膨胀 1/3），超出 MCP 舒适区时自动换 jpeg 重截一次
        if (data.length > MAX_SCREENSHOT_B64_CHARS) {
            res = deps.debuggerManager.cdpCall(engine, "Page.captureScreenshot", shotParams("jpeg", clip, quality = 70))
            val smaller = (res?.get("data") as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (!smaller.isNullOrBlank()) {
                data = smaller
                usedFormat = "jpeg"
            }
        }
        val structured = kotlinx.serialization.json.buildJsonObject {
            put("mode", kotlinx.serialization.json.JsonPrimitive(if (selector != null) "selector" else "fullPage"))
            put("selector", kotlinx.serialization.json.JsonPrimitive(selector ?: ""))
            put("mimeType", kotlinx.serialization.json.JsonPrimitive("image/$usedFormat"))
            put("bytes", kotlinx.serialization.json.JsonPrimitive(data.length * 3 / 4))
            put("clip", clip)
            if (usedFormat != format) put("downgraded", kotlinx.serialization.json.JsonPrimitive(true))
        }
        return McpToolResult(
            content = listOf(McpContent(type = "image", mimeType = "image/$usedFormat", data = data)),
            structuredContent = structured,
        )
    }

    /** Page.captureScreenshot 参数 */
    private fun shotParams(
        format: String,
        clip: kotlinx.serialization.json.JsonObject,
        quality: Int = 80,
    ): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {
        put("format", kotlinx.serialization.json.JsonPrimitive(format))
        put("captureBeyondViewport", kotlinx.serialization.json.JsonPrimitive(true))
        put("clip", clip)
        if (format == "jpeg") put("quality", kotlinx.serialization.json.JsonPrimitive(quality))
    }

    private fun clipOf(x: Double, y: Double, w: Double, h: Double): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            put("x", kotlinx.serialization.json.JsonPrimitive(x))
            put("y", kotlinx.serialization.json.JsonPrimitive(y))
            put("width", kotlinx.serialization.json.JsonPrimitive(w))
            put("height", kotlinx.serialization.json.JsonPrimitive(h))
            put("scale", kotlinx.serialization.json.JsonPrimitive(1))
        }

    /**
     * 页面几何度量脚本：返回文档尺寸（整页）与目标元素在**文档坐标系**中的矩形。
     * 坐标系说明：CDP captureBeyondViewport 的 clip 用文档坐标（含滚动偏移），
     * 所以这里统一加上 scrollX/scrollY。
     */
    private fun pageMetricsScript(selector: String?): String {
        val q = if (selector == null) "null" else kotlinx.serialization.json.JsonPrimitive(selector).toString()
        return """
            (function(){
              try {
                var q = $q;
                var el = q ? document.querySelector(q) : null;
                if (q && !el) return JSON.stringify({hasMatch:false});
                var r = el ? el.getBoundingClientRect() : null;
                var d = document.documentElement || {};
                var b = document.body || {};
                var sx = window.scrollX || d.scrollLeft || 0;
                var sy = window.scrollY || d.scrollTop || 0;
                var vw = window.innerWidth || d.clientWidth || 0;
                var vh = window.innerHeight || d.clientHeight || 0;
                var pw = Math.max(d.scrollWidth||0, b.scrollWidth||0, vw);
                var ph = Math.max(d.scrollHeight||0, b.scrollHeight||0, vh);
                return JSON.stringify({
                  hasMatch: !!el,
                  rect: r ? {x: r.left + sx, y: r.top + sy, w: r.width, h: r.height} : null,
                  page: {w: pw, h: ph},
                  viewport: {w: vw, h: vh}
                });
              } catch (e) { return JSON.stringify({hasMatch:false, error: String(e)}); }
            })()
        """.trimIndent()
    }

    private fun decodeDataUri(uri: String): Pair<ByteArray, String>? {
        return try {
            val headerEnd = uri.indexOf(',', 5)
            if (headerEnd < 0) return null
            val header = uri.substring(5, headerEnd).lowercase()
            val payload = uri.substring(headerEnd + 1)
            val mime = header.substringBefore(';').ifBlank { "text/plain" }
            val isBase64 = header.contains(";base64")
            val bytes = if (isBase64) {
                android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
            } else {
                java.net.URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
            }
            bytes to mime
        } catch (e: Exception) {
            null
        }
    }

    /* * 下载结果：Ok(文件) / Failed(原因， 起带 HTTP 状态或异常详情，便于 AI 判断回退路径） */
    private sealed interface DownloadOutcome {
        data class Ok(val file: java.io.File) : DownloadOutcome
        data class Failed(val reason: String) : DownloadOutcome
    }

    /** 直接 HTTP 下载到指定文件（IO 线程；失败带原因） */
    private suspend fun downloadToFile(url: String, target: java.io.File): DownloadOutcome =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                target.parentFile?.mkdirs()
                val file = target
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = true
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        return@withContext DownloadOutcome.Failed(
                            "HTTP $code ${connection.responseMessage.orEmpty().trim()}",
                        )
                    }
                    connection.inputStream.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    DownloadOutcome.Ok(file)
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                DownloadOutcome.Failed(
                    (e.message ?: e.javaClass.simpleName).take(140),
                )
            }
        }

    /** 剥离 WebView 对 JS 字符串返回值额外包裹的一层 JSON 引号（标准 JSON 反序列化还原转义） */
    private fun unquoteJsResult(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("\"")) return trimmed
        return try {
            (kotlinx.serialization.json.Json.parseToJsonElement(trimmed)
                as? kotlinx.serialization.json.JsonPrimitive)?.content ?: trimmed
        } catch (e: Exception) {
            trimmed
        }
    }
}

/** Tab Tools：多标签管理 */
object TabTools {

    /**
     * 解析 tabIds 入参：JSON 数组字符串（["a","b"]）或裸 CSV（a,b）都接受；
     * 空串/解析失败返回空列表（tab.group 用）。原实现直接忽略该参数。
     */
    private fun parseTabIds(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        if (text.startsWith("[")) {
            return runCatching {
                (kotlinx.serialization.json.Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
            }.getOrDefault(emptyList())
        }
        return text.split(',').map { it.trim() }.filter { it.isNotBlank() }
    }

    fun all(deps: ToolDependencies): List<McpTool> {
        val f = ToolFactory(deps)
        return listOf(
            f.tool(
                "tab.list", "列出所有标签页", ToolCategory.TAB,
                PermissionScope.READ_PAGE, RiskLevel.LOW,
            ) { _ ->
                val tabs = deps.tabManager.tabs.value
                McpToolResult.json(
                    kotlinx.serialization.json.buildJsonObject {
                        put(
                            "tabs",
                            kotlinx.serialization.json.JsonArray(
                                tabs.map { tab ->
                                    kotlinx.serialization.json.buildJsonObject {
                                        put("id", kotlinx.serialization.json.JsonPrimitive(tab.id))
                                        put("title", kotlinx.serialization.json.JsonPrimitive(tab.title))
                                        put("url", kotlinx.serialization.json.JsonPrimitive(Redactor.redactUrl(tab.url)))
                                        put("active", kotlinx.serialization.json.JsonPrimitive(tab.id == deps.browserService.activeTabId.value))
                                        put("pinned", kotlinx.serialization.json.JsonPrimitive(tab.isPinned))
                                        put("muted", kotlinx.serialization.json.JsonPrimitive(tab.isMuted))
                                    }
                                },
                            ),
                        )
                    },
                )
            },
            f.tool(
                "tab.create", "创建新标签页", ToolCategory.TAB,
                PermissionScope.READ_PAGE, RiskLevel.LOW,
                inputSchema = Schemas.objectSchema("url" to Schemas.strSchema("初始 URL")),
            ) { args ->
                val url = ToolArgs.str(args, "url")
                val session = deps.tabManager.createTab(url)
                McpToolResult.text("已创建标签页: ${session.tabId}")
            },
            f.tool(
                "tab.close", "关闭指定标签页", ToolCategory.TAB,
                PermissionScope.READ_PAGE, RiskLevel.LOW,
                inputSchema = Schemas.objectSchema("tabId" to Schemas.strSchema("标签页 ID")),
            ) { args ->
                val tabId = ToolArgs.optStr(args, "tabId") ?: deps.browserService.activeTabId.value
                if (tabId == null) return@tool McpToolResult.error("NO_ACTIVE_TAB", "没有活动标签页")
                deps.tabManager.closeTab(tabId)
                McpToolResult.text("已关闭标签页: $tabId")
            },
            f.tool(
                "tab.activate", "激活指定标签页", ToolCategory.TAB,
                PermissionScope.READ_PAGE, RiskLevel.LOW,
                inputSchema = Schemas.objectSchema("tabId" to Schemas.strSchema("标签页 ID")),
            ) { args ->
                val tabId = ToolArgs.str(args, "tabId")
                deps.tabManager.activateTab(tabId)
                McpToolResult.text("已激活标签页: $tabId")
            },
            f.tool(
                "tab.reload", "刷新指定标签页", ToolCategory.TAB,
                PermissionScope.READ_PAGE, RiskLevel.LOW,
                inputSchema = Schemas.objectSchema("tabId" to Schemas.strSchema("标签页 ID")),
            ) { args ->
                val session = deps.sessionFor(ToolArgs.optStr(args, "tabId"))
                session.engine.reload()
                McpToolResult.text("已刷新标签页: ${session.tabId}")
            },
            f.tool(
                "tab.mute", "静音/取消静音标签页：写入真实状态位（tab.list 的 muted 字段）并用 JS 让页面内已存在/后续新增的 video/audio 静音（取消时恢复）", ToolCategory.TAB,
                PermissionScope.MODIFY_PAGE, RiskLevel.LOW,
                inputSchema = Schemas.objectSchema("tabId" to Schemas.strSchema("标签页 ID（缺省用当前活动标签页）"), "muted" to Schemas.boolSchema("是否静音（默认 true）")),
            ) { args ->
                val tabId = ToolArgs.optStr(args, "tabId") ?: deps.browserService.activeTabId.value
                    ?: return@tool McpToolResult.error("NO_ACTIVE_TAB", "没有活动标签页")
                val muted = ToolArgs.bool(args, "muted", true)
                val tab = deps.browserService.updateTabFlags(tabId, muted = muted)
                    ?: return@tool McpToolResult.error("TAB_NOT_FOUND", "标签页不存在: $tabId")
                // 状态位之外再做一层真实静音：挂钩 HTMLMediaElement 层面，覆盖懒加载/后续新增的元素
                val script = if (muted) {
                    """
                    (function(){
                      var apply=function(){try{Array.prototype.forEach.call(document.querySelectorAll('video,audio'),function(e){try{e.muted=true;}catch(_){}});}catch(_){}};
                      if(document.documentElement&&!window.__mcpMediaMuteObserver){
                        window.__mcpMediaMuteObserver=new MutationObserver(apply);
                        window.__mcpMediaMuteObserver.observe(document.documentElement,{childList:true,subtree:true});
                      }
                      window.__mcpMediaMuted=true;apply();return 'muted';
                    })()
                    """.trimIndent()
                } else {
                    """
                    (function(){
                      if(window.__mcpMediaMuteObserver){try{window.__mcpMediaMuteObserver.disconnect();}catch(_){}window.__mcpMediaMuteObserver=null;}
                      try{Array.prototype.forEach.call(document.querySelectorAll('video,audio'),function(e){try{e.muted=false;}catch(_){}});}catch(_){}
                      window.__mcpMediaMuted=false;return 'unmuted';
                    })()
                    """.trimIndent()
                }
                val session = deps.tabManager.getSession(tabId)
                val mediaApplied = session?.engine?.evaluateJavascript(script) != null
                McpToolResult.json(
                    kotlinx.serialization.json.buildJsonObject {
                        put("tabId", kotlinx.serialization.json.JsonPrimitive(tab.id))
                        put("muted", kotlinx.serialization.json.JsonPrimitive(tab.isMuted))
                        put("mediaPatched", kotlinx.serialization.json.JsonPrimitive(mediaApplied))
                    },
                )
            },
            f.tool(
                "tab.pin", "固定/取消固定标签页：写入真实状态位（tab.list 的 pinned 字段）；标题栏的固定样式由浏览器 UI 渲染，MCP 侧维护状态", ToolCategory.TAB,
                PermissionScope.READ_PAGE, RiskLevel.LOW,
                inputSchema = Schemas.objectSchema("tabId" to Schemas.strSchema("标签页 ID（缺省用当前活动标签页）"), "pinned" to Schemas.boolSchema("是否固定（默认 true）")),
            ) { args ->
                val tabId = ToolArgs.optStr(args, "tabId") ?: deps.browserService.activeTabId.value
                    ?: return@tool McpToolResult.error("NO_ACTIVE_TAB", "没有活动标签页")
                val pinned = ToolArgs.bool(args, "pinned", true)
                val tab = deps.browserService.updateTabFlags(tabId, pinned = pinned)
                    ?: return@tool McpToolResult.error("TAB_NOT_FOUND", "标签页不存在: $tabId")
                McpToolResult.json(
                    kotlinx.serialization.json.buildJsonObject {
                        put("tabId", kotlinx.serialization.json.JsonPrimitive(tab.id))
                        put("pinned", kotlinx.serialization.json.JsonPrimitive(tab.isPinned))
                    },
                )
            },
            f.tool(
                "tab.pin_session", "把当前 MCP 会话固定到指定标签页：此后该会话内所有工具的 activeSession() 都解析到这个标签页，防止多 Agent/多 Tab 切换时串到别的逆向现场（P0 上下文隔离）", ToolCategory.TAB,
                PermissionScope.CONTROL_MCP, RiskLevel.MEDIUM,
                inputSchema = Schemas.objectSchema(
                    "tabId" to Schemas.strSchema("要固定的标签页 ID（tab.list 获取）"),
                ),
            ) { args ->
                val tabId = ToolArgs.str(args, "tabId")
                if (tabId.isBlank()) return@tool McpToolResult.error("INVALID_ARGS", "tabId 不能为空")
                val ctx = deps.context()
                val sessionId = ctx?.sessionId ?: "default"
                deps.pinTabForSession(sessionId, tabId)
                McpToolResult.json(
                    kotlinx.serialization.json.buildJsonObject {
                        put("sessionId", kotlinx.serialization.json.JsonPrimitive(sessionId))
                        put("pinnedTabId", kotlinx.serialization.json.JsonPrimitive(tabId))
                        put("pinned", kotlinx.serialization.json.JsonPrimitive(true))
                        put("hint", kotlinx.serialization.json.JsonPrimitive("此后该会话所有工具都作用于 tab=$tabId；tab.unpin_session 解除固定"))
                    },
                )
            },
            f.tool(
                "tab.unpin_session", "解除当前会话的标签页固定，恢复跟随活动标签页", ToolCategory.TAB,
                PermissionScope.CONTROL_MCP, RiskLevel.LOW,
            ) { _ ->
                val ctx = deps.context()
                val sessionId = ctx?.sessionId ?: "default"
                val unpinned = deps.unpinTabForSession(sessionId)
                McpToolResult.json(
                    kotlinx.serialization.json.buildJsonObject {
                        put("sessionId", kotlinx.serialization.json.JsonPrimitive(sessionId))
                        put("unpinnedTabId", unpinned?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
                        put("pinned", kotlinx.serialization.json.JsonPrimitive(false))
                    },
                )
            },
            f.tool(
                "tab.move", "移动标签页位置（在标签栏顺序中插到 index 处；index 越界自动收敛到末尾）", ToolCategory.TAB,
                PermissionScope.READ_PAGE, RiskLevel.LOW,
                inputSchema = Schemas.objectSchema("tabId" to Schemas.strSchema("标签页 ID（缺省用当前活动标签页）"), "index" to Schemas.intSchema("目标位置（0 起）")),
            ) { args ->
                val tabId = ToolArgs.optStr(args, "tabId") ?: deps.browserService.activeTabId.value
                    ?: return@tool McpToolResult.error("NO_ACTIVE_TAB", "没有活动标签页")
                val index = ToolArgs.int(args, "index", 0)
                // 修复：原实现只回「标签页已移动」，参数不读、顺序不改（假成功）
                if (!deps.browserService.moveTab(tabId, index)) {
                    return@tool McpToolResult.error("TAB_NOT_FOUND", "标签页不存在: $tabId")
                }
                McpToolResult.json(
                    kotlinx.serialization.json.buildJsonObject {
                        put("tabId", kotlinx.serialization.json.JsonPrimitive(tabId))
                        put("index", kotlinx.serialization.json.JsonPrimitive(index))
                        put(
                            "order",
                            kotlinx.serialization.json.JsonArray(
                                deps.tabManager.tabs.value.map { kotlinx.serialization.json.JsonPrimitive(it.id) },
                            ),
                        )
                    },
                )
            },
            f.tool(
                "tab.group", "创建标签组并把指定标签页加入（tabIds 传 JSON 数组字符串，如 [\"tab1\",\"tab2\"]；留空则只建空组）", ToolCategory.TAB,
                PermissionScope.READ_PAGE, RiskLevel.LOW,
                inputSchema = Schemas.objectSchema("name" to Schemas.strSchema("组名"), "tabIds" to Schemas.strSchema("标签页 ID 列表（JSON 数组字符串）")),
            ) { args ->
                val name = ToolArgs.str(args, "name", "New Group")
                // 修复：原实现完全忽略 tabIds，只建空组（schema 声明了却没用）
                val ids = parseTabIds(ToolArgs.str(args, "tabIds"))
                val group = deps.tabManager.createGroup(name, ids)
                McpToolResult.json(
                    kotlinx.serialization.json.buildJsonObject {
                        put("groupId", kotlinx.serialization.json.JsonPrimitive(group.id))
                        put("name", kotlinx.serialization.json.JsonPrimitive(group.name))
                        put(
                            "tabIds",
                            kotlinx.serialization.json.JsonArray(group.tabIds.map { kotlinx.serialization.json.JsonPrimitive(it) }),
                        )
                    },
                )
            },
            f.tool(
                "tab.duplicate", "复制标签页", ToolCategory.TAB,
                PermissionScope.READ_PAGE, RiskLevel.LOW,
                inputSchema = Schemas.objectSchema("tabId" to Schemas.strSchema("标签页 ID")),
            ) { args ->
                val tabId = ToolArgs.optStr(args, "tabId") ?: deps.browserService.activeTabId.value
                if (tabId == null) return@tool McpToolResult.error("NO_ACTIVE_TAB", "没有活动标签页")
                val session = deps.tabManager.duplicateTab(tabId)
                McpToolResult.text("已复制标签页: ${session?.tabId}")
            },
            f.tool(
                "tab.recently_closed", "列出最近关闭的标签页", ToolCategory.TAB,
                PermissionScope.READ_PAGE, RiskLevel.LOW,
            ) { _ ->
                val closed = deps.tabManager.recentlyClosed.value
                McpToolResult.json(
                    kotlinx.serialization.json.buildJsonObject {
                        put(
                            "recentlyClosed",
                            kotlinx.serialization.json.JsonArray(
                                closed.map { c ->
                                    kotlinx.serialization.json.buildJsonObject {
                                        put("title", kotlinx.serialization.json.JsonPrimitive(c.title))
                                        put("url", kotlinx.serialization.json.JsonPrimitive(c.url))
                                    }
                                },
                            ),
                        )
                    },
                )
            },
            f.tool(
                "tab.restore", "恢复最近关闭的标签页", ToolCategory.TAB,
                PermissionScope.READ_PAGE, RiskLevel.LOW,
            ) { _ ->
                val url = deps.tabManager.restoreRecentlyClosed()
                if (url == null) McpToolResult.text("没有可恢复的标签页")
                else {
                    deps.tabManager.createTab(url)
                    McpToolResult.text("已恢复: $url")
                }
            },
            f.tool(
                "browser.set_stealth",
                "注入反调试对抗层（debugger 剥离 / 屏幕几何自洽 / 时间夹层对抗）：Hook 函数 toString 伪装 [native code]、过滤含 debugger 的定时器/Function 构造回调、剥离 eval/Function/constructor 内的 debugger、屏幕几何自洽固定、统一虚拟时间源对抗时间夹层检测（对抗站点反调试封杀；已在 document_start 自动注入，此工具可用于按需重查状态/手动补注）",
                ToolCategory.BROWSER,
                PermissionScope.MODIFY_PAGE, RiskLevel.MEDIUM,
                inputSchema = Schemas.objectSchema(
                    "status" to Schemas.boolSchema("仅查看当前 stealth 状态（默认 false 即注入）"),
                ),
            ) { args ->
                val session = deps.activeSession()
                if (ToolArgs.bool(args, "status", false)) {
                    val st = session.engine.evaluateJavascript(
                        "(function(){var s=window.__WRMCP_STEALTH__;return s?JSON.stringify({active:true,natives:s.nativeCount,debuggerFiltered:s.debuggerFiltered}):JSON.stringify({active:false})})()",
                    ) ?: "{}"
                    return@tool McpToolResult.text(st)
                }
                session.engine.evaluateJavascript(com.webreverse.mcp.browser.engine.util.JsScripts.stealthScript())
                val st = session.engine.evaluateJavascript(
                    "(function(){var s=window.__WRMCP_STEALTH__;return s?JSON.stringify({active:true,natives:s.nativeCount,debuggerFiltered:s.debuggerFiltered}):JSON.stringify({active:false})})()",
                ) ?: "{}"
                McpToolResult.text("stealth 已注入：$st")
            },
        )
    }
}

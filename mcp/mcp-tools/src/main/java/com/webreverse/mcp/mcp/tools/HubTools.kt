package com.webreverse.mcp.mcp.tools

import com.webreverse.mcp.core.common.permission.PermissionScope
import com.webreverse.mcp.core.common.permission.RiskLevel
import com.webreverse.mcp.core.mcp.McpTool
import com.webreverse.mcp.core.mcp.McpToolResult
import com.webreverse.mcp.core.mcp.ToolCategory
import com.webreverse.mcp.core.mcp.ToolContext
import com.webreverse.mcp.core.mcp.ToolContextScope
import com.webreverse.mcp.core.mcp.ToolMetadata
import com.webreverse.mcp.core.mcp.ToolRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 命名空间聚合工具生成器。
 *
 * 背景：本项目 410+ 个细粒度工具在 tools/list 中一次性涌入客户端，
 * 造成上下文膨胀与选择困难（多数客户端超过 128 个工具即明显劣化）。
 *
 * 方案：按命名空间（名字第一个 `.` 之前的部分）把原工具聚合为 1 个枢纽工具：
 * - 枢纽工具名 = 命名空间本身（如 `debugger`、`network`）
 * - `action` 参数（enum）选择具体动作，值 = 原工具名去掉命名空间前缀
 * - 其余参数原样透传给原工具——原实现零改动，功能零损失
 * - 原工具仍全部注册，get("debugger_set_breakpoint")（及旧点号名 "debugger.set_breakpoint"）
 *   依旧可直调（兼容模式，ToolRegistry.normalizeName 统一归一）
 * - 枢纽返回值附 `nextActions`（同命名空间可继续调用的动作），
 *   引导 AI 完成链式工作流：调用 A → 读结果 nextActions → 调 B → C
 *
 * 对客户端暴露的名称（tools/list / tools/call）统一为 MCP 规范的
 * 下划线形式（`ns_action`，如 `dom_get_tree`）；子枢纽名同样去点号
 * （`debugger_bp` / `debugger_script` / `debugger_runtime`）。
 *
 * 生成完全自动化：新增工具只需保持 `ns.action` 命名，重新注册即自动纳入枢纽。
 */
object HubTools {

    /** 单个 action 简述的截断长度（控制枢纽 description 总体积；token 实测见下） */
    private const val DESC_LINE_MAX = 80

    /**
     * 枢纽 description 里是否内联该 action 的参数名（形如 `[expression, awaitPromise]`）。
     *
     * 背景（token 实测）：枢纽体积 = 客户端**每轮请求**都带的 `tools/list` 的一部分。
     * 原实现把「参数名」同时写进 description 的行尾**和** inputSchema 的合并属性里，
     * 而那份合并属性只是全成员参数的并集（HubTools 注释里也写明"仅提示性"）——
     * 既重复又误导（看不出哪个参数属于哪个 action），实测 40 个枢纽共 87 KB ≈ 36k tokens。
     * 现在：参数名只在 description 行尾出现一次，inputSchema 只留 action/subAction，
     * 完整逐参数 schema 由 `mcp.tool_schema` 按需取（一次一个工具，比整体常驻省百倍）。
     */
    private const val INLINE_PARAM_NAMES = true

    /** 只读权限 scope（用于推导枢纽 permission：含任一写操作则整体按写算） */
    private val READ_ONLY_SCOPES = setOf(
        PermissionScope.READ_PAGE,
        PermissionScope.READ_DOM,
        PermissionScope.READ_NETWORK,
        PermissionScope.READ_STORAGE,
        PermissionScope.READ_COOKIES,
        PermissionScope.READ_HEADERS,
        PermissionScope.SCREENSHOT,
        PermissionScope.READ_WORKSPACE,
        PermissionScope.READ_FILE,
    )

    /** nextActions 提示最多列出的同命名空间动作数 */
    private const val NEXT_ACTIONS_MAX = 8

    /**
     * 成员自带 `action` 参数时，调用方改用这些键传「该成员自己的子动作」。
     *
     * 根因：枢纽用 `action` 当调度键，并把整个 arguments 原样转发给成员；成员若有同名
     * `action` 入参，收到的会是枢纽动作名（如 "create"、"attach_remote"）而不是调用方
     * 想传的子动作 —— 表现为静默降级（`HookAction.valueOf("CREATE")` 抛错回落 LOG）
     * 或直接报错（`dynamic.code_add_rule` 的 action 校验）。带该冲突的成员共 5 个：
     * hook.create / browser.attach_remote / terminal.ndk / terminal.sandbox /
     * dynamic.code_add_rule。按顺序取第一个非空键。
     */
    private val SUB_ACTION_KEYS = listOf("subAction", "memberAction", "_action")

    /** 成员是否自带名为 action 的入参（此类成员的子动作必须走 [SUB_ACTION_KEYS]） */
    private fun hasOwnActionParam(tool: McpTool): Boolean =
        (tool.metadata.inputSchema["properties"] as? JsonObject)?.containsKey("action") == true

    /** 成员自身 action 参数的一句话说明（写进枢纽 description，告诉 AI 该传什么值） */
    private fun ownActionHint(tool: McpTool): String {
        val prop = (tool.metadata.inputSchema["properties"] as? JsonObject)
            ?.get("action") as? JsonObject ?: return "见成员参数说明"
        val enumValues = (prop["enum"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        if (enumValues.isNotEmpty()) return enumValues.joinToString(" / ")
        val desc = (prop["description"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (desc.isBlank()) return "见成员参数说明"
        return if (desc.length > DESC_LINE_MAX) desc.take(DESC_LINE_MAX) + "…" else desc
    }

    /**
     * 大命名空间手动拆分：action 过多（>30）时 AI 选型准确率下降，
     * 按职责拆为子枢纽。key = 原命名空间，value = (子枢纽名, 该枢纽承载的 action 集)，
     * 未列出的 action 归入命名空间同名主枢纽。
     */
    private val SUB_HUBS: Map<String, List<Pair<String, Set<String>>>> = mapOf(
        "debugger" to listOf(
            "debugger_bp" to setOf(
                "set_breakpoint", "set_conditional_breakpoint", "set_dom_breakpoint",
                "set_event_breakpoint", "set_listener_breakpoint", "set_logpoint",
                "set_promise_breakpoint", "set_script_breakpoint", "set_source_breakpoint",
                "set_xhr_breakpoint", "remove_breakpoint", "list_breakpoints",
                "wait_breakpoint", "get_breakable_locations",
            ),
            "debugger_script" to setOf(
                "get_script_source", "list_scripts", "list_source_maps",
                "search_script", "resolve_position",
            ),
            "debugger_runtime" to setOf(
                "runtime_evaluate", "evaluate_on_call_frame", "evaluate_watch",
                "call_on_object", "call_stack", "get_object_properties", "query_objects",
                "locals", "scopes", "set_variable", "restart_frame",
                "watch", "unwatch", "snapshot", "state",
            ),
            // 其余（attach/detach/pause/resume/step/exceptions/cpu_profile/
            // detect_vmp/trace_vmp/get_vmp_trace/diff_vmp_trace/*_hits）归 debugger 主枢纽
        ),
    )

    /** 子枢纽职责标题（写进 description 开头，帮助 AI 理解分工） */
    private val HUB_TITLES: Map<String, String> = mapOf(
        "debugger" to "调试器核心控制与追踪（连接/暂停/单步/CPU 采样/VMP 追踪）",
        "debugger_bp" to "断点管理（行/条件/DOM/事件/监听器/XHR 断点与 logpoint）",
        "debugger_script" to "脚本与源码（脚本列表/源码/源码映射/内容搜索/位置解析）",
        "debugger_runtime" to "运行时求值与对象检查（求值/调用栈/作用域/对象属性/观察点）",
    )

    /** 工具归属的枢纽名：先查手动拆分规则，未命中则归命名空间主枢纽 */
    private fun hubNameFor(toolName: String): String {
        val ns = toolName.substringBefore('.')
        val action = toolName.substringAfter('.', "")
        SUB_HUBS[ns]?.forEach { (hub, actions) ->
            if (action in actions) return hub
        }
        return ns
    }

    /**
     * 扫描注册表中全部非枢纽工具，按枢纽归属（SUB_HUBS 拆分规则 + 命名空间）
     * 聚合生成枢纽并注册。应在所有原始工具注册完成后调用一次。
     */
    fun buildAndRegister(registry: ToolRegistry) {
        val originals = registry.list().filter { !it.metadata.isHub }
        val groups = originals.groupBy { hubNameFor(it.metadata.name) }
        val hubs = groups.map { (hubName, members) -> buildHub(hubName, members, registry) }
        registry.registerAll(hubs)
    }

    private fun buildHub(hubName: String, members: List<McpTool>, registry: ToolRegistry): McpTool {
        // 命名空间从成员名推导（子枢纽成员的原名前缀仍是原命名空间）
        val ns = members.first().metadata.name.substringBefore('.')
        val sorted = members.sortedBy { it.metadata.name }
        val actionNames = sorted.map { it.metadata.name.removePrefix("$ns.") }

        // 自带 action 入参的成员：枢纽调度键与其撞名，调用方须改用 subAction 传子动作
        val subActionMembers = sorted.filter { hasOwnActionParam(it) }

        // ---- 1) description：职责标题 + 全部 action 及各自入参（AI 选型依据） ----
        // 体积纪律（token 实测）：参数名只在这里出现一次（行尾 `[a, b]`），
        // inputSchema 不再重复合并全成员参数（见下方 schema 构建处的说明）。
        val title = HUB_TITLES[hubName]
        val desc = buildString {
            if (title != null) append(title).append('\n')
            append("$hubName 聚合工具（${sorted.size} 个 action）。")
            append("用法：action 选动作，其余参数为该动作入参（键名见各行末尾 [ ]；完整逐参数 schema 用 ")
            append("mcp(action=\"tool_schema\", name=\"<成员名，如 ${sorted.first().metadata.name}>\") 取）。可用 action：\n")
            sorted.forEach { t ->
                val action = t.metadata.name.removePrefix("$ns.")
                val props = (t.metadata.inputSchema["properties"] as? JsonObject)?.keys?.toList().orEmpty()
                val firstLine = t.metadata.description.lineSequence()
                    .firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                val brief = if (firstLine.length > DESC_LINE_MAX) firstLine.take(DESC_LINE_MAX) + "…" else firstLine
                append("- $action: $brief")
                if (INLINE_PARAM_NAMES && props.isNotEmpty()) {
                    // 参数名内联在行尾：比放进 inputSchema 更省（避免与 properties 重复），
                    // 又比完全不写强（AI 不必为知道参数名而多调一次 tool_schema）
                    append(" [").append(props.joinToString(", ")).append(']')
                }
                append('\n')
            }
            if (subActionMembers.isNotEmpty()) {
                append("\n⚠ 下列 action 自身也带一个 action 参数（子动作），枢纽的 action 会把它覆盖掉，")
                append("必须改用 subAction 传；不传则成员用它自己的默认子动作：\n")
                subActionMembers.forEach { t ->
                    val action = t.metadata.name.removePrefix("$ns.")
                    append("- $action: subAction=${ownActionHint(t)}\n")
                }
            }
        }.trimEnd()

        // ---- 2) inputSchema：只暴露调度键（action / subAction） ----
        // 体积纪律（token 实测）：原实现把**全体成员参数的并集**铺进 properties，40 个枢纽
        // 合计 16k tokens，而且那份并集只是"提示性"的（HubTools 原注释亦如此注明）——
        // 它无法表达"哪个参数属于哪个 action"，反而诱导 AI 传错参数。参数名现在内联在
        // description 各 action 行尾；需要逐参数的类型/默认值/枚举时，用
        // mcp(action="tool_schema", name="<成员名>") 按需取（一次一个工具，不常驻）。
        // 转发不受影响：枢纽始终把调用方给的 arguments 原样（按需剔除调度键）交给成员。
        val schema = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "action",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "要执行的动作（全部可选值见工具描述）")
                            put("enum", JsonArray(actionNames.map { JsonPrimitive(it) }))
                        },
                    )
                    // 冲突成员的子动作入口（仅在有成员自带 action 入参时出现）
                    if (subActionMembers.isNotEmpty()) {
                        put(
                            "subAction",
                            buildJsonObject {
                                put("type", JsonPrimitive("string"))
                                put(
                                    "description",
                                    JsonPrimitive(
                                        "成员自身也带 action 参数时，用它传该成员自己的子动作" +
                                            "（成员：${subActionMembers.joinToString("、") { it.metadata.name.removePrefix("$ns.") }}）。" +
                                            "不传则用成员默认值。",
                                    ),
                                )
                            },
                        )
                    }
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("action"))))
        }

        // ---- 3) 元数据聚合：权限取首个写操作、风险取最高、超时取最大 ----
        val firstWrite = sorted.firstOrNull { it.metadata.permission !in READ_ONLY_SCOPES } ?: sorted.first()
        val risk = sorted.maxByOrNull { it.metadata.riskLevel.ordinal }?.metadata?.riskLevel ?: RiskLevel.LOW
        val timeout = sorted.maxOfOrNull { it.metadata.timeoutMs } ?: 60_000L
        val category = sorted.groupingBy { it.metadata.category }.eachCount()
            .maxByOrNull { it.value }?.key ?: ToolCategory.MCP

        return object : McpTool {
            override val metadata = ToolMetadata(
                name = hubName,
                description = desc,
                category = category,
                permission = firstWrite.metadata.permission,
                riskLevel = risk,
                timeoutMs = timeout,
                inputSchema = schema,
                isHub = true,
            )

            override suspend fun execute(arguments: JsonObject): McpToolResult =
                execute(ToolContextScope.unknown(), arguments)

            override suspend fun execute(context: ToolContext, arguments: JsonObject): McpToolResult {
                val action = (arguments["action"] as? JsonPrimitive)?.contentOrNull?.trim()
                if (action.isNullOrBlank()) {
                    return McpToolResult.error(
                        "MISSING_ACTION",
                        "缺少 action 参数。可选 action：${actionNames.joinToString(", ")}",
                    )
                }
                // 原工具名还原（如 set_breakpoint -> debugger.set_breakpoint），转发执行；
                // 权限检查由原工具自身完成（携带真实 ToolContext）
                val member = registry.get("$ns.$action")
                    ?: return McpToolResult.error(
                        "ACTION_NOT_FOUND",
                        "未知 action \"$action\"。可选 action：${actionNames.joinToString(", ")}",
                    )
                // 成员自带 action 入参时（hook.create / browser.attach_remote /
                // terminal.ndk / terminal.sandbox / dynamic.code_add_rule）：剔除枢纽
                // 调度键，成员自己的子动作改由 subAction（或 memberAction/_action）传入。
                // 否则枢纽动作名（"create"/"attach_remote"…）会被成员当成自己的动作，
                // 静默降级或直接报错。
                val memberArgs = if (hasOwnActionParam(member)) {
                    val subAction = SUB_ACTION_KEYS.firstNotNullOfOrNull { key ->
                        (arguments[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    }
                    buildJsonObject {
                        arguments.forEach { (key, value) ->
                            if (key != "action" && key !in SUB_ACTION_KEYS) put(key, value)
                        }
                        if (subAction != null) put("action", JsonPrimitive(subAction))
                    }
                } else {
                    arguments
                }
                val result = member.execute(context, memberArgs)
                return withNextActions(result, actionNames - action)
            }
        }
    }

    /**
     * 链式引导：在结构化返回值中追加 nextActions（同命名空间其他动作），
     * AI 读到即可知下一步可继续调什么。仅在有 structuredContent 时注入，
     * 并同步重镜像 text，保持 content 与 structuredContent 一致。
     */
    private fun withNextActions(result: McpToolResult, others: List<String>): McpToolResult {
        if (others.isEmpty() || result.structuredContent == null) return result
        val sc = result.structuredContent ?: return result
        val suggestions = others.take(NEXT_ACTIONS_MAX)
        val enriched = buildJsonObject {
            sc.forEach { (k, v) -> put(k, v) }
            put("nextActions", JsonArray(suggestions.map { JsonPrimitive(it) }))
        }
        val newContent = result.content.mapIndexed { idx, c ->
            if (idx == 0 && c.text != null) c.copy(text = enriched.toString()) else c
        }
        return result.copy(content = newContent, structuredContent = enriched)
    }
}

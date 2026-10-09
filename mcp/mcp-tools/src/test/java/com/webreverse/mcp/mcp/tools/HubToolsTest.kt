package com.webreverse.mcp.mcp.tools

import com.webreverse.mcp.core.common.permission.PermissionScope
import com.webreverse.mcp.core.mcp.McpTool
import com.webreverse.mcp.core.mcp.McpToolResult
import com.webreverse.mcp.core.mcp.ToolCategory
import com.webreverse.mcp.core.mcp.ToolMetadata
import com.webreverse.mcp.core.mcp.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 枢纽转发回归测试。
 *
 * 守护的不变量：**枢纽的调度键 `action` 绝不能覆盖成员自己的 `action` 入参**。
 * 历史故障：`hook(action="create", ...)` 转发到 `hook.create` 时，成员拿到
 * `action="create"`，`HookAction.valueOf("CREATE")` 抛错回落 LOG —— 想建 block/modify
 * 规则实际建了只记录日志的规则，且调用方看不出任何异常。同类成员还有
 * browser.attach_remote / terminal.ndk / terminal.sandbox / dynamic.code_add_rule。
 */
class HubToolsTest {

    /** 探针工具：把收到的 arguments 原样回吐，便于断言枢纽到底转发了什么 */
    private fun probe(name: String, ownActionParam: Boolean): McpTool = object : McpTool {
        override val metadata = ToolMetadata(
            name = name,
            description = "probe $name",
            category = ToolCategory.HOOK,
            permission = PermissionScope.READ_PAGE,
            inputSchema = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put(
                    "properties",
                    buildJsonObject {
                        if (ownActionParam) {
                            put(
                                "action",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("子动作：log / block / replace"))
                                },
                            )
                        }
                        put("name", buildJsonObject { put("type", JsonPrimitive("string")) })
                    },
                )
            },
        )

        override suspend fun execute(arguments: JsonObject): McpToolResult =
            McpToolResult.json(buildJsonObject { arguments.forEach { (k, v) -> put(k, v) } })
    }

    /** 注册探针 → 生成枢纽，返回 hub（成员名都以 hook. 开头，故枢纽名恒为 hook） */
    private fun hubOf(vararg tools: McpTool): McpTool {
        val registry = ToolRegistry()
        tools.forEach { registry.register(it) }
        HubTools.buildAndRegister(registry)
        return registry.get("hook") ?: error("枢纽未生成")
    }

    @Test
    fun subActionIsForwardedAsMemberOwnAction() = runBlocking {
        val hub = hubOf(probe("hook.create", true), probe("hook.list", false))
        val result = hub.execute(
            buildJsonObject {
                put("action", JsonPrimitive("create"))
                put("subAction", JsonPrimitive("block"))
                put("name", JsonPrimitive("r1"))
            },
        )
        val sc = result.structuredContent!!
        // 成员的 action 拿到的是 subAction 的值，而不是枢纽动作名 "create"
        assertEquals("block", sc["action"]!!.jsonPrimitive.content)
        assertEquals("r1", sc["name"]!!.jsonPrimitive.content)
        // 枢纽私有键不外泄给成员
        assertNull(sc["subAction"])
    }

    @Test
    fun memberActionFallsBackToAbsentWhenSubActionMissing() = runBlocking {
        val hub = hubOf(probe("hook.create", true))
        val sc = hub.execute(buildJsonObject { put("action", JsonPrimitive("create")) })
            .structuredContent!!
        // 未传 subAction：成员应看到"参数缺省"，而不是被塞进枢纽动作名
        assertNull(sc["action"])
    }

    @Test
    fun altKeysAreAccepted() = runBlocking {
        val hub = hubOf(probe("hook.create", true))
        val sc = hub.execute(
            buildJsonObject {
                put("action", JsonPrimitive("create"))
                put("_action", JsonPrimitive("replace"))
            },
        ).structuredContent!!
        assertEquals("replace", sc["action"]!!.jsonPrimitive.content)
        assertNull(sc["_action"])
    }

    @Test
    fun plainMembersStillReceiveHubActionKey() = runBlocking {
        val hub = hubOf(probe("hook.list", false))
        val sc = hub.execute(buildJsonObject { put("action", JsonPrimitive("list")) })
            .structuredContent!!
        // 无冲突成员：转发保持原样（不回归）
        assertEquals("list", sc["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun hubSchemaAdvertisesSubActionOnlyWhenNeeded() {
        val withOwner = hubOf(probe("hook.create", true))
        val props = withOwner.metadata.inputSchema["properties"]!!.jsonObject
        assertTrue(props.containsKey("subAction"))
        // description 里要写清楚该成员自己的子动作取值，AI 才不用猜
        val hubDesc = withOwner.metadata.description
        assertTrue(hubDesc.contains("subAction"))

        val withoutOwner = hubOf(probe("hook.list", false))
        val props2 = withoutOwner.metadata.inputSchema["properties"]!!.jsonObject
        assertFalse(props2.containsKey("subAction"))
    }

    @Test
    fun unknownActionListsAvailableActions() = runBlocking {
        val hub = hubOf(probe("hook.create", true), probe("hook.list", false))
        val r = hub.execute(buildJsonObject { put("action", JsonPrimitive("nope")) })
        assertTrue(r.isError)
        assertTrue(r.errorMessage!!.contains("create"))
        assertTrue(r.errorMessage!!.contains("list"))
    }
}

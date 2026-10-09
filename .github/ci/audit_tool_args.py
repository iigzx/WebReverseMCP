#!/usr/bin/env python3
"""
静态审计：核对每个 MCP 工具的「JSON Schema 声明参数」与「代码实际读取参数」。

用法: python3 .github/ci/audit_tool_args.py <源码根目录>
（不参与 CI 阻断，仅作人工排查清单）

能抓到的两类真问题：
  1. 代码读了但 schema 没声明 -> AI 不知道要传（客户端裁剪/校验报错，功能静默失效）
  2. schema 声明了但代码从不读 -> AI 白传；常见于「工具是桩实现」（如历史 tab.move
     只 return 一句"已移动"，tabIds 声明了却没人用）

已知误报（人工复核时跳过）：
  - 参数读取被封装进辅助函数（如 investigation 系列的 resolveInvestigation(deps, args)、
    StaticTools 的 sourceArg(args)）：chunk 内看不到字面量，会误判成「未读」。
  - 文件末尾的私有辅助函数会被归到「最后一个工具」的 chunk（如 HookAutoGenTools
    的 buildHookScript(args)），出现「未声明就读取」的假象。
"""
import re
import sys
import os

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."

TOOL_START = re.compile(r'f\.tool\(\s*\n?\s*"([a-z0-9_]+\.[a-z0-9_]+)"')
SCHEMA_KEY = re.compile(r'"([A-Za-z_][A-Za-z0-9_]*)"\s*to\s*Schemas\.')
ARG_KEY = re.compile(r'ToolArgs\.(?:str|optStr|int|optInt|long|bool|double|json|optJson|array|obj)\(\s*args\s*,\s*"([A-Za-z_][A-Za-z0-9_]*)"')
ARG_KEY2 = re.compile(r'args\[\s*"([A-Za-z_][A-Za-z0-9_]*)"\s*\]')
ARG_KEY3 = re.compile(r'[A-Za-z_][A-Za-z0-9_]*\(\s*args\s*,\s*"([A-Za-z_][A-Za-z0-9_]*)"')

findings = []
tools = 0

for dirpath, _, files in os.walk(ROOT):
    if "/build/" in dirpath or "/.git" in dirpath:
        continue
    for fn in files:
        if not fn.endswith(".kt"):
            continue
        path = os.path.join(dirpath, fn)
        src = open(path, encoding="utf-8", errors="replace").read()
        starts = [(m.start(), m.group(1)) for m in TOOL_START.finditer(src)]
        if not starts:
            continue
        for i, (pos, name) in enumerate(starts):
            end = starts[i + 1][0] if i + 1 < len(starts) else len(src)
            chunk = src[pos:end]
            declared = set(SCHEMA_KEY.findall(chunk))
            used = set(ARG_KEY.findall(chunk)) | set(ARG_KEY2.findall(chunk)) | set(ARG_KEY3.findall(chunk))
            tools += 1
            unused = sorted(declared - used)
            undeclared = sorted(used - declared)
            if unused or undeclared:
                findings.append((path, name, unused, undeclared))

print(f"扫描工具数: {tools}")
print(f"存在差异的工具数: {len(findings)}\n")
for path, name, unused, undeclared in sorted(findings, key=lambda x: x[1]):
    print(f"### {name}   ({os.path.relpath(path, ROOT)})")
    if undeclared:
        print(f"    [WARN] 代码读取但 schema 未声明: {', '.join(undeclared)}")
    if unused:
        print(f"    [WARN] schema 声明但代码未读: {', '.join(unused)}")

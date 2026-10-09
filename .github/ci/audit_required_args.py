#!/usr/bin/env python3
"""
静态审计（面向 Agent 的「预校验」质量）：找出「代码里显式校验某参数为空就报错，
但 JSON Schema 没把它标进 required」的工具。

为什么这对 AI 重要：客户端/Agent 依据 schema 的 required 做调用前校验与提示。
缺标注时，AI 只能靠"调一次 → 收 INVALID_ARGUMENTS → 补参数 → 再调一次"来试错，
每次都是一个往返（token + 延迟）。标注齐全后这类往返可直接省掉。

判定（保守，宁少报不多报）：
  该工具块里出现 `ToolArgs.str(args, "X")` 且紧随其后（同块内）出现
      if (X.isBlank()) ... McpToolResult.error("INVALID_ARGUMENTS", ...)
  或   if (X.isBlank()) return@tool ... error(
  则 X 视为「事实必填」，若 schema 未声明 required 或未包含 X → 报告。

用法: python3 .github/ci/audit_required_args.py <源码根目录>
"""
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."

TOOL_START = re.compile(r'f\.tool\(\s*\n?\s*"([a-z0-9_]+\.[a-z0-9_]+)"')
STR_ARG = re.compile(r'ToolArgs\.str\(\s*args\s*,\s*"([A-Za-z_][A-Za-z0-9_]*)"')
BLANK_CHECK = re.compile(r'if\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\.isBlank\(\)\s*\)')
REQUIRED_BLOCK = re.compile(r'required\s*=\s*listOf\(([^)]*)\)')

findings = []

for dirpath, _, files in os.walk(ROOT):
    if "/build/" in dirpath or "/.git" in dirpath:
        continue
    for fn in files:
        if not fn.endswith(".kt"):
            continue
        path = os.path.join(dirpath, fn)
        src = open(path, encoding="utf-8", errors="replace").read()
        starts = [(m.start(), m.group(1)) for m in TOOL_START.finditer(src)]
        for i, (pos, name) in enumerate(starts):
            end = starts[i + 1][0] if i + 1 < len(starts) else len(src)
            chunk = src[pos:end]
            strArgs = set(STR_ARG.findall(chunk))
            blankChecked = set(BLANK_CHECK.findall(chunk))
            # 只认「既是 ToolArgs.str 取出的、又被 isBlank 检查」的参数
            mustHave = strArgs & blankChecked
            if not mustHave:
                continue
            declared = set()
            for m in REQUIRED_BLOCK.finditer(chunk):
                declared |= set(re.findall(r'"([A-Za-z_][A-Za-z0-9_]*)"', m.group(1)))
            missing = sorted(mustHave - declared)
            if missing:
                findings.append((path, name, missing))

print(f"缺 required 标注的工具数: {len(findings)}\n")
for path, name, missing in sorted(findings, key=lambda x: x[1]):
    print(f"### {name}   ({os.path.relpath(path, ROOT)})")
    print(f"    应补 required: {', '.join(missing)}")

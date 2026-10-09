#!/usr/bin/env python3
"""
静态审计（Kotlin JSON 符号限定名）：找出「裸用了 JsonPrimitive / buildJsonObject /
JsonArray / JsonObject / JsonNull 但该文件既没 import 也没写全限定名」的地方。

为什么需要：沙箱里没有 Android SDK / gradle / kotlinc，**改完代码无法本地编译**，
只能等 GitHub Actions（一轮 7~8 分钟）。这类"少个限定名"的错误已经连续踩了两次
（BrowserTools.kt 的 buildJsonObject、JsonPrimitive），本脚本把它们在推送前抓出来。

用法: python3 .github/ci/audit_kotlin_json.py <源码根目录>
"""
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
SYMBOLS = ["JsonPrimitive", "buildJsonObject", "JsonArray", "JsonObject", "JsonNull",
           "buildJsonArray", "contentOrNull", "intOrNull", "jsonPrimitive"]
# 裸用：前面不是 '.'（排除全限定名）也不是标识符字符（排除 myJsonPrimitive）
BARE = re.compile(r"(?<![.\w$])(" + "|".join(SYMBOLS) + r")\b")
IMPORT_LINE = re.compile(r"^\s*import\s+kotlinx\.serialization\.json\.(\*|" + "|".join(SYMBOLS) + r")\s*$")

hits = []
scanned = 0
for dirpath, _, files in os.walk(ROOT):
    if "/build/" in dirpath or "/.git" in dirpath:
        continue
    for fn in files:
        if not fn.endswith(".kt"):
            continue
        path = os.path.join(dirpath, fn)
        scanned += 1
        lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
        imported = set()
        wildcard = False
        for ln in lines:
            m = IMPORT_LINE.match(ln)
            if m:
                if m.group(1) == "*":
                    wildcard = True
                else:
                    imported.add(m.group(1))
        if wildcard:
            continue
        for i, ln in enumerate(lines, 1):
            stripped = ln.strip()
            if stripped.startswith("//") or stripped.startswith("*"):
                continue
            # 去掉行内注释与字符串，降低误报
            code = re.sub(r'"(\\.|[^"\\])*"', '""', ln)
            code = re.sub(r"//.*$", "", code)
            for m in BARE.finditer(code):
                sym = m.group(1)
                if sym in imported:
                    continue
                hits.append((os.path.relpath(path, ROOT), i, sym, stripped[:110]))

print(f"扫描 .kt 文件数: {scanned}")
print(f"裸用未导入符号的位置: {len(hits)}\n")
by_file = {}
for rel, line, sym, src in hits:
    by_file.setdefault(rel, []).append((line, sym, src))
for rel in sorted(by_file):
    print(f"### {rel}")
    for line, sym, src in sorted(by_file[rel]):
        print(f"    {line}: {sym}   | {src}")
    print()

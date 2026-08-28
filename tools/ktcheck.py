#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AcideFluorhydrique
# SPDX-License-Identifier: GPL-3.0-or-later
"""
Kotlin 原始碼的快速健檢。

    python3 tools/ktcheck.py

不是編譯器，也不打算變成編譯器。它只抓三類「不必等 Gradle 就能發現」的錯，
而這三類剛好都曾經真的發生過：

1. **未閉合的區塊註解。** Kotlin 的區塊註解**會巢狀**，所以 KDoc 裡只要出現
   一個 `/` 緊接 `*`（例如寫下 `assets/maps/` 加上萬用字元的副檔名），
   就會開啟一層永遠關不掉的註解，接著整個檔案被吃掉，
   錯誤訊息卻出現在別的檔案上（「Unresolved reference」），非常難追。
2. **括號不平衡。**
3. **漏掉的 import。** 專案內宣告的型別被跨套件使用卻沒有 import。

註解與字串是用逐字元掃描剝掉的，不是正則 —— 用正則配對巢狀註解正是
第一條會被漏掉的原因。
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE_DIRS = [
    os.path.join(ROOT, "app", "src", "main", "java"),
    os.path.join(ROOT, "app", "src", "test", "java"),
]


def kotlin_files():
    for base in SOURCE_DIRS:
        for dirpath, _dirnames, filenames in os.walk(base):
            for name in sorted(filenames):
                if name.endswith(".kt"):
                    yield os.path.join(dirpath, name)


def strip(text):
    """
    回傳 (去掉註解與字串的程式碼, 結尾殘留的註解深度)。

    逐字元走過去，同時追蹤：巢狀區塊註解、行註解、一般字串、原始字串、
    字元字面量，以及反引號識別字。被剝掉的內容用空白取代，行號才不會跑掉。
    """
    out = []
    i, depth, n = 0, 0, len(text)
    in_line = in_string = in_char = in_raw = in_backtick = False
    while i < n:
        ch = text[i]
        two = text[i:i + 2]
        three = text[i:i + 3]
        if depth > 0:
            if two == "/*":
                depth += 1
                out.append("  "); i += 2; continue
            if two == "*/":
                depth -= 1
                out.append("  "); i += 2; continue
            out.append("\n" if ch == "\n" else " "); i += 1; continue
        if in_line:
            if ch == "\n":
                in_line = False
                out.append("\n")
            else:
                out.append(" ")
            i += 1; continue
        if in_raw:
            if three == '"""':
                in_raw = False
                out.append("   "); i += 3; continue
            out.append("\n" if ch == "\n" else " "); i += 1; continue
        if in_string:
            if ch == "\\":
                out.append("  "); i += 2; continue
            if ch == '"':
                in_string = False
            out.append(" "); i += 1; continue
        if in_char:
            if ch == "\\":
                out.append("  "); i += 2; continue
            if ch == "'":
                in_char = False
            out.append(" "); i += 1; continue
        if in_backtick:
            # 反引號識別字裡什麼字元都可能出現，包含單引號與空白。
            if ch == "`":
                in_backtick = False
            out.append("_" if ch != "\n" else "\n"); i += 1; continue

        if ch == "`":
            in_backtick = True
            out.append("_"); i += 1; continue
        if three == '"""':
            in_raw = True
            out.append("   "); i += 3; continue
        if two == "//":
            in_line = True
            out.append("  "); i += 2; continue
        if two == "/*":
            depth += 1
            out.append("  "); i += 2; continue
        if ch == '"':
            in_string = True
            out.append(" "); i += 1; continue
        if ch == "'":
            in_char = True
            out.append(" "); i += 1; continue
        out.append(ch); i += 1
    return "".join(out), depth


DECL = re.compile(
    r"^(?:@\w+\s+)*(?:public |internal |private |abstract |open |sealed |data |value )*"
    r"(class|object|interface|enum class)\s+(\w+)", re.M
)


def main():
    files = list(kotlin_files())
    if not files:
        print("找不到任何 .kt，路徑對嗎？")
        return 1

    stripped = {}
    declarations = {}
    problems = []

    for path in files:
        text = open(path, encoding="utf-8").read()
        code, depth = strip(text)
        stripped[path] = (text, code)
        if depth != 0:
            problems.append(
                f"{rel(path)}: 區塊註解沒有關閉（結尾深度 {depth}）—— "
                f"檢查 KDoc 裡是不是有 '/' 緊接 '*'，Kotlin 的區塊註解會巢狀"
            )
        if code.count("{") != code.count("}"):
            problems.append(f"{rel(path)}: 大括號不平衡 {code.count('{')} vs {code.count('}')}")
        if code.count("(") != code.count(")"):
            problems.append(f"{rel(path)}: 小括號不平衡 {code.count('(')} vs {code.count(')')}")
        if "app/src/main/java" in path:
            package = re.search(r"^package\s+([\w.]+)", code, re.M)
            if package:
                for m in DECL.finditer(code):
                    declarations.setdefault(m.group(2), set()).add(package.group(1))

    for path, (text, code) in stripped.items():
        package = re.search(r"^package\s+([\w.]+)", code, re.M)
        if not package:
            continue
        package = package.group(1)
        imported = {i.rsplit(".", 1)[-1] for i in re.findall(r"^import\s+([\w.]+)", code, re.M)}
        body = re.sub(r"^(package|import)\s+.*$", "", code, flags=re.M)
        for name in sorted(set(re.findall(r"\b([A-Z]\w+)\b", body))):
            packages = declarations.get(name)
            if not packages or package in packages or name in imported:
                continue
            if any(f"{p}.{name}" in code for p in packages):
                continue
            problems.append(
                f"{rel(path)}: 用到 {name} 但沒有 import（宣告於 {', '.join(sorted(packages))}）"
            )

    if problems:
        for p in problems:
            print("  " + p)
        print(f"\n{len(problems)} 個問題。")
        return 1

    lines = sum(len(open(f, encoding='utf-8').read().splitlines()) for f in files)
    print(f"ktcheck: {len(files)} 個檔案、{lines} 行，註解閉合、括號平衡、import 齊全。")
    return 0


def rel(path):
    return os.path.relpath(path, ROOT)


if __name__ == "__main__":
    sys.exit(main())

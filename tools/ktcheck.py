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
4. **英美拼法對不上的成員名。** 宣告寫 `defenseBonus`、呼叫端寫 `defenceBonus`，
   在一份混用 `Colors`（Android API）與 `colour`（自己的欄位）的程式碼裡
   非常容易發生，而且只會在編譯時才炸出來。
5. **覆寫的參數個數對不上。** 改了介面方法的簽名，卻漏掉某個實作 ——
   測試裡的匿名物件特別容易被忘記，因為它們不在主程式的搜尋結果裡。

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

MEMBER_DECL = re.compile(r"\b(?:fun|val|var)\s+(?:<[^>]*>\s+)?(\w+)")
MEMBER_USE = re.compile(r"\.(\w+)")
FUN_DECL = re.compile(r"\b(override\s+)?fun\s+(?:<[^>]*>\s+)?(\w+)\s*\(")


def count_params(code, open_paren):
    """數一個函式宣告的參數個數。逐字元配對括號，巢狀的泛型與預設值才不會算錯。"""
    depth = 0
    i = open_paren
    commas = 0
    seen = False
    while i < len(code):
        ch = code[i]
        if ch in "([<{":
            depth += 1
        elif ch in ")]>}":
            depth -= 1
            if depth == 0:
                break
        elif ch == "," and depth == 1:
            commas += 1
        elif depth == 1 and not ch.isspace():
            seen = True
        i += 1
    return (commas + 1) if seen else 0

# 英式／美式拼法的對照。左右兩邊都會互換一次，所以只要列一個方向。
SPELLINGS = [
    ("defense", "defence"), ("offense", "offence"), ("armor", "armour"),
    ("color", "colour"), ("center", "centre"), ("neighbor", "neighbour"),
    ("behavior", "behaviour"), ("harbor", "harbour"), ("gray", "grey"),
    ("license", "licence"), ("meter", "metre"), ("fiber", "fibre"),
]


def spelling_variants(name):
    """回傳這個名字所有英美拼法的替換結果（不含自己）。"""
    out = set()
    lowered = name
    for a, b in SPELLINGS:
        for src, dst in ((a, b), (b, a)):
            for cased_src, cased_dst in (
                (src, dst),
                (src.capitalize(), dst.capitalize()),
                (src.upper(), dst.upper()),
            ):
                if cased_src in lowered:
                    out.add(lowered.replace(cased_src, cased_dst))
    out.discard(name)
    return out


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

    # 專案裡宣告過的所有成員名。跨檔案收集，因為呼叫端與宣告端本來就不同檔。
    declared_members = set()
    for path, (_text, code) in stripped.items():
        declared_members.update(MEMBER_DECL.findall(code))
        # 主建構式的參數也是屬性，正則抓 `val x: T` 已涵蓋。
    for path, (_text, code) in stripped.items():
        for line_no, line in enumerate(code.splitlines(), 1):
            for used in MEMBER_USE.findall(line):
                if used in declared_members:
                    continue
                # 只看駝峰式的複合名字。像 paint.color 這種單字成員多半是
                # Android 或標準函式庫的（Paint.color 就是美式拼法），
                # 而專案自己的欄位幾乎都是 defenceBonus 這種複合詞。
                if len(used) < 6 or used[1:].islower():
                    continue
                hits = spelling_variants(used) & declared_members
                if hits:
                    problems.append(
                        f"{rel(path)}:{line_no}: 用了 .{used}，但專案裡宣告的是 "
                        f"{' / '.join(sorted(hits))} —— 英美拼法對不上"
                    )

    # override 的參數個數必須對得上專案裡某個同名宣告。
    # 只在「這個名字專案裡有宣告」時才檢查 —— 平台方法（onCreate、draw…）
    # 找不到宣告，那是正常的，不該報。
    declared_arities = {}
    for _path, (_text, code) in stripped.items():
        for m in FUN_DECL.finditer(code):
            if m.group(1):
                continue
            declared_arities.setdefault(m.group(2), set()).add(count_params(code, m.end() - 1))
    for path, (_text, code) in stripped.items():
        for m in FUN_DECL.finditer(code):
            if not m.group(1):
                continue
            name = m.group(2)
            arities = declared_arities.get(name)
            if not arities:
                continue
            arity = count_params(code, m.end() - 1)
            if arity not in arities:
                line = code[:m.start()].count("\n") + 1
                problems.append(
                    f"{rel(path)}:{line}: override fun {name} 有 {arity} 個參數，"
                    f"但專案裡宣告的是 {sorted(arities)} 個 —— 改簽名時漏掉了這個實作？"
                )

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

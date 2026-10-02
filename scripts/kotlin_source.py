# Copyright (c) 2026 nesfeder
# SPDX-License-Identifier: MIT
"""Reading Kotlin source in the checks under scripts/: what is code, and what is prose or data."""

import re
from typing import List


def strip_comments_and_literals(source: str) -> str:
    """The code of a Kotlin file with every comment and literal blanked, newlines kept.

    A string template's `${...}` stays as code, since it runs; `$name` is a plain reference.
    """
    out: List[str] = []
    index, length = 0, len(source)
    # Each entry is the closing delimiter of an open string, or "}" for an open template; the
    # brace depth of each template counts its own nested braces.
    stack: List[str] = []
    depths: List[int] = []

    def blank(text: str) -> str:
        return "".join("\n" if ch == "\n" else " " for ch in text)

    while index < length:
        in_string = bool(stack) and stack[-1] != "}"
        if in_string:
            closing = stack[-1]
            if closing == '"' and source[index] == "\\":
                out.append(blank(source[index : index + 2]))
                index += 2
                continue
            if source.startswith(closing, index):
                end = index + len(closing)
                # A raw string ends at the last of a run of quotes.
                while closing == '"""' and end < length and source[end] == '"':
                    end += 1
                out.append(blank(source[index:end]))
                index = end
                stack.pop()
                continue
            if source.startswith("${", index):
                out.append("  ")
                index += 2
                stack.append("}")
                depths.append(0)
                continue
            out.append(blank(source[index]))
            index += 1
            continue

        # Code, at the top level or inside a template.
        if source.startswith("//", index):
            end = source.find("\n", index)
            end = length if end == -1 else end
            out.append(blank(source[index:end]))
            index = end
            continue
        if source.startswith("/*", index):
            depth, end = 1, index + 2
            while end < length and depth:
                if source.startswith("/*", end):
                    depth, end = depth + 1, end + 2
                elif source.startswith("*/", end):
                    depth, end = depth - 1, end + 2
                else:
                    end += 1
            out.append(blank(source[index:end]))
            index = end
            continue
        if source.startswith('"""', index):
            out.append("   ")
            index += 3
            stack.append('"""')
            continue
        if source[index] == '"':
            out.append(" ")
            index += 1
            stack.append('"')
            continue
        if source[index] == "'":
            match = re.match(r"'(?:\\.|\\u[0-9a-fA-F]{4}|[^'\\])'", source[index:])
            if match:
                out.append(blank(match.group(0)))
                index += len(match.group(0))
                continue
        if source[index] == "`":
            end = source.find("`", index + 1)
            end = length if end == -1 else end + 1
            out.append(blank(source[index:end]))
            index = end
            continue
        if stack and stack[-1] == "}":
            if source[index] == "{":
                depths[-1] += 1
            elif source[index] == "}":
                if depths[-1] == 0:
                    stack.pop()
                    depths.pop()
                    out.append(" ")
                    index += 1
                    continue
                depths[-1] -= 1
        out.append(source[index])
        index += 1
    return "".join(out)

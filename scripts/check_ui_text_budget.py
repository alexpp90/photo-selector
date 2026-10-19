#!/usr/bin/env python3
"""check_ui_text_budget.py — visible UI text stays within its budget (docs/shared/UI_TEXT.md).

For each product in scripts/ui_text_budget.json it reads:

* the string resources (res/values*/strings.xml, <string> and <plurals>). The key prefix names
  the element (action_, chip_, title_, dialog_body_, …). Each string is checked against that
  element's word and character budget, EN against `en`, values-de against `de`;
* the Kotlin sources, for text the resources cannot see: hard-coded user-visible literals
  (Compose Text("…"), label = "…", title = "…", …), and buttons or bar items that show a
  visible label next to an icon.

Findings: over-words, over-chars, sentence-punctuation, icon-with-label, hardcoded,
unclassified-key, missing-de. A product in `fail` mode fails the gate on any finding. A product in
`warn` mode only reports.

Usage:
    python3 scripts/check_ui_text_budget.py                    # all products, summary
    python3 scripts/check_ui_text_budget.py --product phototok --verbose
    python3 scripts/check_ui_text_budget.py --inventory md     # every visible string, as a table
    python3 scripts/check_ui_text_budget.py --json out.json
    python3 scripts/check_ui_text_budget.py --mode fail        # treat every product as fail mode

A literal that is not user-visible (a file name, a MIME type) can carry `// ui-text: ignore`
on its line or the line above.

Exit codes: 0 = clean (or only warn-mode findings); 1 = a fail-mode product has findings;
--warn-exit-code N returns N when only warn-mode findings exist (run_tests.sh uses it to show
an advisory "!" line).
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONFIG = Path(__file__).resolve().with_name("ui_text_budget.json")

WORD = re.compile(r"[^\W\d_]+(?:['’-][^\W\d_]+)*", re.UNICODE)
FORMAT_SPEC = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z%])")
SENTENCE_END = re.compile(r"[.!?:]\s*$")
IGNORE_MARK = "ui-text: ignore"

# Compose calls whose literal (first positional or named text argument) is shown to the user.
VISIBLE_CALLS = ("Text", "BasicText", "ClickableText")
# Named arguments that carry visible text.
VISIBLE_ARGS = (
    "text", "label", "title", "subtitle", "description", "message", "headline", "body",
    "actionLabel", "confirmLabel", "dismissLabel", "placeholder", "supportingText", "caption",
)
# Named arguments that are spoken, not shown (TalkBack). Hard-coded, but outside the budget.
SPOKEN_ARGS = ("contentDescription", "onClickLabel", "onLongClickLabel", "stateDescription")
# A literal in one of these calls is never user-visible text.
NON_UI_CALLS = (
    "testTag", "Log.d", "Log.i", "Log.w", "Log.e", "Log.v", "require", "requireNotNull", "check",
    "checkNotNull", "error", "Regex", "Uri.parse", "TODO", "println", "putString", "getString",
    "stringPreferencesKey", "booleanPreferencesKey", "intPreferencesKey", "longPreferencesKey",
    "floatPreferencesKey", "stringSetPreferencesKey", "endsWith", "startsWith", "contains",
    "removeSuffix", "removePrefix", "substringAfter", "substringBefore", "split", "Exception",
    "IllegalStateException", "IllegalArgumentException", "IOException", "Throwable",
    "SecurityException", "UnsupportedOperationException", "Suppress", "OptIn", "Requirement",
    "Intent", "setType", "createChooser", "DateTimeFormatter.ofPattern", "SimpleDateFormat",
    "format", "trace", "key", "File", "DocumentsContract", "query", "Pattern.compile",
)
# Containers in which an Icon next to a Text is a labelled icon control.
BUTTON_CALLS = (
    "Button", "TextButton", "OutlinedButton", "FilledTonalButton", "ElevatedButton", "IconButton",
    "FilledIconButton", "OutlinedIconButton", "FilledTonalIconButton", "NavigationBarItem",
    "NavigationRailItem", "FloatingActionButton", "SmallFloatingActionButton",
)
CLICKABLE = re.compile(r"\b(clickable|combinedClickable|selectable|toggleable)\s*[({]")
COUNT_HINT = re.compile(r"(count|size|total|index|number|num|\bn\b|page|percent|bytes|mb|gb)", re.I)


@dataclass
class Finding:
    product: str
    kind: str
    where: str
    text: str
    element: str = ""
    detail: str = ""


@dataclass
class Entry:
    """One visible string: a resource or a hard-coded literal."""
    product: str
    where: str
    element: str
    en: str = ""
    de: str = ""
    source: str = "resource"  # resource | hardcoded

    @property
    def words(self) -> int:
        return count_words(self.en)


@dataclass
class Result:
    findings: list[Finding] = field(default_factory=list)
    entries: list[Entry] = field(default_factory=list)


def count_words(text: str) -> int:
    return len(WORD.findall(fill_placeholders(text, {"number": "12", "text": "x"}, words_only=True)))


def fill_placeholders(text: str, sample: dict, words_only: bool = False) -> str:
    """Replace %d/%1$s and Kotlin ${…}/$name templates with a typical value."""
    def fmt(m: re.Match) -> str:
        conv = m.group(1)
        if conv == "%":
            return "%"
        if conv in "dfxXoeEgG":
            return sample["number"]
        return "" if words_only else sample["text"]

    out = FORMAT_SPEC.sub(fmt, text)

    def tmpl(m: re.Match) -> str:
        expr = m.group(1) or m.group(2) or ""
        if COUNT_HINT.search(expr):
            return sample["number"]
        return "" if words_only else sample["text"]

    out = re.sub(r"\$\{([^{}]*(?:\{[^{}]*\}[^{}]*)*)\}|\$([A-Za-z_][A-Za-z0-9_.]*)", tmpl, out)
    return out


def load_config(path: Path = CONFIG) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def element_for_key(key: str, budgets: dict) -> str | None:
    best: tuple[int, str] | None = None
    for name, b in budgets.items():
        p = b["prefix"]
        if key.startswith(p) and (best is None or len(p) > best[0]):
            best = (len(p), name)
    return best[1] if best else None


def _unescape_resource(s: str) -> str:
    s = s.strip()
    if len(s) >= 2 and s[0] == s[-1] == '"':
        s = s[1:-1]
    return s.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n").replace("\\@", "@")


def read_strings(path: Path) -> dict[str, str]:
    """name -> text; plurals as name#quantity."""
    if not path.is_file():
        return {}
    out: dict[str, str] = {}
    root = ET.parse(path).getroot()
    for el in root:
        name = el.get("name")
        if not name or el.get("translatable") == "false":
            continue
        if el.tag == "string":
            out[name] = _unescape_resource("".join(el.itertext()))
        elif el.tag == "plurals":
            for item in el.findall("item"):
                out[f"{name}#{item.get('quantity')}"] = _unescape_resource("".join(item.itertext()))
    return out


def check_text(product: str, where: str, element: str, text: str, lang: str, cfg: dict,
               res: Result) -> None:
    b = cfg["budgets"][element]
    if b.get("words") is None:
        return
    words = count_words(text)
    chars = len(fill_placeholders(text, cfg["placeholder_sample"]).strip())
    if words > b["words"]:
        res.findings.append(Finding(product, "over-words", where, text, element,
                                    f"{lang}: {words} words > {b['words']}"))
    limit = b[lang]
    if chars > limit:
        res.findings.append(Finding(product, "over-chars", where, text, element,
                                    f"{lang}: {chars} chars > {limit}"))
    if element in cfg["no_sentence_punctuation"] and SENTENCE_END.search(text):
        res.findings.append(Finding(product, "sentence-punctuation", where, text, element,
                                    f"{lang}: ends with sentence punctuation"))


def check_resources(product: str, res_dir: Path, cfg: dict, res: Result, root: Path) -> None:
    en = read_strings(res_dir / "values" / "strings.xml")
    de = read_strings(res_dir / "values-de" / "strings.xml")
    exempt = set(cfg.get("exempt_keys", []))
    rel = lambda p: str(p.relative_to(root)) if p.is_relative_to(root) else str(p)  # noqa: E731
    for key, text in en.items():
        base = key.split("#", 1)[0]
        if base in exempt:
            continue
        element = element_for_key(base, cfg["budgets"])
        where = f"{rel(res_dir / 'values' / 'strings.xml')}:{key}"
        if element is None:
            res.findings.append(Finding(product, "unclassified-key", where, text, "",
                                        "key has no element prefix (docs/shared/UI_TEXT.md)"))
            continue
        res.entries.append(Entry(product, key, element, text, de.get(key, ""), "resource"))
        check_text(product, where, element, text, "en", cfg, res)
        if element == "spoken":
            continue
        if key in de:
            check_text(product, f"{rel(res_dir / 'values-de' / 'strings.xml')}:{key}", element,
                       de[key], "de", cfg, res)
        else:
            res.findings.append(Finding(product, "missing-de", where, text, element,
                                        "no values-de translation"))


# ── Kotlin scanning ──────────────────────────────────────────────────────────────────────

@dataclass
class Literal:
    start: int
    end: int
    line: int
    text: str


def kotlin_literals(src: str) -> list[Literal]:
    """String literals ("…", not raw \"\"\"…\"\"\") with positions; comments skipped."""
    out: list[Literal] = []
    i, n, line = 0, len(src), 1
    while i < n:
        c = src[i]
        if c == "\n":
            line += 1
            i += 1
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            line += src.count("\n", i, j)
            i = j
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            line += src.count("\n", i, j)
            i = j
        elif c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == "\\" else 1
            i = j + 1
        elif c == '"':
            start, j, depth = i, i + 1, 0
            buf = []
            while j < n:
                ch = src[j]
                if depth == 0 and ch == "\\" and j + 1 < n:
                    buf.append(src[j:j + 2])
                    j += 2
                    continue
                if depth == 0 and ch == '"':
                    break
                if ch == "\n" and depth == 0:
                    break
                if src.startswith("${", j):
                    depth += 1
                    buf.append("${")
                    j += 2
                    continue
                if ch == "}" and depth > 0:
                    depth -= 1
                elif ch == "{" and depth > 0:
                    depth += 1
                buf.append(ch)
                j += 1
            out.append(Literal(start, j + 1, line, decode_escapes("".join(buf))))
            i = j + 1
        else:
            i += 1
    return out


def decode_escapes(raw: str) -> str:
    def esc(m: re.Match) -> str:
        e = m.group(1)
        if e.startswith("u"):
            return chr(int(e[1:], 16))
        return {"n": "\n", "t": "\t", "r": "\r", "b": "\b"}.get(e, e)

    return re.sub(r"\\(u[0-9a-fA-F]{4}|.)", esc, raw)


def _call_before(src: str, pos: int) -> tuple[str, str]:
    """(innermost open call name, named argument) enclosing position `pos`."""
    depth = 0
    i = pos - 1
    arg = ""
    # Named argument directly before the literal: `name = "…"`
    m = re.search(r"([A-Za-z_][A-Za-z0-9_]*)\s*=\s*$", src[max(0, pos - 80):pos])
    if m:
        arg = m.group(1)
    while i >= 0:
        ch = src[i]
        if ch in ")]}":
            depth += 1
        elif ch in "([{":
            if depth == 0:
                if ch == "(":
                    m = re.search(r"([A-Za-z_][A-Za-z0-9_.]*)\s*$", src[max(0, i - 80):i])
                    return (m.group(1) if m else "", arg)
                if ch == "{":
                    return (_lambda_owner(src, i), arg)
                return ("", arg)
            depth -= 1
        i -= 1
    return ("", arg)


def _lambda_owner(src: str, brace: int) -> str:
    """Name of the call a trailing lambda `{` belongs to (`require(x) {`, `check {`), or "{"."""
    j = brace - 1
    while j >= 0 and src[j] in " \t\n":
        j -= 1
    if j >= 0 and src[j] == ")":
        depth = 0
        while j >= 0:
            if src[j] == ")":
                depth += 1
            elif src[j] == "(":
                depth -= 1
                if depth == 0:
                    break
            j -= 1
    m = re.search(r"([A-Za-z_][A-Za-z0-9_.]*)\s*$", src[max(0, j - 80):max(j, 0)])
    return m.group(1) if m else "{"


def looks_like_prose(text: str) -> bool:
    plain = fill_placeholders(text, {"number": "1", "text": ""}, words_only=True).strip()
    words = WORD.findall(plain)
    if not words:
        return False
    if re.fullmatch(r"[a-z0-9_.\-/:]+", plain):  # identifiers, keys, paths
        return False
    if " " not in plain or not any(len(w) >= 3 for w in words):
        return False  # one token (a folder name, a separator) is not prose
    return plain[:1].isupper() or plain[:1].isdigit() or any(w[:1].isupper() for w in words)


def classify_literal(src: str, lit: Literal) -> str | None:
    """'visible', 'spoken' or None (not user-facing)."""
    line_start = src.rfind("\n", 0, lit.start) + 1
    prev_start = src.rfind("\n", 0, max(0, line_start - 1)) + 1
    if IGNORE_MARK in src[prev_start:src.find("\n", lit.end) if src.find("\n", lit.end) >= 0 else None]:
        return None
    line = src[line_start:src.find("\n", lit.start) if src.find("\n", lit.start) >= 0 else None]
    if re.match(r"\s*(import|package)\b", line) or line.lstrip().startswith("@"):
        return None
    if not WORD.search(lit.text):
        return None
    call, arg = _call_before(src, lit.start)
    short = call.split(".")[-1]
    if arg in SPOKEN_ARGS:
        return "spoken"
    if any(call == c or call.endswith("." + c) or short == c for c in NON_UI_CALLS):
        return None
    if re.search(r"\bthrow\b", line) or re.search(r"\b(const\s+)?val\s+[A-Z0-9_]*(KEY|TAG|PREF|MIME|EXT)", line):
        return None
    if arg in VISIBLE_ARGS or short in VISIBLE_CALLS:
        return "visible"
    if arg and arg not in VISIBLE_ARGS:
        # A named argument we do not know (e.g. `mimeType = "image/*"`): visible only if prose.
        return "visible" if looks_like_prose(lit.text) else None
    return "visible" if looks_like_prose(lit.text) else None


def _span(src: str, open_paren: int) -> int:
    """End index of the call starting at `open_paren`, trailing lambda included."""
    depth, i, n = 0, open_paren, len(src)
    while i < n:
        ch = src[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
            if depth == 0:
                j = i + 1
                while j < n and src[j] in " \t":
                    j += 1
                if j < n and src[j] == "{" and ch == ")":
                    i = j
                    continue
                return i + 1
        elif ch == '"':
            j = i + 1
            while j < n and src[j] not in '"\n':
                j += 2 if src[j] == "\\" else 1
            i = j
        i += 1
    return n


def _strip_strings(code: str) -> str:
    return re.sub(r'"(?:[^"\\\n]|\\.)*"', '""', code)


def icon_with_label_spans(src: str) -> list[tuple[int, str]]:
    """(line, container) for buttons / clickable icon-over-label items showing Icon + Text."""
    out = []
    code = _strip_strings(src)
    names = "|".join(BUTTON_CALLS)
    for m in re.finditer(rf"(?<![A-Za-z0-9_.])({names}|Column)\s*\(", code):
        name = m.group(1)
        open_paren = m.end() - 1
        end = _span(code, open_paren)
        body = code[open_paren:end]
        if name == "Column":
            # An icon stacked over its label: the bar-item / micro-label pattern.
            head = body[: body.find("{") if "{" in body else 200]
            if "CenterHorizontally" not in head and not CLICKABLE.search(head):
                continue
            if len(re.findall(r"(?<![A-Za-z0-9_.])(Icon|Image)\s*\(", body)) != 1:
                continue
            if body.count("\n") > 25:
                continue
            if re.search(rf"(?<![A-Za-z0-9_.])({names})\s*\(", body):
                continue  # a container of buttons, not one bar item
        if re.search(r"(?<![A-Za-z0-9_.])Icon\s*\(", body) and re.search(r"(?<![A-Za-z0-9_.])Text\s*\(", body):
            if body.count("\n") > 40:
                continue  # a large layout, not one control
            out.append((code.count("\n", 0, m.start()) + 1, name))
    return out


def check_sources(product: str, src_dir: Path, cfg: dict, res: Result, root: Path) -> None:
    if not src_dir.is_dir():
        return
    for path in sorted(src_dir.rglob("*.kt")):
        src = path.read_text(encoding="utf-8")
        rel = str(path.relative_to(root)) if path.is_relative_to(root) else str(path)
        for lit in kotlin_literals(src):
            kind = classify_literal(src, lit)
            if kind is None:
                continue
            where = f"{rel}:{lit.line}"
            res.entries.append(Entry(product, where, "spoken" if kind == "spoken" else "unknown",
                                     lit.text, "", "hardcoded"))
            res.findings.append(Finding(product, "hardcoded", where, lit.text,
                                        "spoken" if kind == "spoken" else "",
                                        "user-facing literal; move it to res/values/strings.xml"))
        for line, container in icon_with_label_spans(src):
            res.findings.append(Finding(product, "icon-with-label", f"{rel}:{line}", "", container,
                                        f"{container} shows an Icon and a visible Text"))


def run(cfg: dict, root: Path, products: list[str] | None = None) -> dict[str, Result]:
    out: dict[str, Result] = {}
    for name, p in cfg["products"].items():
        if products and name not in products:
            continue
        r = Result()
        check_resources(name, root / p["res"], cfg, r, root)
        check_sources(name, root / p["src"], cfg, r, root)
        out[name] = r
    return out


def inventory_markdown(results: dict[str, Result]) -> str:
    lines = ["| Product | Where | Element | EN | DE | Words (EN) | Source |", "|---|---|---|---|---|---:|---|"]
    for name, r in results.items():
        for e in r.entries:
            esc = lambda s: s.replace("|", "\\|").replace("\n", " ⏎ ")  # noqa: E731
            lines.append(f"| {name} | `{e.where}` | {e.element} | {esc(e.en)} | {esc(e.de)} | {e.words} | {e.source} |")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--root", type=Path, default=ROOT, help="checkout to scan (default: this one)")
    ap.add_argument("--config", type=Path, default=CONFIG)
    ap.add_argument("--product", action="append", help="limit to a product (repeatable)")
    ap.add_argument("--mode", choices=("fail", "warn"), help="override every product's mode")
    ap.add_argument("--verbose", "-v", action="store_true", help="list every finding")
    ap.add_argument("--json", type=Path, help="write findings and inventory as JSON")
    ap.add_argument("--inventory", choices=("md",), help="print every visible string as a table")
    ap.add_argument("--warn-exit-code", type=int, default=0)
    args = ap.parse_args(argv)

    cfg = load_config(args.config)
    root = args.root.resolve()
    results = run(cfg, root, args.product)

    if args.json:
        args.json.write_text(json.dumps({k: {"findings": [asdict(f) for f in v.findings],
                                              "entries": [asdict(e) for e in v.entries]}
                                          for k, v in results.items()}, indent=2, ensure_ascii=False),
                             encoding="utf-8")
    if args.inventory:
        sys.stdout.write(inventory_markdown(results))
        return 0

    failed = warned = False
    for name, r in results.items():
        mode = args.mode or cfg["products"][name]["mode"]
        kinds: dict[str, int] = {}
        for f in r.findings:
            kinds[f.kind] = kinds.get(f.kind, 0) + 1
        visible = [e for e in r.entries if e.element != "spoken"]
        summary = ", ".join(f"{k} {v}" for k, v in sorted(kinds.items())) or "clean"
        print(f"{name} [{mode}]: {len(visible)} visible strings, {len(r.findings)} findings ({summary})")
        if args.verbose or (mode == "fail" and r.findings):
            for f in r.findings:
                text = f" «{f.text[:70]}»" if f.text else ""
                print(f"  {f.kind:<21} {f.where}{text} {f.detail}")
        if r.findings:
            if mode == "fail":
                failed = True
            else:
                warned = True
    if failed:
        print("UI text budget exceeded — see docs/shared/UI_TEXT.md", file=sys.stderr)
        return 1
    return args.warn_exit_code if warned else 0


if __name__ == "__main__":
    sys.exit(main())

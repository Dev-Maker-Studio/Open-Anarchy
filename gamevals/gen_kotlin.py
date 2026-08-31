#!/usr/bin/env python3
"""
Typed gameval constants generator.

Reads gamevals/<type>.json (the 727 id -> Jagex dev-name catalogs, see CONTRACT.md) and writes
one constants class per type into core/src/main/java/org/darkan/core/gameval/gen/ so that game
code can say `Obj.coins`, `Npc.hans`, `Seq.swarm_walk`, `Cs2.quicksort` or
`Component.inventory.inv` from Kotlin and from Java alike, as compile-time constants.

The output is Java on purpose. Every constant is a `public static final int` with a ConstantValue
attribute, which both javac and the Kotlin compiler inline. A JVM class holds at most 65535
constant-pool entries and each constant costs two (its name and its value), so a type with more
than SHARD names is split into a chain of package-private superclasses (`Loc extends Loc2 extends
Loc3 ...`): Java inherits statics through a subclass, and Kotlin 2.x resolves Java statics through
a Java subclass too, so `Loc.<name>` keeps working for every name whichever shard holds it. A
Kotlin `object` with `const val`s cannot do that (no static inheritance, and a 32k-member object
takes minutes to compile). The measured limits are in docs/gameval.md.

Rules (docs/gameval.md, "Typed constants"):
  - only real names get a constant; placeholders (`<type>_<id>`, `cs2_<id>`, `component_<i>_<c>`) are skipped
  - identifiers keep Jagex's snake_case; a leading digit gets an `_` prefix; a character that is
    not [A-Za-z0-9_] becomes `_`; Java/Kotlin reserved words get a trailing `_`; a clash after
    rewriting gets `_<id>` appended. Every rewrite is listed in the file header and on stdout.
  - clientscript `[category,name]` becomes `name`, or `category_name` for every script whose bare
    name exists in more than one category.
  - files are deterministic (ascending id, no timestamps) and only rewritten when their content changes.
  - a missing catalog skips the type (its previously generated files, if any, are left alone); an
    unreadable one (mid-rewrite by the crosswalk) is retried once, then skipped.

Usage:
  python3 gamevals/gen_kotlin.py            # regenerate everything that has a catalog
  python3 gamevals/gen_kotlin.py --check    # exit 1 if any generated file is out of date
  python3 gamevals/gen_kotlin.py --types obj,npc
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CATALOGS = ROOT / "gamevals"
OUT = ROOT / "core/src/main/java/org/darkan/core/gameval/gen"
PACKAGE = "org.darkan.core.gameval.gen"
SELF = "gamevals/gen_kotlin.py"

# Constants per class. javac (and ASM, for Kotlin) reject a class at 32765 distinct int constants:
# 2 constant-pool entries each against the 65535 pool limit. 32000 leaves margin for the pool
# entries the class itself needs.
SHARD = 32000

# Catalog type -> generated class. Order is the order Gameval.TYPES lists them.
CLASSES = {
    "obj": "Obj", "npc": "Npc", "loc": "Loc", "seq": "Seq", "spotanim": "Spotanim",
    "enum": "Enum", "struct": "Struct", "param": "Param", "inv": "Inv",
    "interface": "Interface", "component": "Component", "clientscript": "Cs2",
    "varp": "Varp", "varbit": "Varbit", "varc": "Varc", "varcstr": "Varcstr",
    "varclan": "Varclan", "varbitclan": "Varbitclan",
    "varclansetting": "Varclansetting", "varbitclansetting": "Varbitclansetting",
    "bas": "Bas", "hitmark": "Hitmark", "headbar": "Headbar", "cursor": "Cursor",
    "mapelement": "Mapelement", "quest": "Quest", "model": "Model", "graphic": "Graphic",
    "sound": "Sound", "midi": "Midi", "material": "Material", "fontmetrics": "Fontmetrics",
}

JAVA_RESERVED = set("""
abstract assert boolean break byte case catch char class const continue default do double else
enum extends final finally float for goto if implements import instanceof int interface long
native new package private protected public return short static strictfp super switch
synchronized this throw throws transient try void volatile while true false null _
""".split())
KOTLIN_HARD_KEYWORDS = set("""
as break class continue do else false for fun if in interface is null object package return
super this throw true try typealias typeof val var when while
""".split())
RESERVED = JAVA_RESERVED | KOTLIN_HARD_KEYWORDS

# The one member of every Component.<iface> class that is not a component.
IFACE_MEMBER = "IFACE"

ILLEGAL = re.compile(r"[^A-Za-z0-9_]")


def rel(path: Path) -> str:
    """Path relative to the repo when inside it, else absolute; for messages only."""
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


class Rewrite:
    """One identifier rewrite, for the generated header and the report."""

    def __init__(self, key: str, raw: str, ident: str, why: str):
        self.key, self.raw, self.ident, self.why = key, raw, ident, why

    def __str__(self) -> str:
        return f'{self.key} "{self.raw}" -> {self.ident} ({self.why})'


def placeholder(type_: str, key: str) -> str:
    prefix = "cs2" if type_ == "clientscript" else type_
    return f"{prefix}_{key.replace(':', '_')}"


def sanitize(raw: str) -> tuple[str, list[str]]:
    """Turns a dev-name into a Java/Kotlin identifier; returns it with the reasons it changed."""
    why: list[str] = []
    ident = ILLEGAL.sub("_", raw)
    if ident != raw:
        why.append("illegal character(s) replaced by _")
    if not ident or ident[0].isdigit():
        ident = "_" + ident
        why.append("leading digit")
    if ident in RESERVED:
        ident += "_"
        why.append("reserved word")
    if ident == IFACE_MEMBER:
        ident += "_"
        why.append(f"clashes with {IFACE_MEMBER}")
    return ident, why


def assign(type_: str, items: list[tuple[str, int, str]]) -> tuple[list[tuple[str, int, str, str]], list[Rewrite]]:
    """
    items: (key, sort id, dev-name) in the order the constants should be emitted.
    Returns (key, id, ident, raw) rows plus the rewrites, with post-sanitize clashes resolved by
    appending _<id> to the later entry.
    """
    rows, rewrites, taken = [], [], set()
    for key, id_, raw in items:
        ident, why = sanitize(raw)
        if not ident.strip("_"):
            ident = f"_{id_}"  # Kotlin reserves identifiers made only of underscores
            why.append("underscores only")
        if ident in taken:
            ident = f"{ident}_{id_}"
            why.append("clash after rewrite")
        taken.add(ident)
        if why:
            rewrites.append(Rewrite(f"{type_} {key}", raw, ident, ", ".join(why)))
        rows.append((key, id_, ident, raw))
    return rows, rewrites


# ---- catalog reading ------------------------------------------------------------------------

def read_catalog(type_: str, notices: list[str]) -> dict | None:
    path = CATALOGS / f"{type_}.json"
    if not path.is_file():
        notices.append(f"{type_}: no {rel(path)}; skipped")
        return None
    for attempt in (1, 2):
        try:
            with path.open(encoding="utf-8") as f:
                data = json.load(f)
            entries = data.get("entries")
            if not isinstance(entries, dict) or data.get("type") not in (None, type_):
                raise ValueError("unexpected catalog shape")
            return data
        except (ValueError, OSError) as e:
            if attempt == 1:
                time.sleep(2)  # the crosswalk may be mid-write; give it a moment
                continue
            notices.append(f"{type_}: {rel(path)} unreadable ({e}); skipped")
    return None


def named_entries(type_: str, entries: dict[str, str]) -> list[tuple[str, str]]:
    """(key, name) for every entry that is not a placeholder, in ascending id order."""
    out = []
    for key, name in entries.items():
        if not isinstance(name, str) or name == placeholder(type_, key):
            continue
        out.append((key, name))

    def sort_key(kv):
        parts = kv[0].split(":")
        return tuple(int(p) if p.lstrip("-").isdigit() else 1 << 40 for p in parts)

    out.sort(key=sort_key)
    return out


# ---- emit -----------------------------------------------------------------------------------

def header(type_: str, data: dict, named: int, extra: list[str], rewrites: list[Rewrite]) -> list[str]:
    total = len(data.get("entries", {}))
    lines = [
        f"// GENERATED by {SELF} from gamevals/{type_}.json (revision {data.get('revision', 727)}). Do not edit by hand.",
        f"// {named} named ids of {total}; placeholders are skipped. See docs/gameval.md, \"Typed constants\".",
    ]
    lines += [f"// {e}" for e in extra]
    if rewrites:
        lines.append("// Identifiers that differ from the dev-name:")
        lines += [f"//   {r}" for r in rewrites]
    return lines


def class_file(type_: str, data: dict, cls: str, superclass: str | None, public: bool,
               rows: list[tuple[str, int, str, str]], named: int, extra: list[str], rewrites: list[Rewrite]) -> str:
    lines = header(type_, data, named, extra, rewrites)
    lines += ["", f"package {PACKAGE};", ""]
    if public:
        lines += [
            "/**",
            f" * Revision-727 {type_} ids by Jagex dev-name: {{@code {cls}.<name>}} from Kotlin or Java.",
            f" * Generated from gamevals/{type_}.json; regenerate with {{@code python3 {SELF}}}.",
            " */",
        ]
    else:
        lines += [f"/** Continuation of {{@link {cls.rstrip('0123456789')}}}; reference the constants through that class. */"]
    ext = f" extends {superclass}" if superclass else ""
    lines.append(f"{'public ' if public else ''}{'final ' if public else ''}class {cls}{ext} {{")
    lines.append(f"    {'private' if public else 'protected'} {cls}() {{}}")
    lines.append("")
    for key, id_, ident, raw in rows:
        comment = f" // {key} {raw}" if ident != raw or ":" in key else ""
        lines.append(f"    public static final int {ident} = {id_};{comment}")
    lines.append("}")
    return "\n".join(lines) + "\n"


def emit_sharded(type_: str, data: dict, cls: str, rows: list[tuple[str, int, str, str]],
                 rewrites: list[Rewrite]) -> dict[str, str]:
    """Primary class + package-private superclass chain, SHARD constants each."""
    chunks = [rows[i:i + SHARD] for i in range(0, len(rows), SHARD)] or [[]]
    names = [cls] + [f"{cls}{i + 2}" for i in range(len(chunks) - 1)]
    files = {}
    for i, chunk in enumerate(chunks):
        superclass = names[i + 1] if i + 1 < len(names) else None
        extra = []
        if len(chunks) > 1:
            span = f"ids {chunk[0][1]}..{chunk[-1][1]}" if chunk else "empty"
            chain = " -> ".join(names)
            extra.append(f"Part {i + 1} of {len(chunks)} ({span}); the class chain {chain} keeps every constant reachable as {cls}.<name>.")
        if len(chunks) > 1:
            keys = {f"{type_} {row[0]}" for row in chunk}
            part_rewrites = [r for r in rewrites if r.key in keys]
        else:
            part_rewrites = rewrites
        files[f"{names[i]}.java"] = class_file(type_, data, names[i], superclass, i == 0, chunk, len(rows), extra, part_rewrites)
    return files


def emit_simple(type_: str, data: dict, cls: str, named: list[tuple[str, str]]) -> tuple[dict[str, str], list[Rewrite], int]:
    items = [(key, int(key), name) for key, name in named if key.lstrip("-").isdigit()]
    rows, rewrites = assign(type_, items)
    return emit_sharded(type_, data, cls, rows, rewrites), rewrites, len(rows)


def emit_clientscript(type_: str, data: dict, cls: str, named: list[tuple[str, str]]) -> tuple[dict[str, str], list[Rewrite], int]:
    parsed = []
    for key, name in named:
        m = re.fullmatch(r"\[([^,\]]*),([^\]]*)\]", name)
        category, bare = (m.group(1), m.group(2)) if m else ("", name)
        parsed.append((key, category, bare, name))
    categories_of = defaultdict(set)
    for _, category, bare, _ in parsed:
        categories_of[bare].add(category)
    items = []
    for key, category, bare, name in parsed:
        ident = bare if len(categories_of[bare]) == 1 or not category else f"{category}_{bare}"
        items.append((key, int(key), ident))
    rows, rewrites = assign(type_, items)
    # Keep the full Jagex form in the trailing comment so the raw name stays greppable.
    raw_by_key = {key: name for key, _, _, name in parsed}
    rows = [(key, id_, ident, raw_by_key[key]) for key, id_, ident, _ in rows]
    return emit_sharded(type_, data, cls, rows, rewrites), rewrites, len(rows)


def emit_component(type_: str, data: dict, cls: str, named: list[tuple[str, str]]) -> tuple[dict[str, str], list[Rewrite], int]:
    """Component.<iface>.<comp> = (iface << 16) | comp, plus Component.<iface>.IFACE = iface id."""
    by_iface: dict[int, list[tuple[int, str, str]]] = defaultdict(list)  # iface id -> (comp id, comp name, iface name)
    for key, name in named:
        ik, _, ck = key.partition(":")
        iname, _, cname = name.partition(":")
        if not ck.isdigit() or not ik.isdigit():
            continue
        by_iface[int(ik)].append((int(ck), cname, iname))

    iface_items = []
    for iface_id in sorted(by_iface):
        iface_name = by_iface[iface_id][0][2]
        iface_items.append((f"{iface_id}:*", iface_id, iface_name))
    iface_rows, rewrites = assign(type_, iface_items)

    lines = header(type_, data, len(named), [], [])  # rewrites are listed per nested class below
    lines += ["", f"package {PACKAGE};", ""]
    lines += [
        "/**",
        " * Revision-727 interface components by Jagex dev-name: {@code Component.<interface>.<component>} is the",
        " * packed id {@code (iface << 16) | comp} the interface packets use, and {@code Component.<interface>.IFACE}",
        " * is the interface id. Generated from gamevals/component.json; regenerate with",
        f" * {{@code python3 {SELF}}}.",
        " */",
        f"public final class {cls} {{",
        f"    private {cls}() {{}}",
    ]
    count = 0
    for key, iface_id, iface_ident, iface_raw in iface_rows:
        comps = sorted(by_iface[iface_id])
        comp_items = [(f"{iface_id}:{cid}", cid, cname) for cid, cname, _ in comps]
        comp_rows, comp_rewrites = assign(type_, comp_items)
        rewrites += comp_rewrites
        lines.append("")
        lines.append(f"    /** Interface {iface_id} {iface_raw}. */")
        lines.append(f"    public static final class {iface_ident} {{")
        lines.append(f"        private {iface_ident}() {{}}")
        lines.append(f"        public static final int {IFACE_MEMBER} = {iface_id};")
        for ckey, cid, cident, craw in comp_rows:
            packed = (iface_id << 16) | (cid & 0xFFFF)
            lines.append(f"        public static final int {cident} = {packed}; // {ckey} {iface_raw}:{craw}")
            count += 1
        lines.append("    }")
    lines.append("}")
    if rewrites:
        idx = lines.index(f"package {PACKAGE};")
        lines[idx - 1:idx - 1] = ["// Identifiers that differ from the dev-name:"] + [f"//   {r}" for r in rewrites]
    return {f"{cls}.java": "\n".join(lines) + "\n"}, rewrites, count


# ---- driver ---------------------------------------------------------------------------------

def generate(types: list[str], check: bool) -> int:
    notices: list[str] = []
    report: list[str] = []
    all_rewrites: list[Rewrite] = []
    changed = 0
    OUT.mkdir(parents=True, exist_ok=True)
    existing = {p.name for p in OUT.glob("*.java")}

    for type_ in types:
        cls = CLASSES[type_]
        data = read_catalog(type_, notices)
        if data is None:
            continue
        named = named_entries(type_, data["entries"])
        if type_ == "component":
            files, rewrites, count = emit_component(type_, data, cls, named)
        elif type_ == "clientscript":
            files, rewrites, count = emit_clientscript(type_, data, cls, named)
        else:
            files, rewrites, count = emit_simple(type_, data, cls, named)
        all_rewrites += rewrites

        for name, content in files.items():
            path = OUT / name
            if path.is_file() and path.read_text(encoding="utf-8") == content:
                continue
            changed += 1
            if check:
                report.append(f"OUT OF DATE: {rel(path)}")
            else:
                tmp = path.with_suffix(".java.tmp")
                tmp.write_text(content, encoding="utf-8")
                os.replace(tmp, path)
        # Shards this type no longer needs.
        stale = [n for n in existing if re.fullmatch(rf"{cls}\d*\.java", n) and n not in files]
        for n in stale:
            changed += 1
            if check:
                report.append(f"STALE: {rel(OUT / n)}")
            else:
                (OUT / n).unlink()
        shards = len(files)
        report.append(f"{type_:18} {cls:18} {count:6} constants  {shards} file(s)  {len(rewrites)} rewrite(s)"
                      + (f"  [{', '.join(sorted(files))}]" if shards > 1 else ""))

    print(f"Typed gameval constants -> {rel(OUT)}/")
    for line in report:
        print("  " + line)
    if all_rewrites:
        print(f"Identifier rewrites ({len(all_rewrites)}):")
        for r in all_rewrites:
            print("  " + str(r))
    for n in notices:
        print("notice: " + n)
    if check:
        print("up to date" if changed == 0 else f"{changed} file(s) would change")
        return 1 if changed else 0
    print(f"{changed} file(s) written")
    return 0


def main() -> int:
    global CATALOGS, OUT
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--types", help="comma-separated catalog types (default: all)")
    ap.add_argument("--check", action="store_true", help="report whether the generated files are current; exit 1 if not")
    ap.add_argument("--catalogs", type=Path, help=f"catalog folder (default {rel(CATALOGS)})")
    ap.add_argument("--out", type=Path, help=f"output folder (default {rel(OUT)})")
    args = ap.parse_args()
    if args.catalogs:
        CATALOGS = args.catalogs.resolve()
    if args.out:
        OUT = args.out.resolve()
    types = list(CLASSES)
    if args.types:
        types = [t.strip() for t in args.types.split(",") if t.strip()]
        unknown = [t for t in types if t not in CLASSES]
        if unknown:
            ap.error(f"unknown type(s): {', '.join(unknown)}")
    return generate(types, args.check)


if __name__ == "__main__":
    sys.exit(main())

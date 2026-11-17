import argparse
import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import kt
import spec
import view


def check(args):
    s = spec.load(args.json)
    gv = spec.gamevals(args.gamevals, args.hostile) if args.gamevals else None
    errors, span = spec.validate(s, gv, spec.quest_names(args.quest_kt) if args.quest_kt else None)
    for e in errors:
        print("ERROR", e)
    if not errors:
        print(f"OK {len(s['hops'])} hops, multihop spans: " + ", ".join(f"{k} ({len(v)})" for k, v in span.items()))
    return 1 if errors else 0


def html(args):
    out = args.out or os.path.splitext(args.json)[0] + ".html"
    with open(out, "w") as f:
        f.write(view.page(spec.load(args.json)))
    print(out)
    return 0


def kotlin(args):
    s = spec.load(args.json)
    errors, _ = spec.validate(s)
    for e in errors:
        print("ERROR", e)
    if errors:
        return 1
    for name in kt.generate(s, args.outdir):
        print(os.path.join(args.outdir, name))
    return 0


def scan(args):
    found = kt.scan(args.dir)
    text = json.dumps(found, indent=2)
    if args.out:
        open(args.out, "w").write(text)
    else:
        print(text)
    return 0


def roundtrip(args):
    s = spec.load(args.json)
    with tempfile.TemporaryDirectory() as tmp:
        kt.generate(s, tmp)
        back = kt.scan(tmp)
    if back == s:
        print("OK")
        return 0
    print("DIFF", "hand-written output" if "handwritten" in back else "spec differs after the round trip")
    return 1


def main():
    p = argparse.ArgumentParser(prog="quest.py")
    sub = p.add_subparsers(dest="cmd", required=True)
    v = sub.add_parser("validate")
    v.add_argument("json")
    v.add_argument("--gamevals")
    v.add_argument("--hostile")
    v.add_argument("--quest-kt")
    v.set_defaults(run=check)
    h = sub.add_parser("html")
    h.add_argument("json")
    h.add_argument("out", nargs="?")
    h.set_defaults(run=html)
    k = sub.add_parser("kotlin")
    k.add_argument("json")
    k.add_argument("outdir")
    k.set_defaults(run=kotlin)
    c = sub.add_parser("scan")
    c.add_argument("dir")
    c.add_argument("out", nargs="?")
    c.set_defaults(run=scan)
    r = sub.add_parser("roundtrip")
    r.add_argument("json")
    r.set_defaults(run=roundtrip)
    args = p.parse_args()
    sys.exit(args.run(args))


main()

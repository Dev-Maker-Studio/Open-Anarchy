#!/usr/bin/env python3
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
CITIES = ROOT / "data" / "cities"
GAMEVALS = ROOT / "cache-source" / "gamevals" / "loc.json"
WALLS = ["farmhouse", "brick", "plaster", "stone", "whitestone", "lightwood", "darkwood", "greyplaster"]
FLOORS = ["planks", "darkwood", "sandstone", "cobble", "flagstone", "stone", "slate", "marble", "cream", "terracotta", "golden"]
ROOFS = ["slate", "blueslate", "greyslate", "darkslate", "redslate", "thatch", "straw", "reed", "hay", "wood", "none"]
SERVICES = ["bank", "smithy", "general_store", "barber", "sword_shop", "archery_shop", "magic_shop", "clothes_shop", "food_shop", "armour_shop", "fishing_shop", "staff_shop", "realtor"]
SIDES = {"WEST": (-1, 0), "NORTH": (0, 1), "EAST": (1, 0), "SOUTH": (0, -1)}
GATE_MARGIN = 6
BLEND = 13
MARKS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"


def load(name):
    return json.loads((CITIES / f"{name}.json").read_text())


def door(b):
    if b["door"] == "WEST":
        return b["x"], b["y"] + b["depth"] // 2
    if b["door"] == "EAST":
        return b["x"] + b["width"] - 1, b["y"] + b["depth"] // 2
    if b["door"] == "SOUTH":
        return b["x"] + b["width"] // 2, b["y"]
    return b["x"] + b["width"] // 2, b["y"] + b["depth"] - 1


def on_street(city, x, y):
    return any(s["x"] <= x < s["x"] + s["width"] and s["y"] <= y < s["y"] + s["depth"] for s in city["streets"])


def cmd_list():
    for path in sorted(CITIES.glob("*.json")):
        c = json.loads(path.read_text())
        if "buildings" not in c:
            print(f'{c["id"]}: stub, seed {c["seed"]}, made at the next world start')
            continue
        tall = sum(1 for b in c["buildings"] if b.get("floors", 1) > 1)
        print(f'{c["id"]}: centre {c["centerX"]},{c["centerY"]} half {c["halfSize"]}, {len(c["buildings"])} buildings ({tall} with upper floors), {len(c["residents"])} residents')


def cmd_plan(name, step=2):
    c = load(name)
    step = int(step)
    half = c["halfSize"]
    x0, y0 = c["centerX"] - half, c["centerY"] - half
    size = 2 * half + 1
    rows = []
    for gy in range(size - 1, -1, -step):
        row = ""
        for gx in range(0, size, step):
            x, y = x0 + gx, y0 + gy
            mark = "."
            if on_street(c, x, y):
                mark = "="
            for i, b in enumerate(c["buildings"]):
                if b["x"] <= x < b["x"] + b["width"] and b["y"] <= y < b["y"] + b["depth"]:
                    mark = MARKS[i]
            row += mark
        rows.append(row)
    print(f'{c["name"]}: north is up, one character is {step} tiles, = street, digit or letter = building index')
    print("\n".join(rows))
    for i, b in enumerate(c["buildings"]):
        print(f'{MARKS[i]} {i} {b["kind"]} {b["x"]},{b["y"]} {b["width"]}x{b["depth"]} door {b["door"]} floors {b.get("floors", 1)} wall {b["wall"]} floor {b["floor"]} roof {b.get("roof", "unset")}{" FOR SALE" if b.get("forSale") else ""}')


def cmd_building(name, index):
    c = load(name)
    b = c["buildings"][int(index)]
    dx, dy = door(b)
    print(f'{b["kind"]} {b["x"]},{b["y"]} {b["width"]}x{b["depth"]} door {b["door"]} wall {b["wall"]} floor {b["floor"]} roof {b.get("roof", "unset")}; # wall, D door, letters below')
    for level in range(b.get("floors", 1)):
        grid = [["#" if x in (0, b["width"] - 1) or y in (0, b["depth"] - 1) else "." for x in range(b["width"])] for y in range(b["depth"])]
        if level == 0:
            grid[dy - b["y"]][dx - b["x"]] = "D"
        legend = {}
        for f in b["furniture"]:
            if f.get("level", 0) != level:
                continue
            letter = legend.setdefault(f["loc"], chr(ord("a") + len(legend)))
            if 0 <= f["x"] < b["width"] and 0 <= f["y"] < b["depth"]:
                grid[f["y"]][f["x"]] = letter
        print(f"floor {level}: " + ", ".join(f"{v}={k}" for k, v in legend.items()))
        print("\n".join("".join(row) for row in reversed(grid)))
    for r in c["residents"]:
        if b["x"] <= r["x"] < b["x"] + b["width"] and b["y"] <= r["y"] < b["y"] + b["depth"]:
            print(f'resident {r["role"]} at {r["x"] - b["x"]},{r["y"] - b["y"]} level {r.get("level", 0)}')


def problems(c, locs):
    out = []
    half = c["halfSize"]
    for i, b in enumerate(c["buildings"]):
        tag = f'{c["id"]} building {i} ({b["kind"]})'
        if b["wall"] not in WALLS:
            out.append(f'{tag}: unknown wall "{b["wall"]}", use one of {", ".join(WALLS)}')
        if b["floor"] not in FLOORS and not b["floor"].startswith("overlay_"):
            out.append(f'{tag}: unknown floor "{b["floor"]}", use one of {", ".join(FLOORS)}')
        if "roof" in b and b["roof"] not in ROOFS:
            out.append(f'{tag}: unknown roof "{b["roof"]}", use one of {", ".join(ROOFS)}')
        if b["kind"] in SERVICES and b["kind"] not in ("bank", "smithy") and not any(r["role"] in ("shopkeeper", "barber", "realtor") and b["x"] < r["x"] < b["x"] + b["width"] - 1 and b["y"] < r["y"] < b["y"] + b["depth"] - 1 for r in c["residents"]):
            out.append(f'{tag}: a service building needs a resident with role shopkeeper (or barber, or realtor) inside it')
        if b.get("forSale") and (b["kind"] != "house" or any(f["loc"] not in ("ladder", "laddertop") for f in b["furniture"])):
            out.append(f'{tag}: a home for sale is an empty house, with no furniture but its ladders')
        if b["door"] not in SIDES:
            out.append(f'{tag}: door must be one of {", ".join(SIDES)}')
            continue
        floors = b.get("floors", 1)
        if not 1 <= floors <= 3:
            out.append(f"{tag}: floors must be 1 to 3")
        if b["width"] < 5 or b["depth"] < 5:
            out.append(f"{tag}: smaller than 5x5")
        if abs(b["x"] - c["centerX"]) > half or abs(b["x"] + b["width"] - 1 - c["centerX"]) > half or abs(b["y"] - c["centerY"]) > half or abs(b["y"] + b["depth"] - 1 - c["centerY"]) > half:
            out.append(f"{tag}: outside the city footprint")
        for s in c["streets"]:
            if b["x"] < s["x"] + s["width"] and b["x"] + b["width"] > s["x"] and b["y"] < s["y"] + s["depth"] and b["y"] + b["depth"] > s["y"]:
                out.append(f'{tag}: overlaps the street at {s["x"]},{s["y"]}')
                break
        for j, o in enumerate(c["buildings"][:i]):
            if b["x"] <= o["x"] + o["width"] and b["x"] + b["width"] >= o["x"] and b["y"] <= o["y"] + o["depth"] and b["y"] + b["depth"] >= o["y"]:
                out.append(f"{tag}: touches building {j}, leave one tile between buildings")
        dx, dy = door(b)
        sx, sy = SIDES[b["door"]]
        if not on_street(c, dx + sx, dy + sy):
            out.append(f"{tag}: the door at {dx},{dy} does not open onto a street")
        used = {}
        for f in b["furniture"]:
            level = f.get("level", 0)
            if locs and f["loc"] not in locs:
                out.append(f'{tag}: no loc named "{f["loc"]}"')
            if not (1 <= f["x"] <= b["width"] - 2 and 1 <= f["y"] <= b["depth"] - 2):
                out.append(f'{tag}: {f["loc"]} at {f["x"]},{f["y"]} is not inside the walls')
            if level >= floors:
                out.append(f'{tag}: {f["loc"]} is on level {level} but the building has {floors} floors')
            key = (f["x"], f["y"], level)
            if key in used:
                out.append(f'{tag}: {f["loc"]} and {used[key]} share tile {f["x"]},{f["y"]} on level {level}')
            used[key] = f["loc"]
        feet = {(f["x"], f["y"], f.get("level", 0)) for f in b["furniture"] if f["loc"] == "ladder"}
        tops = {(f["x"], f["y"], f.get("level", 0)) for f in b["furniture"] if f["loc"] == "laddertop"}
        for level in range(floors - 1):
            if not any(l == level and (x, y, level + 1) in tops for x, y, l in feet):
                out.append(f"{tag}: level {level + 1} needs a ladder on level {level} with a laddertop on the same tile one level up")
        for x, y, level in feet:
            if not any((x + ax, y + ay, lv) not in used and 1 <= x + ax <= b["width"] - 2 and 1 <= y + ay <= b["depth"] - 2 for ax, ay in SIDES.values() for lv in (level,) if (x + ax, y + ay, level + 1) not in used):
                out.append(f"{tag}: the ladder at {x},{y} level {level} has no free tile beside it on both levels")
    for r in c["residents"]:
        if r["role"] == "shopkeeper" and (not r.get("shop") or sum(1 for o in c["residents"] if o.get("shop") == r.get("shop")) > 1):
            out.append(f'{c["id"]}: shopkeeper at {r["x"]},{r["y"]} needs a "shop" key of its own, for example "{c["id"]}_sword_shop"')
        if abs(r["x"] - c["centerX"]) > half + GATE_MARGIN or abs(r["y"] - c["centerY"]) > half + GATE_MARGIN:
            out.append(f'{c["id"]}: resident {r["role"]} at {r["x"]},{r["y"]} is outside the city')
    return out


def cmd_validate(*names):
    locs = set(json.loads(GAMEVALS.read_text())["entries"].values()) if GAMEVALS.is_file() else set()
    failed = False
    for name in names or [p.stem for p in sorted(CITIES.glob("*.json"))]:
        found = problems(load(name), locs)
        failed = failed or bool(found)
        print(f"{name}: " + ("OK" if not found else f"{len(found)} problems"))
        for line in found:
            print("  " + line)
    sys.exit(1 if failed else 0)


def cmd_shots(name, *indexes):
    c = load(name)
    picked = [int(i) for i in indexes] or range(len(c["buildings"]))
    for i in picked:
        for level in range(c["buildings"][i].get("floors", 1)):
            print(f"::cityshot {name} {i} top {level}")
            print(f"::cityshot {name} {i} tilt {level}")
    if not indexes:
        print(f"::cityshot {name} all top")
        print(f"::cityshot {name} all tilt")
    print("::cityshot off")


def cmd_squares(name, action="show"):
    c = load(name)
    reach = c["halfSize"] + BLEND
    maps = ROOT / "cache-source" / "maps"
    found = [maps / f"{rx}_{ry}" for rx in range((c["centerX"] - reach) // 64, (c["centerX"] + reach) // 64 + 1) for ry in range((c["centerY"] - reach) // 64, (c["centerY"] + reach) // 64 + 1)]
    found = [p for p in found if p.is_dir()]
    for path in found:
        if action == "delete":
            shutil.rmtree(path)
        print(("deleted " if action == "delete" else "saved ") + path.name)
    if not found:
        print("no saved squares; the generator output is used")


def write_stub(ident, name, seed, x, y, half):
    stub = {"id": ident, "name": name, "seed": int(seed), "centerX": int(x), "centerY": int(y), "halfSize": int(half), "baseHeight": 0}
    (CITIES / f"{ident}.json").write_text(json.dumps(stub) + "\n")
    print(f"{ident}: stub with seed {stub['seed']} at {stub['centerX']},{stub['centerY']} half {stub['halfSize']}; the next world start makes the city from it")


def cmd_remake(name, seed=None):
    c = load(name)
    write_stub(c["id"], c["name"], c["seed"] if seed is None else seed, c["centerX"], c["centerY"], c["halfSize"])


def cmd_new(name, x, y, seed, half=34):
    ident = name.lower().replace(" ", "_")
    if (CITIES / f"{ident}.json").exists():
        print(f"{ident} already exists, use remake")
        sys.exit(1)
    write_stub(ident, name, seed, x, y, half)


COMMANDS = {"remake": cmd_remake, "new": cmd_new, "squares": cmd_squares, "list": cmd_list, "plan": cmd_plan, "building": cmd_building, "validate": cmd_validate, "shots": cmd_shots}

if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in COMMANDS:
        print("usage: city.py list | plan <city> [tiles per char] | building <city> <index> | validate [city...] | shots <city> [index...] | squares <city> [delete] | remake <city> [seed] | new <name> <x> <y> <seed> [half]")
        sys.exit(2)
    COMMANDS[sys.argv[1]](*sys.argv[2:])

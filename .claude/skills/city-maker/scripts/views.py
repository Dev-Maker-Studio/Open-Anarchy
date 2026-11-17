#!/usr/bin/env python3
import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
CITIES = ROOT / "data" / "cities"
STATE = ROOT / "data" / "city-views"
CACHE = Path(os.environ.get("CITY_VIEW_CACHE", ROOT / "data" / "cache-cities"))
LAKAN = Path(os.environ.get("LAKAN_BIN", "/data/projects/Personal/lakan/target/release/lakan"))
MAP_SIZES = [104, 120, 136, 168]
TOP_PITCH = 2980
TILT_PITCH = 2048
CANVAS = [1024, 768]
GAME_AREA = [0, 0, 1024, 768]
ZOOM_PER_TILE = 100
ZOOM_BASE = 100
ROOF_ZOOM_PER_TILE = 160
CITY_ZOOM_PER_TILE = 93
SHEET_COLUMNS = 3
SHEET_CELL = "340x255+2+2"
MAP_SCALE = 6
MAP_MARGIN = 12
WALL_RGB = (40, 30, 20)
SCENERY_RGB = (20, 70, 20)
ROOFS_SHOWN = 0
ROOFS_HIDDEN = 1
SLOTS = [0] * 4 + [1] * 5 + [2] * 13 + [3]
HEX = "0123456789abcdef"


def city(name):
    return json.loads((CITIES / f"{name}.json").read_text())


def state(name):
    path = STATE / f"{name}.state.json"
    if not path.is_file():
        sys.exit(f"{path} is missing: restart darkan-world-22, it writes the world state at start")
    return json.loads(path.read_text())


def views_path(name):
    return STATE / f"{name}.views.json"


def default_views(name):
    c = city(name)
    out = []
    for i, b in enumerate(c["buildings"]):
        span = max(b["width"], b["depth"])
        tile = [b["x"] + b["width"] // 2, b["y"] + b["depth"] // 2]
        for level in range(b.get("floors", 1)):
            for mode, pitch in (("top", TOP_PITCH), ("tilt", TILT_PITCH)):
                out.append({"name": f"b{i}-f{level}-{mode}", "building": i, "tile": tile, "plane": level, "yaw": 0, "pitch": pitch, "zoom": ZOOM_BASE + span * ZOOM_PER_TILE, "roofs": ROOFS_HIDDEN, "map_size": 104})
        out.append({"name": f"b{i}-roof", "building": i, "tile": tile, "plane": 0, "yaw": 0, "pitch": TILT_PITCH, "zoom": ZOOM_BASE + span * ROOF_ZOOM_PER_TILE, "roofs": ROOFS_SHOWN, "map_size": 104})
    span = 2 * c["halfSize"] + 1
    for mode, pitch in (("top", TOP_PITCH), ("tilt", TILT_PITCH)):
        out.append({"name": f"city-{mode}", "tile": [c["centerX"], c["centerY"]], "plane": 0, "yaw": 0, "pitch": pitch, "zoom": span * CITY_ZOOM_PER_TILE, "roofs": ROOFS_SHOWN, "map_size": 168})
    return out


def load_views(name):
    path = views_path(name)
    saved = {v["name"]: v for v in json.loads(path.read_text())} if path.is_file() else {}
    merged = [saved.get(v["name"], v) if saved.get(v["name"], {}).get("pinned") else v for v in default_views(name)]
    merged += [v for v in saved.values() if v.get("pinned") and v["name"] not in {m["name"] for m in merged}]
    STATE.mkdir(parents=True, exist_ok=True)
    path.write_text("[\n" + ",\n".join("  " + json.dumps(v) for v in merged) + "\n]\n")
    return merged


def sidecar(view, locs):
    size = view["map_size"]
    half = size >> 4
    cx, cy = view["tile"][0] >> 3, view["tile"][1] >> 3
    base = [(cx - half) * 8, (cy - half) * 8]
    regions = [(x << 8) | y for x in range((cx - half) // 8, (cx + half) // 8 + 1) for y in range((cy - half) // 8, (cy + half) // 8 + 1)]
    rebuild = bytes([MAP_SIZES.index(size), cx >> 8, cx & 255, cy >> 8, cy & 255, 0]) + bytes(16 * len(regions))
    inside = [l for l in locs if base[0] <= l["x"] < base[0] + size and base[1] <= l["y"] < base[1] + size]
    records = [{"plane": l["plane"], "x": l["x"], "y": l["y"], "slot": SLOTS[l["shape"]], "src": 0, "id": l["id"], "shape": l["shape"], "rotation": l["rotation"]} for l in inside]
    body = {"base": base, "regions": regions, "rebuild": {"opcode": 85, "bytes": rebuild.hex()}, "varps": [], "varbits": [], "multilocs": [], "locs": records, "objs": []}
    text = json.dumps(body, separators=(",", ":"))
    digest = hashlib.sha256(text.encode()).hexdigest()
    world = text[:-1] + f',"sha256":"{digest}"}}'
    eye = [(view["tile"][0] - base[0]) << 9, (view["tile"][1] - base[1]) << 9, -4000]
    head = {"canvas": CANVAS, "game_area": GAME_AREA, "camera": {"drawn": {"eye": eye, "angles": [0, view["pitch"], view["yaw"]]}}, "tile": view["tile"]}
    return json.dumps(head, separators=(",", ":"))[:-1] + ',"world_state":' + world + "}", digest


def scene(view, scene_id):
    return "\n".join([
        "schema = 3", f'id = "{scene_id}"', f'name = "{view["name"]}"', 'category = "world"', 'features = ["terrain"]', 'target = ["local"]',
        f'tile = [{view["tile"][0]}, {view["tile"][1]}]', f'plane = {view["plane"]}', 'ui = "none"', 'hide = ["players", "npcs", "self"]',
        f'camera = {{ yaw = {view["yaw"]}, pitch = {view["pitch"]}, zoom = {view["zoom"]} }}',
        f'resolution = [{CANVAS[0]}, {CANVAS[1]}]', f'prefs = {{ brightness = 3, bloom = false, textures = true, roofs = {view["roofs"]}, skybox = true, fog = false }}',
        'settle = "world"', 'first_gate = "M4"', "",
    ])


def cmd_list(name):
    for v in load_views(name):
        print(f'{v["name"]}: tile {v["tile"][0]},{v["tile"][1]} plane {v["plane"]} yaw {v["yaw"]} pitch {v["pitch"]} zoom {v["zoom"]}' + (" pinned" if v.get("pinned") else ""))
    print(f"edit {views_path(name)} and set \"pinned\": true on a view to keep its camera")


def cmd_shoot(name, *picked):
    views = load_views(name)
    wanted = [v for v in views if not picked or v["name"] in picked or any(v["name"].startswith(p + "-") for p in picked)]
    locs = state(name)["locs"]
    out = STATE / name
    work = out / "work"
    work.mkdir(parents=True, exist_ok=True)
    manifest_path = out / "manifest.json"
    manifest = json.loads(manifest_path.read_text()) if manifest_path.is_file() else {}
    cache_stamp = str((CACHE / "main_file_cache.idx5").stat().st_mtime_ns)
    for index, view in enumerate(wanted):
        scene_id = f"CV-{views.index(view):03d}"
        side, digest = sidecar(view, locs)
        key = hashlib.sha256((digest + json.dumps(view, sort_keys=True) + cache_stamp).encode()).hexdigest()
        target = out / f'{view["name"]}.png'
        if manifest.get(view["name"]) == key and target.is_file():
            print(f"unchanged {target}")
            continue
        (work / f"{scene_id}.toml").write_text(scene(view, scene_id))
        (work / f"{scene_id}.json").write_text(side)
        command = ["xvfb-run", "-a", str(LAKAN), "--capture-scene", str(work / f"{scene_id}.toml"), "--out", str(work), "--cache", str(CACHE), "--reference", str(work)]
        run = subprocess.run(command, capture_output=True, text=True, timeout=300)
        made = sorted(work.glob(f"{scene_id}*.png"))
        if run.returncode != 0 or not made:
            print(f'failed {view["name"]}: {(run.stderr or run.stdout).strip().splitlines()[-1:]}')
            continue
        shutil.move(made[0], target)
        manifest[view["name"]] = key
        manifest_path.write_text(json.dumps(manifest, indent=1))
        print(f"wrote {target}")


def cmd_sheet(name, *picked):
    out = STATE / name
    files = [out / f'{v["name"]}.png' for v in load_views(name) if not picked or v["name"] in picked or any(v["name"].startswith(p + "-") for p in picked)]
    files = [f for f in files if f.is_file()]
    if not files:
        sys.exit("nothing shot yet; run shoot first")
    sheet = out / "sheet.jpg"
    subprocess.run(["magick", "montage", *map(str, files), "-tile", f"{SHEET_COLUMNS}x", "-geometry", SHEET_CELL, "-quality", "80", str(sheet)], check=True)
    for row in range(0, len(files), SHEET_COLUMNS):
        print(" | ".join(f.stem for f in files[row:row + SHEET_COLUMNS]))
    print(f"wrote {sheet} (rows as listed, left to right)")


def rgb(value):
    return (value >> 16 & 255, value >> 8 & 255, value & 255)


def cmd_map(name):
    c = city(name)
    tree = ROOT / "cache-source"
    reach = c["halfSize"] + MAP_MARGIN
    x0, y0 = c["centerX"] - reach, c["centerY"] - reach
    side = 2 * reach + 1
    colours = {}

    def colour(kind, ident):
        if (kind, ident) not in colours:
            d = json.loads((tree / "config" / kind / f"{ident}.json").read_text())
            colours[(kind, ident)] = rgb(d.get("colourRgb", d.get("colour", 0)))
        return colours[(kind, ident)]

    squares = {}
    grid = [[(0, 0, 0)] * side for _ in range(side)]
    for gx in range(side):
        for gy in range(side):
            x, y = x0 + gx, y0 + gy
            key = (x // 64, y // 64)
            if key not in squares:
                path = tree / "maps" / f"{key[0]}_{key[1]}" / "terrain.json"
                squares[key] = [row.split(" ") for row in json.loads(path.read_text())["tiles"][0]] if path.is_file() else None
            if squares[key] is None:
                continue
            fields = dict((t[0], t[1:]) for t in squares[key][x % 64][y % 64].split(",") if t != "-")
            if "o" in fields:
                grid[gy][gx] = colour("overlay", int(fields["o"].split("/")[0]))
            elif "u" in fields:
                grid[gy][gx] = colour("underlay", int(fields["u"]))
    size = side * MAP_SCALE
    pixels = bytearray(size * size * 3)

    def put(px, py, value):
        if 0 <= px < size and 0 <= py < size:
            at = ((size - 1 - py) * size + px) * 3
            pixels[at:at + 3] = bytes(value)

    for gy in range(side):
        for gx in range(side):
            for dx in range(MAP_SCALE):
                for dy in range(MAP_SCALE):
                    put(gx * MAP_SCALE + dx, gy * MAP_SCALE + dy, grid[gy][gx])
    last = MAP_SCALE - 1
    edges = {0: lambda i: (0, i), 1: lambda i: (i, last), 2: lambda i: (last, i), 3: lambda i: (i, 0)}
    for l in state(name)["locs"]:
        if l["plane"] != 0 or not (x0 <= l["x"] < x0 + side and y0 <= l["y"] < y0 + side):
            continue
        bx, by = (l["x"] - x0) * MAP_SCALE, (l["y"] - y0) * MAP_SCALE
        if l["shape"] == 0:
            for i in range(MAP_SCALE):
                ex, ey = edges[l["rotation"]](i)
                put(bx + ex, by + ey, WALL_RGB)
        elif l["shape"] == 2:
            for rotation in (l["rotation"], (l["rotation"] + 1) % 4):
                for i in range(MAP_SCALE):
                    ex, ey = edges[rotation](i)
                    put(bx + ex, by + ey, WALL_RGB)
        elif l["shape"] in (10, 11):
            for dx in range(2, MAP_SCALE - 2):
                for dy in range(2, MAP_SCALE - 2):
                    put(bx + dx, by + dy, WALL_RGB if c["centerX"] - c["halfSize"] <= l["x"] <= c["centerX"] + c["halfSize"] and c["centerY"] - c["halfSize"] <= l["y"] <= c["centerY"] + c["halfSize"] else SCENERY_RGB)
    out = STATE / name
    out.mkdir(parents=True, exist_ok=True)
    ppm = out / "work-map.ppm"
    ppm.write_bytes(f"P6 {size} {size} 255\n".encode() + bytes(pixels))
    target = out / "map.png"
    subprocess.run(["magick", str(ppm), str(target)], check=True)
    ppm.unlink()
    print(f"wrote {target} (north up, {MAP_SCALE} pixels per tile, drawn from the same terrain and objects as the world map image)")


COMMANDS = {"list": cmd_list, "shoot": cmd_shoot, "sheet": cmd_sheet, "map": cmd_map}

if __name__ == "__main__":
    if len(sys.argv) < 3 or sys.argv[1] not in COMMANDS:
        print("usage: views.py list <city> | shoot <city> [view name or prefix such as b3 or city]... | sheet <city> [view or prefix]... | map <city>")
        sys.exit(2)
    COMMANDS[sys.argv[1]](*sys.argv[2:])

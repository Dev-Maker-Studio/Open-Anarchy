import importlib.util
import json
import os
import shutil
import subprocess
import sys

ROOT = os.getcwd()
DUNGEONS = os.path.join(ROOT, "data", "dungeons")
BOSSES = os.path.join(DUNGEONS, "bosses")
VIEWS = os.path.join(ROOT, "data", "dungeon-views")
THEMES = ("cave", "fortress", "sewer")
DEFAULT_BOSS = {"cave": "broodmother", "fortress": "warden", "sewer": "rat_king"}
SLOT_COLUMNS = 5
SLOT_PITCH = 192
FIRST_X = 238 * 64
FIRST_Y = 2 * 64
MIN_ROOMS, MAX_ROOMS = 5, 9
TILES_PER_LEVEL = 200
CENTRE = 7500
ENTRANCE_LOCS = {"cave": 3759, "fortress": 10698, "sewer": 10321}
JAVA = os.environ.get("JAVA_HOME", "/home/jesse/.jdks/jdk-26.0.2.1+1") + "/bin/java"


def load():
    found = []
    for name in sorted(os.listdir(DUNGEONS)):
        path = os.path.join(DUNGEONS, name)
        if os.path.isfile(path) and name.endswith(".json"):
            with open(path) as handle:
                found.append(json.load(handle))
    return found


def base(slot):
    return FIRST_X + slot % SLOT_COLUMNS * SLOT_PITCH, FIRST_Y + slot // SLOT_COLUMNS * SLOT_PITCH


def level(dungeon):
    return 1 + max(abs(dungeon["entranceX"] - CENTRE), abs(dungeon["entranceY"] - CENTRE)) // TILES_PER_LEVEL


def boss_of(dungeon):
    return dungeon.get("boss") or DEFAULT_BOSS.get(dungeon["theme"], "?")


def listing():
    for dungeon in load():
        x, y = base(dungeon["slot"])
        base_level = level(dungeon)
        print(f'{dungeon["id"]:12} {dungeon["name"]:20} {dungeon["theme"]:9} entrance {dungeon["entranceX"]},{dungeon["entranceY"]} '
              f'base {x},{y} level {base_level} (mobs {round(base_level * 0.8)} to {round(base_level * 1.2)}) boss {boss_of(dungeon)} seed {dungeon["seed"]}')


def validate():
    problems = []
    dungeons = load()
    bosses = {name[:-5] for name in os.listdir(BOSSES) if name.endswith(".json")} if os.path.isdir(BOSSES) else set()
    slots = {}
    for dungeon in dungeons:
        tag = dungeon.get("id", "?")
        for field in ("id", "name", "theme", "seed", "slot", "entranceX", "entranceY", "origin"):
            if field not in dungeon:
                problems.append(f"{tag}: missing {field}")
        if dungeon.get("theme") not in THEMES:
            problems.append(f'{tag}: theme {dungeon.get("theme")} is not one of {", ".join(THEMES)}')
        if dungeon.get("slot") in slots:
            problems.append(f'{tag}: slot {dungeon["slot"]} is also used by {slots[dungeon["slot"]]}')
        slots[dungeon.get("slot")] = tag
        if isinstance(dungeon.get("slot"), int) and base(dungeon["slot"])[1] + 128 > 255 * 64:
            problems.append(f"{tag}: slot {dungeon['slot']} is off the map")
        if "rooms" in dungeon and not MIN_ROOMS <= dungeon["rooms"] <= MAX_ROOMS:
            problems.append(f"{tag}: rooms must be {MIN_ROOMS} to {MAX_ROOMS}")
        if boss_of(dungeon) not in bosses:
            problems.append(f"{tag}: boss {boss_of(dungeon)} has no file in data/dungeons/bosses")
        if not os.path.isfile(os.path.join(DUNGEONS, f"{tag}.json")):
            problems.append(f"{tag}: the file name must be {tag}.json")
    print("\n".join(problems) if problems else "OK")
    return 1 if problems else 0


def plan(dungeon_id):
    libraries = os.path.join(ROOT, "world", "build", "install", "world", "lib", "*")
    classes = [os.path.join(ROOT, "core", "build", "classes", kind, "main") for kind in ("kotlin", "java")]
    result = subprocess.run([JAVA, "-cp", os.pathsep.join(classes + [libraries]), "org.darkan.core.dungeon.DungeonPreviewKt", dungeon_id],
                            cwd=ROOT, capture_output=True, text=True)
    lines = [line for line in result.stdout.splitlines() if not line.startswith("[")]
    print("\n".join(lines) if lines else result.stderr)
    return result.returncode


def mobs(dungeon_id):
    path = os.path.join(VIEWS, f"{dungeon_id}.txt")
    if not os.path.isfile(path):
        print(f"{path} is written when the world starts; start world 22 once")
        return 1
    with open(path) as handle:
        print("".join(line for line in handle if line.startswith(("mob ", "boss ", "base level"))), end="")
    return 0


def harness():
    path = os.path.join(ROOT, ".claude", "skills", "city-maker", "scripts", "views.py")
    spec = importlib.util.spec_from_file_location("city_views", path)
    module = importlib.util.module_from_spec(spec)
    saved, sys.argv = sys.argv, [path]
    try:
        spec.loader.exec_module(module)
    except SystemExit:
        pass
    sys.argv = saved
    return module


def shoot(dungeon_id):
    dungeon = next((d for d in load() if d["id"] == dungeon_id), None)
    path = os.path.join(VIEWS, f"{dungeon_id}.txt")
    if dungeon is None or not os.path.isfile(path):
        print("unknown dungeon, or the world has not started since it was added")
        return 1
    x, y = base(dungeon["slot"])
    with open(path) as handle:
        points = [line.split()[:2] for line in handle if line.startswith(("mob ", "boss "))]
    spots = {kind: [int(v) for v in at.split(",")] for kind, at in reversed(points)}
    views = [
        ("surface", [dungeon["entranceX"], dungeon["entranceY"]], 2048, 900),
        ("hall-top", [x + spots["mob"][0], y + spots["mob"][1]], 2980, 1900),
        ("hall-tilt", [x + spots["mob"][0], y + spots["mob"][1]], 2048, 1500),
        ("boss-top", [x + spots["boss"][0], y + spots["boss"][1]], 2980, 2500),
        ("boss-tilt", [x + spots["boss"][0], y + spots["boss"][1]], 2048, 2000),
    ]
    views_module = harness()
    out = os.path.join(VIEWS, dungeon_id)
    work = os.path.join(out, "work")
    os.makedirs(work, exist_ok=True)
    entrance = {"theme": dungeon["theme"]}
    locs = [{"id": ENTRANCE_LOCS[entrance["theme"]], "shape": 10, "rotation": 0, "x": dungeon["entranceX"], "y": dungeon["entranceY"], "plane": 0}]
    for index, (name, tile, pitch, zoom) in enumerate(views):
        view = {"name": name, "tile": tile, "plane": 0, "yaw": 0, "pitch": pitch, "zoom": zoom, "roofs": 1, "map_size": 104}
        scene_id = f"DV-{index:03d}"
        side, _ = views_module.sidecar(view, locs)
        with open(os.path.join(work, f"{scene_id}.toml"), "w") as handle:
            handle.write(views_module.scene(view, scene_id))
        with open(os.path.join(work, f"{scene_id}.json"), "w") as handle:
            handle.write(side)
        command = ["xvfb-run", "-a", str(views_module.LAKAN), "--capture-scene", os.path.join(work, f"{scene_id}.toml"), "--out", work,
                   "--cache", str(views_module.CACHE), "--reference", work]
        run = subprocess.run(command, capture_output=True, text=True, timeout=300)
        made = sorted(name for name in os.listdir(work) if name.startswith(scene_id) and name.endswith(".png"))
        if run.returncode != 0 or not made:
            print(f"failed {name}: {(run.stderr or run.stdout).strip().splitlines()[-1:]}")
            continue
        shutil.move(os.path.join(work, made[0]), os.path.join(out, f"{name}.png"))
        print(f"wrote {os.path.join(out, name)}.png")
    return 0


def reseed(dungeon_id, seed):
    path = os.path.join(DUNGEONS, f"{dungeon_id}.json")
    with open(path) as handle:
        dungeon = json.load(handle)
    dungeon["seed"] = int(seed)
    with open(path, "w") as handle:
        json.dump(dungeon, handle, indent=2)
        handle.write("\n")
    return plan(dungeon_id)


def main(arguments):
    if not arguments or arguments[0] == "list":
        return listing()
    if arguments[0] == "validate":
        return validate()
    if arguments[0] == "plan" and len(arguments) == 2:
        return plan(arguments[1])
    if arguments[0] == "mobs" and len(arguments) == 2:
        return mobs(arguments[1])
    if arguments[0] == "shoot" and len(arguments) == 2:
        return shoot(arguments[1])
    if arguments[0] == "reseed" and len(arguments) == 3:
        return reseed(arguments[1], arguments[2])
    print("usage: dungeon.py list | validate | plan <id> | mobs <id> | shoot <id> | reseed <id> <seed>")
    return 2


sys.exit(main(sys.argv[1:]))

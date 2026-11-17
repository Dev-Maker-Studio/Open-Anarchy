import json
import os
import sys

ROOT = os.getcwd()
BOSSES = os.path.join(ROOT, "data", "dungeons", "bosses")
DUNGEONS = os.path.join(ROOT, "data", "dungeons")
ABILITIES = {
    "slam": ("everyTicks", "radius", "damage", "shout"),
    "summon": ("atHealth", "npc", "count", "shout"),
    "enrage": ("belowHealth", "damage", "shout"),
    "heal": ("everyTicks", "percent", "shout"),
}
REQUIRED = {"slam": (), "summon": ("atHealth", "npc"), "enrage": ("belowHealth", "damage"), "heal": ("percent",)}
FIELDS = ("id", "name", "npc", "level", "hitpoints", "damage", "respawnMinutes", "abilities", "drops")
DROP_FIELDS = ("item", "min", "max", "chance", "perLevel")
DEFAULT_BOSS = {"cave": "broodmother", "fortress": "warden", "sewer": "rat_king"}


def gamevals(kind):
    for folder in (os.environ.get("GAMEVAL_PATH"), os.path.join(ROOT, "..", "..", "..", "assets", "server-cache", "cities", "gamevals"),
                   os.path.join(ROOT, "cache-source", "gamevals")):
        if folder and os.path.isfile(os.path.join(folder, f"{kind}.json")):
            with open(os.path.join(folder, f"{kind}.json")) as handle:
                return set(json.load(handle)["entries"].values())
    sys.exit(f"no {kind}.json gameval catalog found; set GAMEVAL_PATH")


def load():
    bosses = {}
    for name in sorted(os.listdir(BOSSES)):
        if name.endswith(".json"):
            with open(os.path.join(BOSSES, name)) as handle:
                bosses[name[:-5]] = json.load(handle)
    return bosses


def users(boss_id):
    found = []
    for name in sorted(os.listdir(DUNGEONS)):
        path = os.path.join(DUNGEONS, name)
        if os.path.isfile(path) and name.endswith(".json"):
            with open(path) as handle:
                dungeon = json.load(handle)
            if (dungeon.get("boss") or DEFAULT_BOSS.get(dungeon["theme"])) == boss_id:
                found.append(dungeon["id"])
    return found


def listing():
    for boss_id, boss in load().items():
        abilities = ", ".join(ability["type"] for ability in boss.get("abilities", [])) or "none"
        print(f'{boss_id:14} {boss["name"]:22} npc {boss["npc"]:20} level x{boss.get("level", 1)} hp x{boss.get("hitpoints", 1)} '
              f'damage x{boss.get("damage", 1)} abilities {abilities}; used by {", ".join(users(boss_id)) or "no dungeon"}')


def validate():
    npcs = gamevals("npc")
    objs = gamevals("obj")
    problems = []
    for file_id, boss in load().items():
        if boss.get("id") != file_id:
            problems.append(f'{file_id}: "id" must equal the file name')
        for field in ("id", "name", "npc"):
            if not boss.get(field):
                problems.append(f"{file_id}: missing {field}")
        for field in boss:
            if field not in FIELDS:
                problems.append(f"{file_id}: unknown field {field}")
        if boss.get("npc") not in npcs:
            problems.append(f'{file_id}: npc {boss.get("npc")} is not an npc gameval name')
        for factor in ("level", "hitpoints", "damage"):
            if factor in boss and not 0 < boss[factor] <= 50:
                problems.append(f"{file_id}: {factor} must be above 0 and at most 50")
        for ability in boss.get("abilities", []):
            kind = ability.get("type")
            if kind not in ABILITIES:
                problems.append(f"{file_id}: ability type {kind} is not one of {', '.join(ABILITIES)}")
                continue
            for field in ability:
                if field != "type" and field not in ABILITIES[kind]:
                    problems.append(f"{file_id}: {kind} does not use {field}")
            for field in REQUIRED[kind]:
                if field not in ability:
                    problems.append(f"{file_id}: {kind} needs {field}")
            if kind == "summon" and ability.get("npc") not in npcs:
                problems.append(f'{file_id}: summon npc {ability.get("npc")} is not an npc gameval name')
            for threshold in ability.get("atHealth", []) + [ability.get("belowHealth", 50)]:
                if not 0 < threshold < 100:
                    problems.append(f"{file_id}: {kind} health thresholds are percentages between 1 and 99")
        for drop in boss.get("drops", []):
            if drop.get("item") not in objs:
                problems.append(f'{file_id}: drop item {drop.get("item")} is not an obj gameval name')
            for field in drop:
                if field not in DROP_FIELDS:
                    problems.append(f"{file_id}: drop does not use {field}")
    print("\n".join(problems) if problems else "OK")
    return 1 if problems else 0


def find(kind, text):
    matches = sorted(name for name in gamevals(kind) if text.lower() in name.lower())
    print("\n".join(matches[:60]) if matches else "no match")
    return 0


def main(arguments):
    if not arguments or arguments[0] == "list":
        return listing()
    if arguments[0] == "validate":
        return validate()
    if arguments[0] in ("npc", "obj") and len(arguments) == 2:
        return find(arguments[0], arguments[1])
    print("usage: boss.py list | validate | npc <text> | obj <text>")
    return 2


sys.exit(main(sys.argv[1:]))

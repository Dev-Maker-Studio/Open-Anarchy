#!/usr/bin/env python3
import glob
import json
import os
import re

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
NPC_DIR = os.path.join(ROOT, "cache-source", "config_npc")
COMBAT_DIR = os.path.join(ROOT, "data", "npcs", "combatdefs")
OUTPUT = os.path.join(ROOT, "data", "npcs", "hostile_mobs.json")

EXCLUDED_DIRS = {"quests", "random_events", "pest_control", "soul_wars", "fight_kiln", "familiars", "qbd", "elder_kiln", "hunter"}
EXCLUDED_NAME = re.compile(r"dummy|^null$|^$|impling|\bpet\b|training", re.IGNORECASE)


def combat_definitions():
    by_id, by_name = {}, {}
    for path in sorted(glob.glob(os.path.join(COMBAT_DIR, "**", "*.json"), recursive=True)):
        try:
            with open(path) as handle:
                data = json.load(handle)
        except ValueError:
            continue
        relative = os.path.relpath(path, COMBAT_DIR).split(os.sep)
        entry = {"excluded": relative[0] in EXCLUDED_DIRS, "hitpoints": data.get("hitpoints", 0)}
        for npc_id in data.get("ids", []):
            by_id.setdefault(npc_id, entry)
        for name in data.get("names", []):
            by_name.setdefault(name, entry)
    return by_id, by_name


def main():
    by_id, by_name = combat_definitions()
    mobs = {}
    for path in glob.glob(os.path.join(NPC_DIR, "*.json")):
        with open(path) as handle:
            npc = json.load(handle)
        name = npc.get("name")
        level = npc.get("combat", 0)
        options = [option for option in npc.get("options", []) if option]
        if not name or level <= 0 or "Attack" not in options or EXCLUDED_NAME.search(name):
            continue
        definition = by_id.get(npc["id"]) or by_name.get(name)
        if definition is None or definition["excluded"] or definition["hitpoints"] <= 1:
            continue
        key = (name.lower(), level)
        if key not in mobs or npc["id"] < mobs[key]["ID"]:
            mobs[key] = {"ID": npc["id"], "name": name.lower(), "level": level}
    result = sorted(mobs.values(), key=lambda mob: (mob["level"], mob["ID"]))
    with open(OUTPUT, "w") as handle:
        handle.write("[\n" + ",\n".join(" " + json.dumps(mob) for mob in result) + "\n]\n")
    print(len(result))


main()

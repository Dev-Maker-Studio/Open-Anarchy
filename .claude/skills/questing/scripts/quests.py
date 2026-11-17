import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
VIEWER = HERE.parent / "viewer"
QUESTS = Path("data/quests")
VIEWS = Path("data/quest-views")
WORLD = QUESTS / "world.json"
OBJ_CATALOG = Path("cache-source/gamevals/obj.json")

STEP_TYPES = ("kill", "boss", "dungeoneering", "gather", "deliver", "talk", "visit", "service", "choice")
LENGTHS = {"short": (1, 2), "medium": (3, 4), "long": (5, 10)}
RETURN = "return"
PLAIN_ROLES = ("banker", "barber", "realtor")
NODE_TYPE = {
    "kill": "kill_npc", "boss": "kill_npc", "dungeoneering": "puzzle", "gather": "item", "deliver": "use_item",
    "talk": "talk_to_npc", "visit": "travel", "service": "object_interact", "choice": "choice",
}


class World:
    def __init__(self):
        if not WORLD.is_file():
            sys.exit(f"{WORLD} is missing: start world 22 once so it exports the world facts")
        data = json.loads(WORLD.read_text())
        self.data = data
        self.areas = {a["id"]: a for a in data["areas"]}
        self.dungeons = {d["id"]: d for d in data["dungeons"]}
        self.biomes = set(data["biomes"])
        self.skills = set(data["skills"])
        self.gather = {g["item"]: g for g in data["gatherItems"]}
        self.objs = set(json.loads(OBJ_CATALOG.read_text())["entries"].values()) if OBJ_CATALOG.is_file() else None

    def giver_ok(self, giver):
        return isinstance(giver, dict) and giver.get("giver") in self.areas.get(giver.get("area"), {}).get("givers", [])

    def place_ok(self, place):
        if not isinstance(place, dict):
            return False
        if "dungeon" in place:
            return place["dungeon"] in self.dungeons
        if "biome" in place:
            return place["biome"].lower() in self.biomes
        if "area" in place:
            return place["area"] in self.areas
        return "x" in place and "y" in place

    def obj_ok(self, name):
        return isinstance(name, str) and (self.objs is None or name in self.objs)


def role(giver):
    return giver if giver in PLAIN_ROLES else giver.replace("_", " ") + " keeper"


def item_name(name):
    return (name or "item").replace("_", " ")


def load(area=None):
    quests = []
    folders = [QUESTS / area] if area else sorted(p for p in QUESTS.iterdir() if p.is_dir())
    for folder in folders:
        for file in sorted(folder.glob("*.json")):
            quests.extend((file, q) for q in read(file))
    return quests


def read(file):
    data = json.loads(Path(file).read_text())
    return data["quests"] if isinstance(data, dict) and "quests" in data else [data]


def after(quest, step):
    if step.get("next"):
        return step["next"]
    steps = quest["steps"]
    index = steps.index(step)
    return steps[index + 1]["id"] if index + 1 < len(steps) else RETURN


def targets(quest, step):
    if step["type"] == "choice":
        return [o.get("next") for o in step.get("options", [])]
    return [after(quest, step)]


def longest(quest):
    by_id = {s["id"]: s for s in quest["steps"]}
    memo = {}

    def depth(step_id, trail):
        if step_id == RETURN:
            return 0
        if step_id in trail or step_id not in by_id:
            return None
        if step_id in memo:
            return memo[step_id]
        depths = [depth(t, trail | {step_id}) for t in targets(quest, by_id[step_id])]
        if not depths or any(d is None for d in depths):
            return None
        memo[step_id] = 1 + max(depths)
        return memo[step_id]

    return depth(quest["steps"][0]["id"], frozenset()) if quest.get("steps") else None


def check(world, quest, seen):
    errors, warnings = [], []
    for key in ("id", "name", "area", "giver", "length", "summary", "intro", "steps"):
        if not quest.get(key):
            errors.append(f"missing '{key}'")
    if errors:
        return errors, warnings
    if quest["id"] in seen:
        errors.append("the id is already used")
    seen.add(quest["id"])
    area = world.areas.get(quest["area"])
    source = {"area": quest["area"], "giver": quest["giver"]}
    if not world.giver_ok(source):
        errors.append(f"giver '{quest['giver']}' is not a service npc of '{quest['area']}'")
    if quest["length"] not in LENGTHS:
        errors.append(f"length '{quest['length']}' is unknown")
    ids = [s.get("id") for s in quest["steps"]]
    if len(ids) != len(set(ids)) or None in ids:
        errors.append("step ids are missing or repeat")
    if RETURN in ids:
        errors.append("'return' is reserved")
    tier = area["tier"] if area else 1
    for index, step in enumerate(quest["steps"]):
        sid, kind = step.get("id"), step.get("type")

        def later(target):
            return target is None or target == RETURN or (target in ids and ids.index(target) > index)

        if not later(step.get("next")):
            errors.append(f"step {sid}: next '{step.get('next')}' is not a later step")
        to = step.get("to")
        if kind == "kill":
            if "where" in step and not world.place_ok(step["where"]):
                errors.append(f"step {sid}: where is unknown")
            mob = step.get("mob")
            dungeon = (step.get("where") or {}).get("dungeon")
            known = world.dungeons[dungeon]["mobs"] if dungeon in world.dungeons else (area or {}).get("localMobs", [])
            if mob and mob.lower() not in known:
                warnings.append(f"step {sid}: '{mob}' is not listed for {'dungeon ' + dungeon if dungeon else 'the land around ' + quest['area']}")
        elif kind == "boss":
            if step.get("dungeon") not in world.dungeons:
                errors.append(f"step {sid}: dungeon '{step.get('dungeon')}' is unknown")
        elif kind == "dungeoneering":
            pass
        elif kind in ("gather", "deliver"):
            if not world.obj_ok(step.get("item")):
                errors.append(f"step {sid}: item '{step.get('item')}' is no obj gameval")
            if kind == "gather":
                known = world.gather.get(step.get("item"))
                if known is None:
                    warnings.append(f"step {sid}: '{step.get('item')}' is not in the list of items the world is known to produce")
                elif known["minTier"] > tier + 2:
                    warnings.append(f"step {sid}: '{step['item']}' suits tier {known['minTier']}, this area is tier {tier}")
                if to is not None and not world.giver_ok(to):
                    errors.append(f"step {sid}: to is no service npc")
            else:
                if not world.giver_ok(to):
                    errors.append(f"step {sid}: to is no service npc")
                if to == source:
                    errors.append(f"step {sid}: a delivery cannot go back to the giver")
        elif kind == "talk":
            if not world.giver_ok(to):
                errors.append(f"step {sid}: to is no service npc")
            if not step.get("lines"):
                errors.append(f"step {sid}: lines are empty")
        elif kind == "service":
            if not world.giver_ok(to):
                errors.append(f"step {sid}: to is no service npc")
        elif kind == "visit":
            if not world.place_ok(step.get("place")):
                errors.append(f"step {sid}: place is unknown")
        elif kind == "choice":
            options = step.get("options") or []
            if not 2 <= len(options) <= 4:
                errors.append(f"step {sid}: a choice has 2 to 4 options")
            if to is not None and not world.giver_ok(to):
                errors.append(f"step {sid}: to is no service npc")
            for option in options:
                if not option.get("text") or not later(option.get("next")) or option.get("next") is None:
                    errors.append(f"step {sid}: option '{option.get('text')}' needs text and a later step")
        else:
            errors.append(f"step {sid}: type '{kind}' is unknown")
        if kind in ("kill", "dungeoneering", "gather") and step.get("amount", 1) < 1:
            errors.append(f"step {sid}: amount must be at least 1")
    depth = longest(quest) if not errors else None
    if not errors and depth is None:
        errors.append("steps loop or a branch never returns")
    elif depth is not None and quest["length"] in LENGTHS:
        low, high = LENGTHS[quest["length"]]
        if not low <= depth <= high:
            errors.append(f"a {quest['length']} quest takes {low} to {high} steps, this takes {depth}")
    reachable, stack = set(), [quest["steps"][0]["id"]]
    by_id = {s["id"]: s for s in quest["steps"]}
    while stack and not errors:
        current = stack.pop()
        if current in reachable or current == RETURN:
            continue
        reachable.add(current)
        stack.extend(targets(quest, by_id[current]))
    if not errors and len(reachable) != len(ids):
        warnings.append(f"steps never reached: {', '.join(i for i in ids if i not in reachable)}")
    rewards = quest.get("rewards") or {}
    for skill in rewards.get("xp") or []:
        if skill.lower() not in world.skills:
            errors.append(f"xp skill '{skill}' is unknown")
    for item in rewards.get("items") or []:
        if not world.obj_ok(item.get("item")):
            errors.append(f"reward item '{item.get('item')}' is no obj gameval")
    for other in (quest.get("requires") or {}).get("quests") or []:
        if other == quest["id"]:
            errors.append("a quest cannot require itself")
    return errors, warnings


def title(world, giver):
    return f"the {role(giver['giver'])} in {world.areas.get(giver['area'], {}).get('name', giver['area'])}"


def place_name(world, place):
    if not place:
        return "the wilds"
    if "dungeon" in place:
        return world.dungeons.get(place["dungeon"], {}).get("name", place["dungeon"])
    if "biome" in place:
        return "the " + place["biome"].replace("_", " ")
    if "area" in place:
        return world.areas.get(place["area"], {}).get("name", place["area"])
    return f"the marked spot ({place.get('x')}, {place.get('y')})"


def describe(world, quest, step):
    if step.get("journal"):
        return step["journal"]
    kind, amount = step["type"], step.get("amount", 1)
    to = step.get("to") or {"area": quest["area"], "giver": quest["giver"]}
    if kind == "kill":
        where = f" in {place_name(world, step['where'])}" if step.get("where") else ""
        return f"Kill {amount} {step.get('mob') or 'hostile creatures'}{where}."
    if kind == "boss":
        dungeon = world.dungeons.get(step.get("dungeon"), {})
        return f"Defeat {dungeon.get('boss', 'the boss')} in {dungeon.get('name', step.get('dungeon'))}."
    if kind == "dungeoneering":
        return f"Complete {amount} dungeoneering floor{'s' if amount != 1 else ''} at a dungeoneering cave."
    if kind == "gather":
        return f"Bring {amount} {item_name(step.get('item'))} to {title(world, to)}."
    if kind == "deliver":
        return f"Carry the {item_name(step.get('item'))} to {title(world, to)}."
    if kind == "talk":
        return f"Speak to {title(world, to)}."
    if kind == "service":
        return f"Call on {title(world, to)} and use their service."
    if kind == "visit":
        return f"Travel to {place_name(world, step.get('place'))}."
    return f"Decide with {title(world, to)}: {step.get('prompt', 'how to go on')}"


def reward_lines(world, quest):
    reward = world.areas.get(quest["area"], {}).get("rewards", {}).get(quest["length"])
    if not reward:
        return ["Scaled by the area's tier"]
    skills = (quest.get("rewards") or {}).get("xp") or []
    lines = [f"{reward['questPoints']} quest point{'s' if reward['questPoints'] != 1 else ''}", f"{reward['coins']:,} coins"]
    lines += [f"{reward['xp'] // len(skills):,} {skill.capitalize()} XP" for skill in skills]
    lines += [f"{item.get('amount', 1)} x {item_name(item['item'])}" for item in (quest.get("rewards") or {}).get("items") or []]
    return lines


def lines_to_transcript(lines, speaker):
    result = []
    for line in lines or []:
        if line.lower().startswith("player:"):
            result.append({"speaker": "Player", "text": line[7:].strip()})
        else:
            result.append({"speaker": speaker, "text": line})
    return result


def says(transcript):
    return [{"say": line["speaker"], "text": line["text"]} for line in transcript]


def to_tree(world, quest):
    source = {"area": quest["area"], "giver": quest["giver"]}
    area = world.areas.get(quest["area"], {})
    giver_name = role(quest["giver"]).capitalize()
    npcs = {}

    def npc(giver):
        key = f"npc_{giver['area']}_{giver['giver']}"
        if key not in npcs:
            npcs[key] = {
                "id": key, "name": f"{role(giver['giver']).capitalize()} ({world.areas.get(giver['area'], {}).get('name', giver['area'])})",
                "location": world.areas.get(giver["area"], {}).get("name", giver["area"]), "onPath": True, "stageDependent": True,
                "summary": "A service NPC who cannot be attacked.", "variants": [],
            }
        return npcs[key]

    def variant(giver, when, stages, steps):
        entry = npc(giver)
        entry["variants"].append({"id": f"v_{entry['id']}_{len(entry['variants'])}", "when": when, "stages": stages, "steps": steps})

    steps = quest["steps"]
    stage_of = {s["id"]: i + 1 for i, s in enumerate(steps)}
    return_stage = len(steps) + 1
    done_stage = return_stage + 1
    accept = quest.get("accept") or "I'll do it."
    intro = lines_to_transcript(quest["intro"], giver_name)
    outro = lines_to_transcript(quest.get("outro") or ["Well done. You have earned this."], giver_name)
    rewards = reward_lines(world, quest)
    nodes, edges = [], []
    requires = quest.get("requires") or {}
    requirements = [f"{requires['questPoints']} quest points"] if requires.get("questPoints") else []
    requirements += [f"Completed: {other}" for other in requires.get("quests") or []]
    first = "n_start"
    if requirements:
        nodes.append({"id": "n_req", "type": "requirement", "label": "Requirements", "stage": 0, "description": "The giver only offers this once you have: " + "; ".join(requirements) + "."})
        edges.append({"from": "n_req", "to": first, "label": "Meet the requirements", "kind": "progress"})
    nodes.append({
        "id": first, "type": "quest_start", "label": f"Talk to {title(world, source)}"[:60], "stage": 0, "location": area.get("name"),
        "npc": npc(source)["name"], "npcRef": npc(source)["id"],
        "description": f"Talk to {title(world, source)} and pick '{quest['name']}'. Hearing the offer adds the quest to the quest tab in red.",
        "transcript": intro,
    })
    nodes.append({"id": "n_offer", "type": "choice", "label": f"Start {quest['name']}?"[:60], "stage": 0, "npc": npc(source)["name"], "npcRef": npc(source)["id"],
                  "description": "Accept to start the quest (it turns yellow in the quest tab), or decline and come back later."})
    nodes.append({"id": "n_decline", "type": "detour", "label": "Not right now", "stage": 0, "description": "The quest stays in the quest tab in red. Talk to the giver again to start it."})
    nodes.append({"id": "n_started", "type": "started", "label": "Quest started", "stage": 1, "description": describe(world, quest, steps[0])})
    edges += [
        {"from": first, "to": "n_offer", "label": "Hear the offer", "kind": "progress"},
        {"from": "n_offer", "to": "n_started", "label": f"Say: {accept}", "kind": "choice"},
        {"from": "n_offer", "to": "n_decline", "label": "Say: Not right now.", "kind": "detour"},
        {"from": "n_decline", "to": first, "label": "Talk to the giver again", "kind": "detour"},
        {"from": "n_started", "to": f"n_{steps[0]['id']}", "label": "Set out", "kind": "progress"},
    ]
    variant(source, "Stage 0: quest not started", [0], says(intro) + [{"choice": [
        {"option": accept, "steps": [{"effect": "The quest starts and turns yellow in the quest tab."}]},
        {"option": "Not right now.", "steps": [{"end": "The quest stays in the quest tab in red."}]},
    ]}])

    def node_id(target):
        return "n_return" if target == RETURN else f"n_{target}"

    for step in steps:
        kind = step["type"]
        to = step.get("to") or source
        text = describe(world, quest, step)
        node = {"id": f"n_{step['id']}", "type": NODE_TYPE[kind], "label": text.rstrip(".")[:60], "stage": stage_of[step["id"]], "description": text}
        if kind in ("talk", "deliver", "gather", "service", "choice"):
            node["npc"], node["npcRef"], node["location"] = npc(to)["name"], npc(to)["id"], npc(to)["location"]
        speaker = role(to["giver"]).capitalize()
        if kind == "talk":
            node["transcript"] = lines_to_transcript(step.get("lines"), speaker)
            variant(to, f"Stage {stage_of[step['id']]}: {quest['name']}", [stage_of[step["id"]]], says(node["transcript"]) + [{"effect": "The quest moves on."}])
        elif kind in ("deliver", "gather"):
            amount = 1 if kind == "deliver" else step.get("amount", 1)
            default = [f"player: Here you are, {amount} {item_name(step.get('item'))}.", "Just what was needed. Thank you."]
            node["transcript"] = lines_to_transcript(step.get("lines") or default, speaker)
            node["description"] = text + (" The giver hands you the item when this step starts; ask them again if you lose it." if kind == "deliver" else " The items are taken from your inventory when you talk to them.")
            variant(to, f"Stage {stage_of[step['id']]}: {quest['name']}", [stage_of[step["id"]]], [{"choice": [
                {"option": f"If the player holds {amount} {item_name(step.get('item'))}", "condition": True,
                 "steps": says(node["transcript"]) + [{"effect": f"Takes {amount} {item_name(step.get('item'))}; the quest moves on."}]},
                {"option": "Otherwise", "condition": True, "steps": [{"say": speaker, "text": f"I am waiting on {amount} {item_name(step.get('item'))}."}, {"end": "Come back with the items."}]},
            ]}])
        elif kind == "choice":
            node["label"] = (step.get("prompt") or "Decide how to go on")[:60]
            node["transcript"] = lines_to_transcript(step.get("lines"), speaker)
            variant(to, f"Stage {stage_of[step['id']]}: {quest['name']}", [stage_of[step["id"]]], says(node["transcript"]) + [{"choice": [
                {"option": o["text"], "steps": [{"effect": f"Next: {describe(world, quest, next(s for s in steps if s['id'] == o['next'])) if o['next'] != RETURN else 'return to the giver'}"}]}
                for o in step.get("options", [])
            ]}])
        elif kind in ("kill", "boss"):
            node["npc"] = step.get("mob") or world.dungeons.get(step.get("dungeon"), {}).get("boss") or "hostile creatures"
            place = step.get("where") or ({"dungeon": step["dungeon"]} if step.get("dungeon") else None)
            node["location"] = place_name(world, place)
            level = world.dungeons.get((place or {}).get("dungeon"), {}).get("level")
            node["description"] = text + (f" The dungeon's base level is {level}; creatures grow stronger with depth." if level else f" Creatures here are scaled to the area's tier ({area.get('tier')}).")
        elif kind == "visit":
            node["location"] = place_name(world, step.get("place"))
        nodes.append(node)
        if kind == "choice":
            for option in step.get("options", []):
                edges.append({"from": node["id"], "to": node_id(option["next"]), "label": f"Say: {option['text']}", "kind": "choice"})
        else:
            edges.append({"from": node["id"], "to": node_id(after(quest, step)), "label": text.rstrip("."), "kind": "progress"})
    nodes.append({"id": "n_return", "type": "talk_to_npc", "label": f"Return to {title(world, source)}"[:60], "stage": return_stage, "location": area.get("name"),
                  "npc": npc(source)["name"], "npcRef": npc(source)["id"], "description": f"Go back to {title(world, source)} to finish the quest.", "transcript": outro})
    nodes.append({"id": "n_reward", "type": "reward", "label": "Rewards", "stage": done_stage, "description": "; ".join(rewards) + "."})
    nodes.append({"id": "n_complete", "type": "quest_complete", "label": "Quest complete", "stage": done_stage,
                  "description": "The quest turns green in the quest tab and its quest points are added. Quest points have no upper limit."})
    edges += [{"from": "n_return", "to": "n_reward", "label": "Hand in", "kind": "progress"}, {"from": "n_reward", "to": "n_complete", "label": "Quest complete", "kind": "progress"}]
    variant(source, "While the quest is in progress", sorted(stage_of.values()), [{"say": giver_name, "text": "Back again? How is the work coming along?"}, {"end": "Repeats the current journal line."}])
    variant(source, f"Stage {return_stage}: every step is done", [return_stage], says(outro) + [{"effect": "Quest complete: " + "; ".join(rewards) + "."}])
    stages = [{"stage": 0, "title": "Not started", "journal": f"I can start this quest by speaking to {title(world, source)}."}]
    stages += [{"stage": stage_of[s["id"]], "title": s["type"].capitalize(), "journal": describe(world, quest, s)} for s in steps]
    stages += [{"stage": return_stage, "title": "Return", "journal": f"Return to {title(world, source)}."}, {"stage": done_stage, "title": "Complete", "journal": "QUEST COMPLETE!"}]
    return {
        "quest": quest["id"].upper(), "name": quest["name"], "enabled": True, "handlerClass": "AreaQuests (data driven)",
        "sourceFiles": ["AreaQuests.kt", "AreaQuestSpec.kt"], "startNpc": npc(source)["name"], "startLocation": area.get("name"),
        "completedStage": done_stage, "questPoints": area.get("rewards", {}).get(quest["length"], {}).get("questPoints"),
        "summary": f"{quest['summary']} ({quest['length']} quest, tier {area.get('tier')})", "requirements": requirements, "rewards": rewards,
        "stages": stages, "nodes": nodes, "edges": edges, "npcs": list(npcs.values()),
    }


def tree_problems(tree):
    ids = {n["id"] for n in tree["nodes"]}
    problems = [f"edge to unknown node {e[k]}" for e in tree["edges"] for k in ("from", "to") if e[k] not in ids]
    adjacent = {}
    for edge in tree["edges"]:
        adjacent.setdefault(edge["from"], []).append(edge["to"])
    seen, stack = set(), [tree["nodes"][0]["id"]]
    while stack:
        current = stack.pop()
        if current not in seen:
            seen.add(current)
            stack.extend(adjacent.get(current, []))
    return problems + [f"node {i} unreachable" for i in ids - seen]


def render(tree):
    template = (VIEWER / "template.html").read_text(encoding="utf-8")
    template = template.replace('<script src="../_tree_viz/three.min.js"></script>', '<script src="../three.min.js"></script>')
    payload = json.dumps(tree, ensure_ascii=False).replace("</", "<\\/")
    return template.replace("__TITLE__", tree["name"].replace("<", "&lt;")).replace("__DATA__", payload)


def cmd_validate(args):
    world = World()
    quests = [(Path(a), q) for a in args for q in read(a)] if args and Path(args[0]).is_file() else load(args[0] if args else None)
    seen, failed, warned = set(), 0, 0
    if args and Path(args[0]).is_file():
        seen = {q["id"] for f, q in load() if f.resolve() not in {Path(a).resolve() for a in args}}
    for file, quest in quests:
        errors, warnings = check(world, quest, seen)
        for message in errors:
            print(f"ERROR {file} {quest.get('id')}: {message}")
        for message in warnings:
            print(f"warn  {file} {quest.get('id')}: {message}")
        failed += bool(errors)
        warned += bool(warnings)
    print(f"{len(quests)} quests, {failed} with errors, {warned} with warnings" if failed or warned else f"OK ({len(quests)} quests)")
    return 1 if failed else 0


def cmd_list(args):
    world = World()
    for area in ([args[0]] if args else sorted(world.areas)):
        info = world.areas[area]
        quests = [q for _, q in load(area)] if (QUESTS / area).is_dir() else []
        points = sum(info["rewards"][q["length"]]["questPoints"] for q in quests if q.get("length") in info["rewards"])
        print(f"{info['name']} ({area}) tier {info['tier']}: {len(quests)} quests, {points} quest points")
        for quest in quests:
            path = " > ".join(s["type"] for s in quest["steps"])
            print(f"  {quest['length']:<6} {quest['giver']:<14} {quest['id']:<44} {quest['name']} [{path}]")
    return 0


def cmd_show(args):
    world = World()
    quest = next((q for _, q in load() if q["id"] == args[0]), None)
    if quest is None:
        sys.exit(f"no quest '{args[0]}'")
    print(f"{quest['name']} ({quest['id']}), {quest['length']}, from {title(world, quest)}")
    print(quest["summary"])
    for step in quest["steps"]:
        tail = " | ".join(f"'{o['text']}' -> {o['next']}" for o in step.get("options", [])) if step["type"] == "choice" else f"-> {after(quest, step)}"
        print(f"  {step['id']:<4} {step['type']:<13} {describe(world, quest, step)} {tail}")
    print("  rewards: " + "; ".join(reward_lines(world, quest)))
    return 0


def cmd_html(args):
    world = World()
    VIEWS.mkdir(parents=True, exist_ok=True)
    (VIEWS / "three.min.js").write_text((VIEWER / "three.min.js").read_text(encoding="utf-8"), encoding="utf-8")
    rows, failed = [], 0
    for area in ([args[0]] if args else sorted(world.areas)):
        if not (QUESTS / area).is_dir():
            continue
        (VIEWS / area).mkdir(exist_ok=True)
        seen = set()
        for _, quest in load(area):
            errors, _ = check(world, quest, seen)
            if errors:
                failed += 1
                print(f"skipped {quest.get('id')}: {errors[0]}")
                continue
            tree = to_tree(world, quest)
            problems = tree_problems(tree)
            if problems:
                failed += 1
                print(f"skipped {quest['id']}: {problems[0]}")
                continue
            (VIEWS / area / f"{quest['id']}.html").write_text(render(tree), encoding="utf-8")
            rows.append((world.areas[area], quest, tree))
    index = ["<!doctype html><meta charset='utf-8'><title>Area quests</title>",
             "<style>body{font:14px system-ui;background:#14161a;color:#ddd;margin:16px}a{color:#7cc4ff}table{border-collapse:collapse;width:100%}"
             "td,th{padding:4px 8px;border-bottom:1px solid #2a2e36;text-align:left}h2{margin:24px 0 6px}.short{color:#8fd68f}.medium{color:#e6d36a}.long{color:#f08a6a}</style>",
             "<h1>Area quest decision trees</h1>"]
    current = None
    for info, quest, tree in rows:
        if info["id"] != current:
            if current is not None:
                index.append("</table>")
            current = info["id"]
            index.append(f"<h2>{info['name']} (tier {info['tier']})</h2><table><tr><th>Quest</th><th>Giver</th><th>Length</th><th>Path</th><th>Rewards</th></tr>")
        path = " &rarr; ".join(s["type"] for s in quest["steps"])
        index.append(f"<tr><td><a href='{info['id']}/{quest['id']}.html'>{quest['name']}</a></td><td>{role(quest['giver'])}</td>"
                     f"<td class='{quest['length']}'>{quest['length']}</td><td>{path}</td><td>{'; '.join(tree['rewards'])}</td></tr>")
    index.append("</table>")
    (VIEWS / "index.html").write_text("\n".join(index), encoding="utf-8")
    print(f"{len(rows)} trees written to {VIEWS}/, index at {VIEWS / 'index.html'}" + (f", {failed} skipped" if failed else ""))
    return 1 if failed else 0


def cmd_tree(args):
    world = World()
    quest = next((q for _, q in load() if q["id"] == args[0]), None)
    if quest is None:
        sys.exit(f"no quest '{args[0]}'")
    print(json.dumps(to_tree(world, quest), indent=1, ensure_ascii=False))
    return 0


def cmd_brief(args):
    world = World()
    if not args or args[0] not in world.areas:
        sys.exit("usage: brief <area> [count]; areas: " + ", ".join(sorted(world.areas)))
    info = world.areas[args[0]]
    count = args[1] if len(args) > 1 else "6"
    existing = [q for _, q in load(args[0])] if (QUESTS / args[0]).is_dir() else []
    dungeons = [world.dungeons[d] for d in [info.get("sewer")] + info.get("wildDungeons", []) if d in world.dungeons]
    neighbours = [world.areas[n] for n in info.get("neighbours", []) if n in world.areas]
    gather = [g["item"] for g in world.data["gatherItems"] if g["minTier"] <= info["tier"] + 1]
    print(f"""Write {count} new quests for the city of {info['name']} (area id "{info['id']}", tier {info['tier']}) in the Darkan generated world.
Return one JSON object: {{"area": "{info['id']}", "quests": [ ... ]}} and nothing else. Follow the quest schema below exactly.

FACTS (use only these ids and names)
- Quest givers here (invulnerable service NPCs): {', '.join(info['givers'])}
- Hostile creatures around the city (kill.mob): {', '.join(info['localMobs']) or 'none listed; leave mob out'}
- Dungeons: {'; '.join(f"{d['id']} = {d['name']} (level {d['level']}, boss {d['boss']}, creatures: {', '.join(d['mobs'][:6])})" for d in dungeons)}
- Other cities: {'; '.join(f"{n['id']} = {n['name']} (tier {n['tier']}; givers: {', '.join(n['givers'])})" for n in neighbours)}
- Items the world produces at this tier (gather.item): {', '.join(gather)}
- Parcel items (deliver.item): {', '.join(world.data['parcels'])}
- Biomes (visit.place.biome, kill.where.biome): {', '.join(sorted(world.biomes))}
- XP skills (rewards.xp): {', '.join(sorted(world.skills))}
- Rewards are worked out by the game from length and tier: {json.dumps(info['rewards'])}. Never write coin, xp or quest point numbers.
- Quest names already used here: {'; '.join(q['name'] for q in existing) or 'none'}

RULES
- id is "{info['id']}_<giver>_<slug>" in lower snake case and unique. giver is one of the givers above.
- length: short = 1-2 steps, medium = 3-4, long = 5-10 (count the longest path, not the hand-in). Write a mix of all three.
- Step types: kill, boss, dungeoneering, gather, deliver, talk, visit, service, choice. Mix them; a long quest has a boss or dungeoneering step and at least one other city or dungeon.
- Amounts fit tier {info['tier']}: about {round(5 * (1 + (info['tier'] - 1) * 0.2))} kills or {round(8 * (1 + (info['tier'] - 1) * 0.2))} gathered items for a short quest, more for longer ones.
- Every quest ends by returning to its giver; do not add that as a step.
- Dialogue lines are plain strings spoken by the NPC; start a line with "player: " for the player. Keep each under 200 characters. Original writing only, in the plain tone of a medieval town.
- Steps run in order unless "next" names a later step id. A choice step lists options, each with "text" and "next".

SCHEMA
{(HERE.parent / 'SCHEMA.md').read_text()}""")
    return 0


COMMANDS = {"validate": cmd_validate, "list": cmd_list, "show": cmd_show, "html": cmd_html, "tree": cmd_tree, "brief": cmd_brief}


def main(argv):
    if not argv or argv[0] not in COMMANDS:
        print("usage: quests.py <" + "|".join(COMMANDS) + "> [args]  (run from the server root)")
        return 2
    return COMMANDS[argv[0]](argv[1:])


sys.exit(main(sys.argv[1:]))

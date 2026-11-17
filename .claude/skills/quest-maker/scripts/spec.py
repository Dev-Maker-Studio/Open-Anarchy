import json
import re

VERSION = "3.0"
KINDS = ("place", "biome", "city", "npc_talk", "item_get", "item_give", "item_use", "mob_kill", "object_interact")
BIOMES = ("plains", "winter", "wilderness", "forest", "desert", "ocean", "mountain", "volcano", "rune_essence")
STATS = ("attack", "defense", "strength", "hitpoints", "range", "prayer", "magic", "cooking", "woodcutting", "fletching", "fishing", "firemaking", "crafting", "smithing", "mining", "herblore", "agility", "thieving", "slayer", "farming", "runecrafting", "hunter", "construction", "summoning", "dungeoneering")
LANE = {"place": "place", "npc_talk": "place", "object_interact": "place", "biome": "biome", "city": "city", "item_get": "item", "item_give": "item", "item_use": "item", "mob_kill": "mob"}
REQUIREMENTS = {"skill": ("stat", "level"), "quest": ("quest",), "item": ("obj", "amount"), "questPoints": ("amount",)}
REWARDS = {"xp": ("stat", "amount"), "coins": ("amount",), "item": ("obj", "amount"), "questPoints": ("amount",)}
ACTIONS = {"give_item": ("obj", "amount"), "take_item": ("obj", "amount"), "set_stage": ("stage",), "give_xp": ("stat", "amount"), "give_coins": ("amount",), "complete_quest": ()}
NEED = {"place": (), "biome": (), "city": (), "npc_talk": ("npc", "steps"), "item_get": ("obj", "amount"), "item_give": ("obj", "amount", "to"), "item_use": ("obj", "amount", "on"), "mob_kill": ("npc", "amount"), "object_interact": ("loc", "option")}
GAMEVAL = {"npc": "Npc", "obj": "Obj", "loc": "Loc", "startNpc": "Npc", "has_item": "Obj"}
TOP = ("generatorVersion", "quest", "name", "summary", "startNpc", "requirements", "rewards", "stages", "hops")


def isint(v):
    return isinstance(v, int) and not isinstance(v, bool)


def load(path):
    with open(path) as f:
        return json.load(f)


def gamevals(folder, hostile=None):
    entries = {t: json.load(open(f"{folder}/{t}.json"))["entries"] for t in ("npc", "obj", "loc")}
    names = {k.capitalize(): set(v.values()) for k, v in entries.items()}
    names["mobs"] = {entries["npc"].get(str(m["ID"])) for m in json.load(open(hostile))} if hostile else None
    return names


def quest_names(path):
    return set(re.findall(r"^\s+([A-Z][A-Z0-9_]*)\(\d+,", open(path).read(), re.M))


def targets(hop):
    return [n if isinstance(n, str) else n.get("to") for n in hop.get("next", [])]


def trigger(hop):
    k = hop["kind"]
    if k == "npc_talk":
        return ("click", hop["npc"])
    if k == "object_interact":
        return ("click", hop["loc"])
    if k == "mob_kill":
        return ("kill", hop["npc"])
    if k == "item_get":
        src = hop.get("from")
        if src:
            return ("kill", src["npc"]) if "npc" in src else ("click", src["loc"])
        return ("add", hop["obj"])
    if k in ("item_give", "item_use"):
        dst = hop.get("to") or hop.get("on")
        return ("onnpc", dst["npc"]) if "npc" in dst else ("onloc", dst["loc"])
    return ("chunk", "")


def hooked(hop):
    if hop["kind"] == "npc_talk":
        return True
    if hop["stage"] == 0:
        return False
    return hop["kind"] not in ("place", "biome", "city") or "x" in hop["at"]


def walk(x):
    if isinstance(x, dict):
        for k, v in x.items():
            if k in GAMEVAL and isinstance(v, str):
                yield GAMEVAL[k], v
            yield from walk(v)
    elif isinstance(x, list):
        for v in x:
            yield from walk(v)


def typed(items, table, name, e):
    if not isinstance(items, list):
        e.append(f"{name}: must be a list")
        return
    for i, x in enumerate(items):
        fields = table.get(x.get("type")) if isinstance(x, dict) else None
        if fields is None:
            e.append(f"{name}[{i}]: type must be one of {sorted(table)}")
            continue
        e += [f"{name}[{i}]: missing {k}" for k in fields if k not in x]
        if "stat" in fields and x.get("stat") not in STATS:
            e.append(f"{name}[{i}]: unknown stat {x.get('stat')}")
        e += [f"{name}[{i}]: {k} must be an integer" for k in ("amount", "level") if k in fields and not isint(x.get(k))]


def steps(items, path, e, acts, empty=False):
    if not isinstance(items, list) or not (items or empty):
        e.append(f"{path}: steps must be a non-empty list")
        return
    for i, s in enumerate(items):
        p = f"{path}[{i}]"
        kind = s.get("step") if isinstance(s, dict) else None
        if kind == "say":
            if s.get("who") not in ("npc", "player"):
                e.append(f"{p}: who must be npc or player")
            if not re.fullmatch(r"[A-Z][A-Z_]*", str(s.get("head", ""))):
                e.append(f"{p}: head must be a HeadE constant")
            if not s.get("text"):
                e.append(f"{p}: text is empty")
        elif kind == "options":
            ops = s.get("options")
            if not isinstance(ops, list) or not ops:
                e.append(f"{p}: options must be a non-empty list")
                continue
            for j, o in enumerate(ops):
                if not o.get("text"):
                    e.append(f"{p}.options[{j}]: text is empty")
                steps(o.get("steps", []), f"{p}.options[{j}].steps", e, acts, True)
        elif kind == "action":
            fields = ACTIONS.get(s.get("type"))
            if fields is None:
                e.append(f"{p}: action type must be one of {sorted(ACTIONS)}")
                continue
            args = s.get("args", {})
            e += [f"{p}: missing arg {k}" for k in fields if k not in args]
            if "stat" in fields and args.get("stat") not in STATS:
                e.append(f"{p}: unknown stat {args.get('stat')}")
            acts.append((s["type"], args))
        elif kind != "end":
            e.append(f"{p}: step must be say, options, action or end")


def check_at(at, p, e):
    if not isinstance(at, dict):
        e.append(f"{p}: at is required")
        return
    coords = [k for k in ("x", "y", "plane") if k in at]
    if coords and len(coords) != 3 or any(not isint(at[k]) for k in coords):
        e.append(f"{p}: x, y and plane must all be integers")
    if "city" in at and "biome" in at:
        e.append(f"{p}: use either city or biome")
    if not ("city" in at or "biome" in at or len(coords) == 3):
        e.append(f"{p}: needs x,y,plane or city or biome")
    if "biome" in at and at["biome"] not in BIOMES:
        e.append(f"{p}: unknown biome {at['biome']}")
    if "radius" in at and not (isint(at["radius"]) and at["radius"] >= 8):
        e.append(f"{p}: radius must be an integer of at least 8")


def dimensions(s):
    d = {"city": set(), "biome": set(), "item": set(), "mob": set()}
    for h in s["hops"]:
        at = h.get("at", {})
        if "city" in at:
            d["city"].add(at["city"])
        if at.get("biome") or h.get("biome"):
            d["biome"].add(at.get("biome") or h["biome"])
        if h["kind"].startswith("item_"):
            d["item"].add(h["obj"])
        if h["kind"] == "mob_kill":
            d["mob"].add(h["npc"])
        if "npc" in h.get("from", {}):
            d["mob"].add(h["from"]["npc"])
    return {k: sorted(v) for k, v in d.items() if v}


def validate(s, gv=None, quests=None):
    e = []
    if not isinstance(s, dict):
        return ["spec must be an object"], {}
    e += [f"missing top-level {k}" for k in TOP if k not in s]
    if e:
        return e, {}
    if s["generatorVersion"] != VERSION:
        e.append(f"generatorVersion must be {VERSION}")
    if not re.fullmatch(r"[A-Z][A-Z0-9_]*", str(s["quest"])):
        e.append("quest must be an enum constant name")
    elif quests is not None and s["quest"] not in quests:
        e.append(f"quest {s['quest']} is not in the Quest enum")
    e += [f"{k} is empty" for k in ("name", "summary") if not s[k]]
    typed(s["requirements"], REQUIREMENTS, "requirements", e)
    typed(s["rewards"], REWARDS, "rewards", e)
    if quests is not None:
        e += [f"requirements: quest {r['quest']} is not in the Quest enum" for r in s["requirements"] if r.get("type") == "quest" and r.get("quest") not in quests]
    nums = [x.get("stage") for x in s["stages"]] if isinstance(s["stages"], list) else []
    if not nums or nums[0] != 0 or nums != sorted(set(nums)) or not all(isint(n) for n in nums):
        e.append("stages must be unique ascending integers starting at 0")
        return e, {}
    e += [f"stage {x['stage']}: title and journal are required" for x in s["stages"] if not x.get("title") or not x.get("journal")]
    hops = s["hops"]
    ids = [h.get("id") for h in hops if isinstance(h, dict)]
    if not hops or len(ids) != len(hops) or len(set(ids)) != len(ids) or not all(re.fullmatch(r"[a-z][a-z0-9_]*", str(i)) for i in ids):
        e.append("hops need unique ids matching [a-z][a-z0-9_]*")
        return e, {}
    index = {h["id"]: i for i, h in enumerate(hops)}
    acts = {}
    seen = {}
    for h in hops:
        p = f"hop {h['id']}"
        if h.get("kind") not in KINDS:
            e.append(f"{p}: kind must be one of {list(KINDS)}")
            continue
        if h.get("stage") not in nums:
            e.append(f"{p}: stage {h.get('stage')} is not listed in stages")
            continue
        e += [f"{p}: missing {k}" for k in ("title",) + NEED[h["kind"]] if k not in h]
        check_at(h.get("at"), f"{p}.at", e)
        needs = {"place": "x", "biome": "biome", "city": "city"}.get(h["kind"])
        if needs and needs not in h.get("at", {}):
            e.append(f"{p}: kind {h['kind']} needs at.{needs}")
        if "biome" in h and h["biome"] not in BIOMES:
            e.append(f"{p}: unknown biome {h['biome']}")
        for k in ("to", "on", "from"):
            if k in h and (not isinstance(h[k], dict) or ("npc" in h[k]) + ("loc" in h[k]) != 1):
                e.append(f"{p}: {k} needs exactly one of npc or loc")
        if "amount" in NEED[h["kind"]] and not isint(h.get("amount")):
            e.append(f"{p}: amount must be an integer")
        a = acts[h["id"]] = []
        if "steps" in h or h["kind"] == "npc_talk":
            steps(h.get("steps"), f"{p}.steps", e, a)
            if h["kind"] not in ("npc_talk", "item_give", "item_use") or h["kind"] != "npc_talk" and "npc" not in (h.get("to") or h.get("on") or {}):
                e.append(f"{p}: steps need an npc_talk hop or an item hop aimed at an npc")
        for n in h.get("next", []):
            t = n if isinstance(n, str) else n.get("to")
            if t not in index or index[t] <= index[h["id"]]:
                e.append(f"{p}: next {t} must be a later hop")
            elif hops[index[t]].get("stage", -1) < h["stage"]:
                e.append(f"{p}: next {t} has a lower stage")
        if not e and hooked(h) and h["kind"] in KINDS:
            key = (h["stage"], trigger(h))
            if key in seen:
                e.append(f"{p}: same trigger and stage as hop {seen[key]}")
            seen[key] = h["id"]
    if e:
        return e, {}
    stage_of = {h["id"]: h["stage"] for h in hops}
    first = hops[0]
    if first["stage"] != 0:
        e.append("first hop must be at stage 0")
    start = [h for h in hops if h["kind"] == "npc_talk" and h["npc"] == s["startNpc"] and h["stage"] == 0]
    if not start:
        e.append("no stage 0 npc_talk hop with the startNpc")
    reach, todo = set(), [hops[0]["id"]]
    while todo:
        i = todo.pop()
        if i not in reach:
            reach.add(i)
            todo += targets(hops[index[i]])
    e += [f"hop {i}: not reachable from the first hop" for i in ids if i not in reach]
    flat = [x for v in acts.values() for x in v]
    completes = [i for i, v in acts.items() if any(t == "complete_quest" for t, _ in v)]
    if sum(t == "complete_quest" for t, _ in flat) != 1:
        e.append("exactly one complete_quest action is required")
    for h in hops:
        p = f"hop {h['id']}"
        if not targets(h) and h["id"] not in completes:
            e.append(f"{p}: leaf hop must hold the complete_quest action")
        if h["kind"] == "npc_talk" and targets(h) and h["id"] not in completes:
            want = {stage_of[t] for t in targets(h)}
            if not any(t == "set_stage" and a.get("stage") in want for t, a in acts[h["id"]]) and want != {h["stage"]}:
                e.append(f"{p}: needs a set_stage action to one of {sorted(want)}")
        e += [f"{p}: set_stage {a.get('stage')} is not listed in stages" for t, a in acts.get(h["id"], []) if t == "set_stage" and a.get("stage") not in nums]
    for cls, name in walk(s):
        if not re.fullmatch(r"(Npc|Obj|Loc)\.[A-Za-z0-9_]+", name) or not name.startswith(cls + "."):
            e.append(f"gameval {name} must look like {cls}.name")
        elif gv and name[4:] not in gv[cls]:
            e.append(f"gameval {name} does not exist")
    if gv and gv["mobs"]:
        e += [f"hop {h['id']}: {h['npc']} is not in the hostile mob list" for h in hops if h["kind"] == "mob_kill" and h["npc"][4:] not in gv["mobs"]]
    span = dimensions(s)
    if len(hops) < 3 or len(span) < 2:
        e.append(f"not multihop: {len(hops)} hops, dimensions touched: {sorted(span) or 'none'}")
    return e, span

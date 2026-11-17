import json
import re
from pathlib import Path

from spec import hooked, trigger

PACKAGE = "com.rs.game.content.quests"
JOURNAL_FALLBACK = "Invalid quest stage. Report this to an administrator."
IMPORTS = {
    "HeadE.": "com.rs.engine.dialogue.HeadE",
    "startConversation": "com.rs.engine.dialogue.startConversation",
    "Quest.": "com.rs.engine.quest.Quest",
    "QuestHandler": "com.rs.engine.quest.QuestHandler",
    "QuestOutline": "com.rs.engine.quest.QuestOutline",
    "NPC)": "com.rs.game.model.entity.npc.NPC",
    "Player": "com.rs.game.model.entity.player.Player",
    "ServerStartupEvent": "com.rs.plugin.annotations.ServerStartupEvent",
    "Constants.": "org.darkan.core.Constants",
    "Npc.": "org.darkan.core.gameval.gen.Npc",
    "Obj.": "org.darkan.core.gameval.gen.Obj",
    "Loc.": "org.darkan.core.gameval.gen.Loc",
    "Tile.": "world.gregs.voidps.type.Tile",
    "onNpcClick(": "com.rs.plugin.kts.onNpcClick",
    "onObjectClick(": "com.rs.plugin.kts.onObjectClick",
    "onNpcKillParticipation(": "com.rs.plugin.kts.onNpcKillParticipation",
    "onItemAddedToInventory(": "com.rs.plugin.kts.onItemAddedToInventory",
    "onItemOnNpc(": "com.rs.plugin.kts.onItemOnNpc",
    "onItemOnObject(": "com.rs.plugin.kts.onItemOnObject",
    "onChunkEnter {": "com.rs.plugin.kts.onChunkEnter",
}
EVENTS = {
    "onNpcClick": (("player", "npc"), "onNpcClick(%s)"),
    "onObjectClick": (("player", "obj", "option"), "onObjectClick(%s)"),
    "onNpcKillParticipation": (("killer", "npc"), "onNpcKillParticipation(%s)"),
    "onItemAddedToInventory": (("player", "item"), "onItemAddedToInventory(%s)"),
    "onItemOnNpc": (("player", "item", "npc"), "onItemOnNpc(%s)"),
    "onItemOnObject": (("player", "obj", "item"), "onItemOnObject(arrayOf<Any>(%s), null)"),
    "onChunkEnter": (("entity",), "onChunkEnter"),
}
OF = {("click", "Npc"): "onNpcClick", ("click", "Loc"): "onObjectClick", ("kill", "Npc"): "onNpcKillParticipation", ("add", "Obj"): "onItemAddedToInventory", ("onnpc", "Npc"): "onItemOnNpc", ("onloc", "Loc"): "onItemOnObject", ("chunk", ""): "onChunkEnter"}
PRELUDE = {"onNpcKillParticipation": ["val player = killer as? Player ?: return@onNpcKillParticipation"], "onChunkEnter": ["val player = entity as? Player ?: return@onChunkEnter"]}


def lit(text):
    return text.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")


def q(text):
    return f'"{lit(text)}"'.replace("\n", "\\n")


def pascal(name):
    return "".join(p.capitalize() for p in name.split("_"))


def label(gameval):
    return gameval.split(".", 1)[1].replace("_", " ")


def with_imports(body, head=""):
    found = sorted({v for k, v in IMPORTS.items() if k in body})
    return f"package {PACKAGE}\n\n" + "\n".join(f"import {i}" for i in found) + "\n\n" + head + body


def stage_map(s):
    return {h["id"]: h["stage"] for h in s["hops"]}


def advance(s, h):
    stages = stage_map(s)
    if not h.get("next"):
        return ""
    entries = [(n, None) if isinstance(n, str) else (n["to"], n.get("when")) for n in h.get("next", [])]
    plain = [stages[t] for t, w in entries if not w]
    expr = str(plain[0] if plain else h["stage"])
    for t, w in reversed([e for e in entries if e[1]]):
        expr = f"if (player.inventory.containsItem({w['has_item']}, {w.get('amount', 1)})) {stages[t]} else {expr}"
    return f"player.questManager.setStage(Quest.{s['quest']}, {expr})"


def rewards_text(s):
    out = []
    for r in s["rewards"]:
        if r["type"] == "xp":
            out.append(f"{r['amount']} {r['stat'].capitalize()} XP")
        elif r["type"] == "coins":
            out.append(f"{r['amount']} coins")
        elif r["type"] == "item":
            out.append(f"{r['amount']} {label(r['obj'])}")
    return "<br>".join(out)


def outline(s, cls):
    pairs = {"xp": lambda r: f"player.skills.addXpQuest(Constants.{r['stat'].upper()}, {r['amount']}.0)", "coins": lambda r: f"player.inventory.addCoins({r['amount']})", "item": lambda r: f"player.inventory.addItemDrop({r['obj']}, {r['amount']})"}
    calls = [pairs[r["type"]](r) for r in s["rewards"] if r["type"] in pairs]
    icon = next((r["obj"] for r in s["rewards"] if r["type"] == "item"), "Obj.coins")
    items = ", ".join(f"{r['amount']} {label(r['obj'])}" for r in s["requirements"] if r["type"] == "item") or "None."
    mobs = sorted({label(h["npc"]) for h in s["hops"] if h["kind"] == "mob_kill"})
    spec = json.dumps(s, separators=(",", ":"))
    if len(spec) > 60000:
        raise SystemExit("spec too large for a Kotlin string constant")
    rows = "\n".join(f"        {x['stage']} -> listOf({q(x['journal'])})" for x in s["stages"])
    body = [
        "@QuestHandler(",
        f"    quest = Quest.{s['quest']},",
        f"    startText = {q(next(h['title'] for h in s['hops'] if h['kind'] == 'npc_talk' and h['stage'] == 0 and h['npc'] == s['startNpc']) + '.')},",
        f"    itemsText = {q(items)},",
        f"    combatText = {q('Kill ' + ', '.join(mobs) + '.' if mobs else 'None.')},",
        f"    rewardsText = {q(rewards_text(s))},",
        f"    completedStage = {s['stages'][-1]['stage']}",
        ")",
        f"class {cls} : QuestOutline() {{",
        "    override fun getJournalLines(player: Player, stage: Int) = when (stage) {",
        rows,
        f"        else -> listOf({q(JOURNAL_FALLBACK)})",
        "    }",
        "",
        "    override fun complete(player: Player) {",
        *[f"        {c}" for c in calls],
        f"        sendQuestCompleteInterface(player, {icon})",
        "    }",
        "}",
    ]
    return with_imports("\n".join(body) + "\n", f'private const val QUEST_SPEC_JSON = "{lit(spec)}"\n\n')


def render(items, ind, s):
    pad = "    " * ind
    out = []
    for x in items:
        k = x["step"]
        if k == "say":
            out.append(f'{pad}npc(npc, HeadE.{x["head"]}, {q(x["text"])})' if x["who"] == "npc" else f'{pad}player(HeadE.{x["head"]}, {q(x["text"])})')
        elif k == "options":
            out.append(f"{pad}options {{")
            for o in x["options"]:
                sub = render(o.get("steps", []), ind + 2, s)
                out += [f"{pad}    op({q(o['text'])}) {{", *sub, f"{pad}    }}"] if sub else [f"{pad}    op({q(o['text'])})"]
            out.append(f"{pad}}}")
        elif k == "end":
            out.append(f"{pad}stop()")
        else:
            a = x["args"]
            call = {
                "give_item": f"player.inventory.addItemDrop({a.get('obj')}, {a.get('amount')})",
                "take_item": f"player.inventory.deleteItem({a.get('obj')}, {a.get('amount')})",
                "set_stage": f"player.questManager.setStage(Quest.{s['quest']}, {a.get('stage')})",
                "give_xp": f"player.skills.addXp(Constants.{str(a.get('stat')).upper()}, {a.get('amount')}.0)",
                "give_coins": f"player.inventory.addCoins({a.get('amount')})",
                "complete_quest": f"player.questManager.completeQuest(Quest.{s['quest']})",
            }[x["type"]]
            out.append(f"{pad}exec {{ {call} }}")
    return out


def hop_body(s, h, fn):
    k, adv, obj = h["kind"], advance(s, h), h.get("obj")
    need = f"player.inventory.containsItem({obj}, {h.get('amount')})"
    if k == "npc_talk":
        return [f"{fn(h)}(player, npc)"]
    if k == "object_interact":
        return [f"if (option.equals({q(h['option'])}, true)) {adv}"]
    if k == "mob_kill":
        key = q("kills_" + h["id"])
        return ["val attribs = player.questManager.getAttribs(Quest." + s["quest"] + ")", f"val kills = attribs.getI({key}) + 1", f"if (kills >= {h['amount']}) {{", f"    attribs.setI({key}, 0)", f"    {adv}", f"}} else attribs.setI({key}, kills)"]
    if k == "item_get":
        grant = [f"if (!{need}) player.inventory.addItemDrop({obj})"] if h.get("from") else []
        return grant + [f"if ({need}) {adv}"]
    if k in ("item_give", "item_use"):
        body = ([f"player.inventory.deleteItem({obj}, {h['amount']})"] if k == "item_give" else []) + ([adv] if adv else []) + ([f"{fn(h)}(player, npc)"] if "steps" in h else [])
        return [f"if (item.id == {obj} && {need}) {{", *[f"    {b}" for b in body], "}"]
    at = h["at"]
    return [f"if (entity.withinDistance(Tile.of({at['x']}, {at['y']}, {at['plane']}), {at.get('radius', 16)})) {adv}"]


def hooks(s, cls):
    fn = lambda h: "hop" + pascal(h["id"])
    groups = {}
    for h in s["hops"]:
        if hooked(h):
            kind, key = trigger(h)
            event = OF[(kind, key.split(".")[0] if key else "")]
            groups.setdefault((event, key), []).append((h["stage"], h))
    lines = ["@ServerStartupEvent", f"fun map{cls}() {{"]
    for (event, key), arms in groups.items():
        names, head = EVENTS[event]
        arm_lines = []
        for stage, h in arms:
            body = hop_body(s, h, fn)
            if h["kind"] == "npc_talk" and stage == 0 and h["npc"] == s["startNpc"] and s["requirements"]:
                body = [f"if (canStart(player)) {body[0]}"]
            arm_lines += [f"            {stage} -> {body[0]}"] if len(body) == 1 else [f"            {stage} -> {{", *[f"                {b}" for b in body], "            }"]
        text = "\n".join(arm_lines)
        used = [i == 0 or bool(re.search(rf"\b{n}\b", text)) for i, n in enumerate(names)]
        last = max(i for i, u in enumerate(used) if u)
        params = ", ".join(n if used[i] else "_" for i, n in enumerate(names[: last + 1]))
        lines.append(f"    {head % key if '%s' in head else head} {{ ({params}) ->")
        lines += [f"        {p}" for p in PRELUDE.get(event, [])]
        lines += [f"        when (player.getQuestStage(Quest.{s['quest']})) {{", *arm_lines, "        }", "    }"]
    lines.append("}")
    for h in s["hops"]:
        if "steps" in h:
            lines += ["", f"private fun {fn(h)}(player: Player, npc: NPC) {{", "    player.startConversation {", *render(h["steps"], 2, s), "    }", "}"]
    if s["requirements"]:
        lines += ["", "private fun canStart(player: Player): Boolean {", "    val missing = listOfNotNull("]
        lines += [f"        {req(r)}," for r in s["requirements"]]
        lines += ["    )", "    missing.forEach { player.sendMessage(it) }", "    return missing.isEmpty()", "}"]
    return with_imports("\n".join(lines) + "\n")


def req(r):
    t = r["type"]
    if t == "skill":
        return f'"You need level {r["level"]} {r["stat"]}.".takeIf {{ player.skills.getLevelForXp(Constants.{r["stat"].upper()}) < {r["level"]} }}'
    if t == "quest":
        return f'"You must have completed {r["quest"].replace("_", " ").lower()}.".takeIf {{ !player.isQuestComplete(Quest.{r["quest"]}) }}'
    if t == "item":
        return f'"You need {r["amount"]} {label(r["obj"])}.".takeIf {{ !player.inventory.containsItem({r["obj"]}, {r["amount"]}) }}'
    return f'"You need {r["amount"]} quest points.".takeIf {{ player.questManager.questPoints < {r["amount"]} }}'


def generate(s, outdir):
    cls = pascal(s["quest"])
    out = Path(outdir)
    out.mkdir(parents=True, exist_ok=True)
    (out / f"{cls}.kt").write_text(outline(s, cls))
    (out / f"{cls}Dialogue.kt").write_text(hooks(s, cls))
    return [f"{cls}.kt", f"{cls}Dialogue.kt"]


def scan(folder):
    hand = []
    for f in sorted(Path(folder).rglob("*.kt")):
        text = f.read_text()
        m = re.search(r'private const val QUEST_SPEC_JSON = "((?:[^"\\]|\\.)*)"', text)
        if m:
            return json.loads(re.sub(r"\\(.)", r"\1", m[1]))
        if "@QuestHandler" in text:
            texts = dict(re.findall(r'(\w+Text)\s*=\s*"((?:[^"\\]|\\.)*)"', text))
            parts = re.split(r"(?m)^\s*(\d+)\s*->", text.split("getJournalLines", 1)[-1].split("override fun complete")[0])
            stages = [{"stage": int(parts[i]), "journal": re.findall(r'"((?:[^"\\]|\\.)*)"', parts[i + 1])} for i in range(1, len(parts) - 1, 2)]
            quest = re.search(r"quest\s*=\s*Quest\.(\w+)", text)
            done = re.search(r"completedStage\s*=\s*(\d+)", text)
            hand.append({"file": f.name, "quest": quest and quest[1], "completedStage": done and int(done[1]), "handler": texts, "stages": stages})
    return {"handwritten": True, "note": "no QUEST_SPEC_JSON; only handler arguments, stage numbers and journal strings are extracted", "quests": hand}

# 727 gameval crosswalk — contract

Every `gamevals/<type>.json` in this directory maps a **revision-727 (July 2012) cache id** to the
Jagex developer name for that exact asset, recovered from the RS3 revision-949 gameval tables
(`~/darkan/darkan-3-undercut/re-resources/gamevals/`). This file is the rule book the generator
scripts in `gamevals/crosswalk/` implement and the verifiers audit against.

## The one rule

**A name is emitted only when the evidence proves the 727 id and the RS3 name denote the same
asset.** A plausible name is worse than a number. When no channel proves it, the entry is the
placeholder `<type>_<id>` (e.g. `cs2_5002`, `loc_1234`, `spotanim_77`).

"Proves" means one of the channels below reports a match **and** no channel reports a
contradiction. Same-id alone is never evidence: Jagex never renumbers, but it does delete, replace
and recycle ids, so same-id is only the *candidate generator*.

## Type names are canonical Jagex names

`obj` (not item), `loc` (not object), `seq` (not animation), `spotanim` (not graphic/gfx),
`graphic` = 2D sprite, `inv`, `enum`, `struct`, `param`, `npc`, `interface`, `component`,
`varp` / `varbit` / `varc` / `varcstr` / `varclan` / `varbitclan` / `varclansetting` /
`varbitclansetting`, `clientscript` (placeholder prefix `cs2_`), `bas`, `hitmark`, `headbar`,
`cursor`, `mapelement`, `model`, `sound`, `midi`, `material`, `fontmetrics`, `quest` - 32 catalogs,
one per type the 727 cache holds.

### RS3 gameval tables with no 727 catalog

The RS3-949 dump names twelve more types. None of them has data in the 727 cache, so no catalog
exists for them and none may be invented (a catalog must cover the ids the 727 cache holds, and
there are none). Recorded here so the gap is a decision, not an oversight; `work/727/manifest.json`
is the witness for the config archives:

| RS3 table | canonical name | why there is no 727 catalog |
|---|---|---|
| `var_npc`, `varbit_npc` | `varn`, `varnbit` | config archive 61 (`varnpc`) dumps 0 records; there is no npc varbit archive |
| `var_object`, `varbit_object` | - | not in the 727 cache (post-2012 types) |
| `var_player_group` | - | config archive 80 (`vargroup`) dumps 0 records |
| `category` | `category` | 727 obj/loc/npc definitions carry no category field and no category config archive exists |
| `dbrow`, `dbtable` | `dbrow`, `dbtable` | no index-2 database archives in 727 |
| `achievement` | - | no such type in 727 |
| `stylesheet`, `ui_anim`, `ui_anim_curve` | - | NXT-era UI types, absent from 727 |

`lib.RS3_GAMEVAL_STEM` still maps the canonical names to the RS3 files so a verifier can read
the tables; that mapping is not a promise of a catalog. Should a later 727 dump turn up records
for one of these (a dumper fix, say), it gets a generator and a catalog like any other type.

## Evidence channels

| channel | what it compares | strength |
|---|---|---|
| `hash` | 727 index-12 archive name hash == `String.hashCode("[cat,name]")` | proof (32-bit; see collision note) |
| `struct` | same id, and the 727 definition's identity-bearing fields equal the RS3 definition's | proof when the field set is identity-bearing for that type (per-type table below) |
| `cs2` | a 727 script and an RS3 script that are the *same script by `hash`* use the 727 id and the RS3 name at aligned instruction positions | proof when the two instruction streams align 1:1 and the operand's arg-type matches |
| `derived` | the name is a deterministic function of another proven name (spotanim ← seq; obj base ← its noted cert twin, `cert_` stripped) | proof, inherits the parent's evidence |
| `carry` | brought over from the existing `com/rs/engine/variables` crosswalk (var* only) | accepted with provenance; sampled by verifiers |
| `manual` | a human decision recorded in `gamevals/crosswalk/overrides.json`, applied last | accepted with provenance; see "Manual overrides" below |

Collision note: a 32-bit hash against a dictionary of ~6.4k names and ~6.6k archives expects ~0.01
false pairs. Every hash match is additionally checked for argument-count agreement with the RS3
script of that name; a disagreement is reported, not silently accepted. Deterministic variants of
the table names (category swap, dropped qualifier/namespace token, `_2`/`_vN`/trailing-number
rewrites — each justified by proven pairs and ranked by its yield on the already-named set) may be
added to the dictionary only while the expected false hits of the whole variant pass,
`|variants| x |727 hashes no table name hits| / 2^32`, stay `<= 0.05`, enforced in the generator;
a variant may only claim an archive no table name hits.

### Identity-bearing fields per type (channel `struct`)

Field names refer to the 727 dump in `gamevals/work/727/*.jsonl` and the RS3 dump in
`gamevals/work/rs3-unpacked/full/types/*.jsonl`. "eq" = exactly equal after the stated
normalisation. Strings are compared case-sensitively after trimming; `null`/`"null"` names are
**not** identity (an unnamed asset matches nothing on name).

- **obj**: `name` eq (non-null, not "null") **and** at least one of: `notedId`/`notedTemplateId`
  structure eq, `options` eq, `modelId` eq, `cost` eq (`stackable` eq was removed as evidence
  2026-08-30: a near-constant binary field, name eq + stackable eq proves almost nothing beyond
  a same-id name match; it may still appear in evidence rows informationally). Noted/lent/bound template
  items follow their base item (`name` is inherited from the template in both caches).
  Additionally, channel `derived` (cert-twin, 2026-08-30): a base obj that is still a
  placeholder takes the name of its noted twin with the `cert_` prefix stripped, when ALL of:
  (i) the 727 records link both ways — base `noteId` == cert id with base `notedTemplateId`
  == -1, and the cert record links back with `noteId` == base id and a real
  `notedTemplateId`; (ii) the cert id's catalog name is proven by `struct`/`cs2` (never
  `manual` — overrides are applied after this rule) and starts with `cert_`; (iii) the 727
  display names of base and cert are identical; (iv) the derived name is unique in the
  catalog (a collision rejects the candidate and is reported). Sound because both caches
  derive a cert's dev name as `cert_` + the base's; the evidence row records channel
  `derived`, the cert id, its name and its proofs.
- **npc**: `name` eq (non-null) **and** at least one of: `modelIds` intersect non-empty,
  `options` eq, `size` eq **and** `renderEmote`/bas eq **and** the bas is not -1 on both sides (a
  shared -1 is a shared decoder default, not identity). Combat level is *not* identity (EoC rescaled it).
- **loc**: `name` eq (non-null, not "null") **and** at least one of: `modelIds` intersect
  non-empty, (`sizeX`,`sizeY`,`options`) eq. For nameless locs (`null`): `modelIds` eq **and**
  (`sizeX`,`sizeY`,`solid`/`clipType`) eq.
- **seq**: `frames` eq **and** `durations` eq (both non-empty). Empty/skeletal RS3 seqs never match.
- **spotanim**: `derived` from its `animationId`'s seq name. Duplicates (several spotanims on one
  seq) are numbered in ascending spotanim-id order: `name`, `name_2`, `name_3`… Additionally,
  RS3 `spot-anim` with the same id must have the same `animationId` **and** `modelId`; if it
  disagrees, emit the placeholder and report it.
- **enum**: `keyType` eq, `valueType` eq, and every 727 entry present in the RS3 map with an equal
  value (RS3 may be a superset), `defaultInt`/`defaultString` eq. Enums whose values are
  interface/component ids fail automatically unless the target interfaces are proven.
- **struct**: every 727 param present in RS3 with an equal value (RS3 may be a superset), at least
  one param.
- **param**: `type` eq and `defaultInt`/`defaultString` eq **and** a usage witness: at least one
  proven obj/npc/loc/struct that carries the param in both caches with an equal value.
- **inv**: `size` eq **and** (stock `ids` eq when both have stock, or a `cs2` witness).
- **interface**: carbon copy only — same id, same component count, and **every** component equal
  on (`type`, `contentType`, `parent`, `basePositionX/Y`, `baseWidth/Height`, position/size
  modes, `text`, `options`, `name`, sprite/model/anim ids where applicable). Then every
  `component` name of that interface carries over by index. Nothing partial.
- **var\***: `carry` from the existing crosswalk; verifiers sample-check with `cs2` witnesses.
- **model / sprite / sound / midi / material / fontmetrics**: raw-byte `sha1` eq with the RS3
  archive of the same id (the RS3 side needs a raw dump; if unavailable the type is placeholder-only
  and the report says so).
- **bas / hitmark / headbar / cursor / mapelement / quest**: struct eq on the full decoded field
  set where a 727 decoder exists; otherwise placeholder-only.

## Manual overrides

`gamevals/crosswalk/overrides.json` records human promote/demote decisions, typically taken from
the review list `gamevals/REVIEW.md` (generated by `crosswalk/review_list.py` as the last
pipeline stage). `lib.emit_catalog` applies them **last**, after every channel and the
uniqueness/contradiction handling:

```json
{
  "obj":  { "1041": { "name": "cert_yellow_partyhat", "reason": "manual: RS3 table lacks the entry; dump name+link equal" } },
  "npc":  { "117":  { "placeholder": true, "reason": "manual: case-only match judged a rename" } }
}
```

- `{"name": ...}` names the id; the evidence sidecar keeps whatever channel evidence existed and
  appends a row of channel `manual` with the `reason`, so provenance stays explicit.
- `{"placeholder": true}` demotes the id to its placeholder whatever the channels proved.
- An override may not use an id the type does not have, a placeholder-shaped name, or a name
  another id already carries — each of these fails the run instead of degrading silently.
- The file is committed; an empty object `{}` is the no-op. A promotion here is still a
  *decision with a stated reason*, not a loophole: the one rule above stands, and the reason
  belongs in the `reason` field and, for anything non-obvious, in `REVIEW.md`'s class notes.

## Review decisions

`gamevals/crosswalk/reviewed.json` (committed) records **class-level** verdicts on
`gamevals/REVIEW.md`, so a reviewed class stops re-presenting its rows on every regeneration:

```json
{ "decisions": [
    { "class": "A1_obj_cost_only", "status": "approved", "date": "2026-08-30",
      "note": "user review: no mistakes" } ] }
```

- `status` is `approved` (the rows were checked and stand as emitted) or `resolved` (the class
  was acted on — via overrides.json or a generator-rule change — and the `note` says what
  resolved it, pointing at the report section or override reasons).
- `review_list.py` matches `class` against its section keys ignoring the leading `A<n>_`/`B<n>_`
  (section numbers shift as classes come and go); a decision matching no class fails the run.
  A matched class collapses in `REVIEW.md` to its heading, count and the one-line note.
- The file records review verdicts only — it never changes a catalog entry. Per-id decisions
  stay in `overrides.json`; rule changes stay in this contract and the generators.

## Output shape

```json
{
  "revision": 727,
  "type": "seq",
  "source": "rs3-949 gameval names crosswalked onto the 727 cache; see gamevals/CONTRACT.md",
  "date": "2026-08-30",
  "coverage": { "cache_ids": 17000, "named": 12000, "placeholder": 5000,
                "by_channel": { "struct": 11800, "cs2": 200 } },
  "entries": { "0": "swarm_walk", "1": "swarm_attack", "17": "seq_17" }
}
```

- `entries` covers **every id the 727 cache has** for that type, ascending, no gaps skipped.
- `component.json` keys are `"<interfaceId>:<componentId>"`; names are `<iface>:<comp>`.
- `clientscript.json` names keep Jagex's `[category,name]` form.
- Names are unique within a type. If two 727 ids prove to the same RS3 name, that is a
  contradiction: both become placeholders and the report lists them.
- A sidecar `gamevals/work/evidence/<type>.jsonl` records, per named id, the channel(s) and the
  compared values, so a verifier can re-derive any single entry without rerunning the script.

## Reports

Each generator writes `gamevals/work/reports/<type>.md`: counts per channel, contradictions,
the ten largest classes of "candidate rejected because …", and a random sample of 20 named
entries with their evidence. Verifiers append their findings to the same file.

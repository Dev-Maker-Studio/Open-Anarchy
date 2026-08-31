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

One channel, `brute`, does not reach proof and does not claim to: it recovers a name by search and
corroborates it against the asset, with a **measured** residual error rate rather than a
negligible one. It is admitted because a corroborated name with a stated, small, measured chance
of being wrong is worth more than a number - but it is fenced off, counted separately, and always
distinguishable in the evidence. Its rules and its arithmetic are in "Channel `brute`" below.

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
| `hash` | a 727 cache name hash equals `String.hashCode(<name>)`: index-12 archive names as `"[cat,name]"` (clientscript), a type-index archive name (sprite/fontmetrics/midi), or an index-23 world-map static-element **file** name paired with the map element id in that file's own payload (mapelement) | proof (32-bit; see collision note) |
| `struct` | same id, and the 727 definition's identity-bearing fields equal the RS3 definition's | proof when the field set is identity-bearing for that type (per-type table below) |
| `cs2` | a 727 script and an RS3 script that are the *same script by `hash`* use the 727 id and the RS3 name at aligned instruction positions | proof when the two instruction streams align 1:1 and the operand's arg-type matches |
| `derived` | the name is a deterministic function of another proven name (spotanim ← seq; obj base ← its noted cert twin, `cert_` stripped) | proof, inherits the parent's evidence |
| `carry` | brought over from the existing `com/rs/engine/variables` crosswalk (var* only) | accepted with provenance; sampled by verifiers |
| `brute` | the name hash was recovered by dictionary-constrained search **and** independently corroborated by the asset's own content/structure; accepted only when the hit is unique in the searched space and clears the calibrated corroboration threshold | corroborated, **not** proof - weaker than `hash`; see "Channel `brute`" below |
| `manual` | a human decision recorded in `gamevals/crosswalk/overrides.json`, applied last | accepted with provenance; see "Manual overrides" below |

Collision note: a 32-bit hash against a dictionary of ~6.4k names and ~6.6k archives expects ~0.01
false pairs. Every hash match is additionally checked for argument-count agreement with the RS3
script of that name; a disagreement is reported, not silently accepted. Deterministic variants of
the table names (category swap, dropped qualifier/namespace token, `_2`/`_vN`/trailing-number
rewrites — each justified by proven pairs and ranked by its yield on the already-named set) may be
added to the dictionary only while the expected false hits of the whole variant pass,
`|variants| x |727 hashes no table name hits| / 2^32`, stay `<= 0.05`, enforced in the generator;
a variant may only claim an archive no table name hits.

### Channel `brute`

`hash` is a proof channel because its dictionary is tiny: the whole RS3-949 table plus a budgeted
variant pass, held to <= 0.05 expected false hits for the entire run. `brute` runs the same 32-bit
equality against a search space many orders of magnitude larger - generated prefix/suffix
compositions, family stems, ranked tokens, lexicon substrings - built by the local, gitignored
tooling in `gamevals/crosswalk/cracker/`. At that size **a hash hit is not a name**: `N`
candidates against `T` targets throw off `N*T/2^32` hits by chance, and the production ladder's
own negative control measures 24.7 false hits per run on index 12 alone. The hit is therefore only
the *candidate generator*, exactly as same-id is for `struct`, and the one rule at the top of this
file still decides: a name is emitted only when the evidence, not the hit, carries it.

Two conditions, both required:

1. **Unique in the searched space.** Two candidate strings hitting one target rejects both, and
   both are listed. (One index-12 target was rejected this way: `12:702` =
   `[clientscript,lore_head_thunder]` / `[clientscript,openurl_nologin]`.)
2. **Independent corroboration from the asset itself**, of one of two shapes:
   - *Content agreement* (index 12, clientscripts). Score = the IDF-weighted count of the
     candidate's **non-anchor** tokens - the longest anchor prefix the search itself supplied is
     subtracted first - that appear in what the script actually touches: the names of the
     components, interfaces, objs, structs, params, enums and vars it references (resolved
     through our own catalogs), its own string literals, its bound interface/components, and its
     call-graph and id-window neighbours. IDF is over the 6,567 per-script context bags, so the
     threshold 2.0 demands a token carried by at most `e^-2` = 13.5% of scripts. A hit found at
     search iteration >= 1 is accepted only if the anchor it used has a source that is either a
     name already proven by another channel or a name this run's own gate has itself accepted -
     never an unaccepted guess (the chain rule). Ten of the 94 index-12 names rest on such a
     one-step chain; the negative control applies the identical rule, so the measured residual
     below already covers them.
   - *Cheapest generating region* (the asset indices). Name the smallest **pre-specifiable**
     region that generates the string and charge it exactly:
     `E = sum over targets in scope of |region(target)| / 2^32`. A region under this file's 0.05
     proof budget is accepted on budget alone; a region above it must additionally clear a
     neighbourhood-agreement score whose false-pass rate is *measured* by sampling that region
     against random families/archives. Restricting acceptance to a sub-region is legitimate
     post-hoc because the wider rule's false hits are uniform over its space.

**The acceptance arithmetic is measured, not assumed.** The content gate is calibrated three
ways: on the 1,580 already-solved 727 scripts (true name to its own script mean 9.91, 92.3% at or
above 2.0; the same names shuffled onto other scripts mean 0.40, 7.7% above), by holdout retention
(12 holdout runs, 3,307 true recoveries, 58.3% kept), and by a negative control - the identical
ladder run against pseudo-random target hashes over 64 seeds produced 1,580 false hits (24.7 per
run against the 26.1 predicted by `pairs/2^32`), of which exactly 9 passed the gate. That is the
number the channel reports: **0.141 expected wrong names per production run** on index 12 (95%
Poisson upper 0.252), 0.048 on index 6 and 0.243 on index 8.

So `brute` is honestly weaker than `hash`, and the catalogs must let a reader see that. Every
`brute` entry's evidence row carries `channel: "brute"`, the exact hashed literal, the accept
path, the region or content score that carried it and the measured residual false-positive
expectation of its index; `coverage.by_channel` counts it separately; and
`coverage.brute_residual_false_expectation` states, per catalog, how many of its `brute` names are
expected to be wrong. **A `brute` name may never claim an id another channel names** - `hash`,
`struct` and `cs2` all win - and it is subject to the same uniqueness and contradiction handling
as every other channel.

Everything the gate rejects is a *candidate*, not a name. The rejected pile lives in the local
review ledger `gamevals/crosswalk/cracker/results/candidates.md` and is never written to a
catalog. The accepted set that feeds the pipeline is
`gamevals/crosswalk/cracker/results/accepted.json`, consumed by `crosswalk/brute.py`, which
re-verifies every row against the 727 cache's own name hashes before a name is allowed through -
a row whose literal does not hash to its archive's recorded 727 name hash fails the run.

The acceptance run also produced 138 names for index 23 (`world_map`) *files*. No 727
catalog covers that index, so those names have nowhere to land and are not emitted; they stay in
the acceptance ledger until a `world_map` catalog exists.

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
- **mapelement**, additionally, channel `hash` (world-map static elements, 2026-08-31): index 23
  carries *file* name hashes, and every `<area>_staticelements` file is one map icon whose 7-byte
  payload the 727 client reads in `Class301.getStaticElements(Index, String, boolean)` as `readInt`
  regionHash, `readUnsignedShort` map element id, `readUnsignedByte` members - the id indexing config
  archive 36 (`SharedConfigsType.MAP_AREAS`), the table this catalog names. A solved file name is
  therefore a hash-verified name for the id in that file's own payload. An id is claimed when ALL of:
  (i) the file's name hash is solved and `java_hash(name)` re-checks against index 23's reference
  table (the generator recomputes it; a claim never rests on the cracker's bookkeeping);
  (ii) every static-element file referencing that element id carries the same recovered name - two
  different names on one id mean the file names a world-map *entry*, not the element, and the id is
  rejected and reported; (iii) the RS3-949 `mapelement` gameval table holds that exact name at the
  same numeric id (the cracker's dictionary is keyed on names and never sees the payload id, so the
  id agreement is independent corroboration, not circularity). Disagreement with another channel on
  an id is a contradiction and makes both placeholders, as everywhere else.
  Measured on the 727 cache: 1091 static-element files in 40 archives reference 991 distinct element
  ids; 503 file names are solved, covering 502 element ids. Against the independent `struct` channel
  the overlap is 204 (file, element) pairs on 203 element ids, of which **203 agree byte-for-byte and
  1 disagrees** - and that one disagreement is the reverse hazard itself (element 98 is
  `main_staticelements:300` = `jail` and `troll_stronghold_staticelements:1` =
  `troll_stronghold_jail`), so rule (ii) rejects it.
  That overlap is measured over the files whose names the search actually recovered, and it is a
  selection-biased sample: index-23 file names come in two shapes, the bare element name and an
  area-scoped `<area>_<elementname>` form, and the search recovered mostly the bare shape. Re-measured
  (2026-08-31 audit, independent reader and dictionary) against every RS3-949 mapelement name plus
  every `<area>_<name>` composition - 267,214 strings, expected chance hits 0.068 over the 1091
  targets - the overlap against `struct` is 389 (file, element) pairs, **203 agreeing and 186
  disagreeing**, and every one of the 186 is the area-scoped form of that element's own struct name.
  The struct overlap is therefore *not* what carries this channel; **rule (iii) is**. Not one of the
  177 area-scoped composites that hash-match a file on a struct-named id appears anywhere in the
  RS3-949 mapelement table, so rule (iii) rejects all 177; for a wrong name to survive it would have
  to be both a chance 32-bit hit and sit at its own element's RS3 id (~1/5809), putting the channel's
  residual at order 1e-5. The same audit re-derived the 40 archives, 1091 files and 991 element ids
  from the `details` archive alone and reproduced all 299 landed names exactly, with no sibling file
  on any landed id resolving to a different name. No normalisation (prefix, suffix or case) is
  applied or needed. Rule (iii) holds for 502 of the 503 solved names, the exception being that same
  `troll_stronghold_jail`, which RS3 has no mapelement name for. 16 claims have a sibling file on the
  same id whose hash is still unsolved; in that same configuration `struct` overlaps 12 ids and agrees
  on all 12, and each evidence row records `filesReferencingId` so a reader can see it. The channel
  claims 501 ids, 299 of them previously placeholders (icon elements with no map text, which `struct`
  cannot reach), taking mapelement from 424/1220 to 723/1220.

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

# Gamevals: 727 asset ids by their Jagex names

`gamevals/<type>.json` at the repo root maps every **revision-727 cache id** of an asset type to
the Jagex developer name for that exact asset (`inv` 94 -> `worn`, `seq` 0 -> `swarm_walk`). They
were produced by a one-time crosswalk from the RS3-949 gameval tables (727 is version-locked and
Jagex never published gamevals for it, so this was reverse-engineered once); the rules under which
a name was emitted are in `gamevals/CONTRACT.md`. The crosswalk tooling (`gamevals/crosswalk/`),
its dumps (`gamevals/work/`) and the review ledger (`gamevals/REVIEW.md`) are local-only and
gitignored — the committed catalogs are the canonical artifact and are only ever improved, never
regenerated from scratch. This document covers how the server reads them.

## Catalog shape

```json
{
  "revision": 727, "type": "seq", "source": "...", "date": "2026-08-30",
  "coverage": { "cache_ids": 17000, "named": 12000, "placeholder": 5000 },
  "entries": { "0": "swarm_walk", "1": "swarm_attack", "17": "seq_17" }
}
```

- One file per type, filename stem = type name. Types: `obj npc loc seq spotanim enum struct
  param inv interface component clientscript varp varbit varc varcstr varclan varbitclan
  varclansetting varbitclansetting bas hitmark headbar cursor mapelement quest model graphic
  sound midi material fontmetrics` - all 32 exist. A missing file is still tolerated (treated as
  an empty catalog, see below), so a partial folder under `GAMEVAL_PATH` works.
- `entries` has **every id the 727 cache holds** for that type. Names are unique per type.
- `component.json` keys are `"<interfaceId>:<componentId>"` and names are
  `"<interfaceName>:<componentName>"`.
- `clientscript.json` names keep Jagex's `[category,name]` form.

### The placeholder rule

An id with no proven name still has an entry, holding the placeholder `<type>_<id>`
(`loc_1234`, `spotanim_77`); clientscripts use `cs2_<id>` and components
`component_<iface>_<comp>`. **A placeholder is not a name.** The runtime API hides placeholders:
`name()` returns `null` for them, `id()` never resolves one, and `entries()` leaves them out.
`rawName()`, `allEntries()` and `isPlaceholder()` expose them when you really want the stored
string.

## Where the server reads them from

1. The folder named by `GAMEVAL_PATH` (`.env`, default `./gamevals`, i.e. the repo checkout when
   the working directory is the repo root). Edits there take effect on restart with no rebuild.
2. Otherwise the copy bundled into `core`'s jar under `/gamevals/<type>.json`.
   `core/build.gradle.kts` copies the repo's `gamevals/*.json` into `core`'s resources at build
   time (`CONTRACT.md`, `crosswalk/` and `work/` are not shipped).

Resolution is per file, so a folder missing one catalog still gets that one from the jar.

A catalog that is missing or unreadable (the generator may be mid-write) is treated as empty:
one warning is logged for that type and the file is re-examined after 30 seconds. Catalogs are
parsed lazily on first use and cached per type; `Gameval.reload()` drops the cache.

## Runtime API

`org.darkan.core.gameval.Gameval` (in `core`) is the process-wide lookup. It is an instance of
`GamevalCatalog`, which can also be constructed against any folder (tests do).

```kotlin
import org.darkan.core.gameval.Gameval

val worn = Gameval.requireId(Gameval.INV, "worn")     // Int; throws if the name is unknown
Gameval.name(Gameval.SEQ, 0)                          // "swarm_walk", or null for unknown/placeholder
Gameval.label(Gameval.NPC, 1)                         // "name (1)" or "1", for logs and UIs
Gameval.id(Gameval.LOC, "tree")                       // Int?, placeholders never match
Gameval.has(Gameval.QUEST)                            // catalog present and non-empty
Gameval.entries(Gameval.OBJ)                          // Map<Int, String> of named ids only
Gameval.allEntries(Gameval.OBJ)                       // placeholders included
Gameval.rawName(Gameval.OBJ, 4151) / Gameval.isPlaceholder(Gameval.OBJ, 4151)
Gameval.source(Gameval.OBJ)                           // FOLDER, BUNDLED or MISSING
```

Type constants: `OBJ NPC LOC SEQ SPOTANIM ENUM STRUCT PARAM INV INTERFACE COMPONENT CLIENTSCRIPT
VARP VARBIT VARC VARCSTR VARCLAN VARBITCLAN VARCLANSETTING VARBITCLANSETTING BAS HITMARK HEADBAR
CURSOR MAPELEMENT QUEST MODEL GRAPHIC SOUND MIDI MATERIAL FONTMETRICS`, and `Gameval.TYPES` lists
them all.

Components are addressed by the packed id the interface packets use, `(iface shl 16) or comp`:

```kotlin
Gameval.component(679, 0)                              // "inventory:inv" or null
Gameval.component(packed)                              // same, from a packed id
Gameval.componentLabel(679, 0)                         // "inventory:inv (679:0)" or "679:0"
Gameval.componentId("inventory:inv")                   // packed Int?  (requireComponentId throws)
GamevalCatalog.pack(iface, comp) / ifaceOf(packed) / compOf(packed)
```

`GamevalCatalog.placeholder(type, key)` builds the placeholder string for an id, for code that
needs to recognise one outside the catalog (the codegen does).

## Typed constants

For code that names an asset directly, every catalog also becomes a class of compile-time
constants in `org.darkan.core.gameval.gen` (`core/src/main/java/org/darkan/core/gameval/gen/`),
generated by `gamevals/gen_kotlin.py` and committed. The syntax is the same from Kotlin and Java:

```kotlin
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.gameval.gen.Component

player.inventory.add(Obj.coins, 1000)
player.anim(Seq.swarm_walk)
when (script) { Cs2.quicksort -> ... }
Component.inventory.inv                 // packed (679 shl 16) or 0, what the interface packets take
Component.inventory.IFACE               // 679
const val WORN = Inv.worn               // they are real constants: const val, when, annotations
```

| catalog | class | example |
|---|---|---|
| obj | `Obj` | `Obj.coins` |
| npc | `Npc` | `Npc.hans` |
| loc | `Loc` | `Loc.mcannoncrateboy` |
| seq | `Seq` | `Seq.swarm_walk` |
| spotanim | `Spotanim` | `Spotanim.<name derived from its seq>` |
| enum / struct / param | `Enum` / `Struct` / `Param` | `Enum.<name>` |
| inv | `Inv` | `Inv.bank` |
| interface | `Interface` | `Interface.inventory` (interface id 679) |
| component | `Component` | `Component.inventory.inv` (packed id), `Component.inventory.IFACE` (interface id) |
| clientscript | `Cs2` | `Cs2.quicksort` for `[proc,quicksort]` |
| varp / varbit / varc / varcstr | `Varp` / `Varbit` / `Varc` / `Varcstr` | `Varp.<name>` |
| varclan / varbitclan / varclansetting / varbitclansetting | `Varclan` / `Varbitclan` / `Varclansetting` / `Varbitclansetting` | |
| bas / hitmark / headbar / cursor / mapelement / quest | `Bas` / `Hitmark` / `Headbar` / `Cursor` / `Mapelement` / `Quest` | |
| model / graphic / sound / midi / material / fontmetrics | `Model` / `Graphic` / `Sound` / `Midi` / `Material` / `Fontmetrics` | |

Every one of the 32 catalogs has a class (34 files: `loc` spans three shards, see below);
a type is only skipped when its catalog is missing at generation time. Only interfaces the review process
proved carbon copies are named (206 of 1321), so `Component.<interface>` exists for those alone -
the 727 toplevel interface 548, for instance, is unnamed and has no class. The older flat
`com.rs.engine.variables` constants that used to live in `world` were a separate, earlier
generation and have been **removed**: every caller was rewritten to these classes, id-for-id,
by a local migration script (per-reference proof kept in the local
`gamevals/work/reports/legacy-var-migration.md`; an id whose catalog entry is still a
placeholder is left as the raw int literal with a `// <type>_<id> (legacy name: ..., unproven)`
comment). The 13 legacy names whose ids are still placeholders are persisted in
the local crosswalk's `legacy_placeholders.json` and surface in the local review ledger
until a proven name is added to the catalog.
Caution: legacy re-sync batches (the `docs/legacy-baseline.md` workflow) may
reintroduce `world/src/main/java/com/rs/engine/variables/` or imports of it; if one does,
re-run `migrate_legacy_vars.py` to migrate the new callers and delete the package again.

### Naming

- Only **real names** get a constant. Placeholders (`loc_1234`, `cs2_5002`, `component_548_2`)
  are skipped; use the runtime `Gameval` API for ids without a name.
- Identifiers keep Jagex's snake_case exactly. A name starting with a digit gets a leading
  underscore (`2h_sword` -> `_2h_sword`); any character that is not `[A-Za-z0-9_]` becomes `_`;
  a Java or Kotlin reserved word gets a trailing underscore (`class` -> `class_`, `val` ->
  `val_`); a name that is only underscores becomes `_<id>`; if two names collide after rewriting,
  the later id gets `_<id>` appended. Every rewrite is listed in the header comment of the
  generated file and on the generator's stdout, and the original dev-name is kept in a trailing
  comment on the constant.
- Clientscripts drop Jagex's `[category,name]` wrapper: `[proc,quicksort]` -> `Cs2.quicksort`.
  When the same bare name exists in more than one category, every one of them is emitted as
  `<category>_<name>` (`Cs2.proc_scrollbar_vertical`, `Cs2.clientscript_scrollbar_vertical`).
- `Component.<interface>.<component>` is the packed id `(iface shl 16) or comp`, and
  `Component.<interface>.IFACE` is the interface id; `IFACE` is uppercase so it can never collide
  with a Jagex name.
- The class names `Enum` and `Interface` shadow `kotlin.Enum` / `java.lang.Enum` only where you
  import them. Kotlin: an explicit `import org.darkan.core.gameval.gen.Enum` wins over the default
  import. Java: import `Enum` by name, not with `gen.*` (a wildcard import leaves it ambiguous).

### Regenerating

```
python3 gamevals/gen_kotlin.py             # or: ./gradlew :core:generateGamevals
python3 gamevals/gen_kotlin.py --check     # exit 1 if the committed files are stale (./gradlew :core:generateGamevals -Pcheck)
python3 gamevals/gen_kotlin.py --types obj,npc
python3 gamevals/gen_kotlin.py --catalogs <dir> --out <dir>   # other folders, e.g. for a dry run
```

Python 3 standard library only; the output is deterministic (ascending id, no timestamps) and a
file is only rewritten when its content changes, so running it twice is a no-op. A missing catalog
skips its type with a notice and leaves any previously generated class alone; a catalog that is
unreadable because the crosswalk is rewriting it is retried once after two seconds, then skipped.
The generated files are ordinary sources: commit them with the catalog change that produced them.
`GamevalConstantsTest` cross-checks the generated classes against the catalogs on the classpath
(`Obj.coins == Gameval.requireId(Gameval.OBJ, "coins")`, every constant is a named id, counts
agree) and skips whatever is not generated yet.

### Why the generated files are Java, and how big types are sharded

The generator emits Java classes of `public static final int` fields rather than Kotlin
`object`s with `const val`s. Both give `Obj.coins` from Kotlin and from Java and both are inlined
compile-time constants, but the JVM's per-class limits and the compilers' behaviour differ. All
of the following was measured with the toolchain in this repo (Kotlin 2.3.0, javac 25):

| approach | per-class ceiling | measured | compile time |
|---|---|---|---|
| Kotlin `object { const val }` | constant pool: 2 entries per constant (name Utf8 + Integer) of 65535 | 32,740 ok, 33,000 and 40,000 fail with `ClassTooLargeException` | **130-160 s** for one 32k object |
| Kotlin `abstract class { @JvmField val }` chain under an `object` | constructor bytecode 64 KB (`aload_0; sipush/ldc; putfield` per field) | 9,000 ok, 10,000 fails with `MethodTooLargeException: <init>` | 19 s per 9k class; not static, not const |
| Kotlin extension properties (`val Loc.x get() = ..`) split over files | facade constant pool | 30,000 in one file fails; no limit across files | Kotlin `Loc.x` only; Java would need `LocKt.getX(Loc.INSTANCE)`; not const |
| **Java `public static final int` class chain** | constant pool: 2 entries per constant | 32,760 ok, 32,765 fails (`too many constants`) | **0.7 s** per 32k class with javac; the Kotlin compiler reads 70k such constants from Java sources in ~4 s |

`loc` and `model` have ~74k ids each in the 727 cache, so a single class cannot hold every named
id of those types. The chosen layout keeps `Loc.<name>` for every name:

- The first 32,000 constants (by ascending id) live in the public class `Loc`; the rest continue
  in package-private classes `Loc2`, `Loc3`, ... and the chain is linked by inheritance,
  `Loc extends Loc2 extends Loc3`.
- Java inherits static fields through a subclass, so `Loc.<name>` resolves whichever shard holds
  it. Kotlin 2.x (K2) resolves static members of a Java class through a Java subclass as well,
  which was verified for a three-level chain from another package in `const val`, `when` branches
  and annotation arguments, all constant-folded in the bytecode. (This is exactly what a Kotlin
  `object` cannot do: Kotlin never inherits statics into a Kotlin type, so a `const val` object
  would have forced `Loc2.<name>` on callers for the overflow.)
- The shard size is 32,000 rather than the 32,760 ceiling to leave room for the class's own
  constant-pool entries; it is `SHARD` in the generator. Component classes are nested per
  interface and never come near the limit.

With all 32 catalogs generated (34 files, ~148k constants, `loc` in three shards; `model` has
only 106 named ids so it never overflows) `:core:compileJava` takes about 1.5 s and a full `:core:compileKotlin` 22 s, the same as
before the constants existed within noise; the equivalent Kotlin objects would have added several
minutes to every clean build.

The trade-off is only cosmetic: `Loc2`, `Loc3` are visible in the package's file list and to
Java code in that package, and their javadoc says to go through `Loc`.

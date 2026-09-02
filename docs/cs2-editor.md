# CS2 script editor

Decompiles RuneScape clientscripts (cache index 12) into TypeScript, recompiles that
TypeScript back into bytecode, and runs it against a live interface.

Everything lives in `tools/src/main/kotlin/org/darkan/tools/cs2/`.

## Commands

All of these run through the tools CLI:

```bash
./gradlew :tools:run --args="cs2 <sub-command>"
```

| Sub-command | What it does |
|---|---|
| `decompile <id>` | Prints one script as TypeScript, then its dead code |
| `decompile-all [dir]` | Writes every script, the declarations and `dead-code.md`; the folder defaults to `cs2-dump` |
| `roundtrip [n]` | The stability check (see *Fidelity*) over every script |
| `fixpoint <id> [dir]` | The same checks for one script, writing both sources out to diff |
| `types [OP:slot]` | What the corpus says constants mean; with a slot, why it says so |
| `diff <id>` | Shows where one script's re-compiled bytecode diverges |
| `verify` | Decodes and re-encodes every script, asserting byte identity |
| `analyse` | Stack-simulates every script; confirms all 1006 opcode signatures |
| `dump <id>` / `info <id>` | Raw instruction listing / header |
| `run <id> [args]` | Executes a script on the VM against a no-op host |
| `interface <id>` | Runs one interface's load scripts and reports what changed |
| `interfaces` | Runs every interface's load scripts |
| `declarations [dir]` | Writes just the ambient TypeScript declarations |
| `export <dir> [id]` | Decompiles one script into a folder |
| `compile <dir> <id>` | Compiles an edited script from disk and compares it to the cache |
| `sources <dir>` | Compiles every source file in a folder |
| `watch [dir] [secs]` | Hot-reloads scripts as they are saved |
| `apply-names` / `sync-names` / `check-names` | Reconcile a folder's names with `gamevals/*.json` |

## Pipeline

```
cache bytes ──Cs2Codec──▶ instructions ──Cs2Lifter──▶ expressions
                                              │
                                     Cs2Structurer (if/else, while, switch)
                                              │
                                       Cs2Cleanup (dead code, declarations)
                                              │
                                        Cs2Types (what constants mean)
                                              │
                                        Cs2Emitter ──▶ TypeScript
                                                            │
                                    Cs2Parser ──▶ IR ──▶ Cs2CodeGen
                                                            │
                                                    Cs2Codec ──▶ cache bytes
```

`Cs2Analyzer` sits alongside it, resolving what the bytecode does not state outright: every
script's return signature (by fixpoint across calls, with a search for the recursive ones),
which param ids yield strings and what their type characters are (from `Cache.params`),
confirmation that the opcode table's stack effects balance, and the cached corpus-wide
`Cs2Types` analysis.

`Cs2Structurer` recovers a condition from the graph of test blocks between it and its body:
the run of pure tests and lone jumps with exactly two ways out - into the body, or past it -
reads back as one `&&`/`||` expression, which is what leaves the corpus with no `goto` at all.

## Where the opcode table comes from

It is generated, not hand-written. `tools/scripts/extract_cs2_sigs.py` reads the
deobfuscated client's opcode handlers and derives each opcode's stack effect; the scan is
brace-aware, so `if`/`else` arms count once rather than twice and an early `return` is
treated as an alternative to the rest of the handler.
`tools/scripts/gen_cs2_opcodes.py` turns that into `Cs2Opcodes.kt`.

Rerun both after changing the client's CS2 engine:

```bash
python3 tools/scripts/extract_cs2_sigs.py /tmp/sigs.json
python3 tools/scripts/gen_cs2_opcodes.py /tmp/sigs.json \
    tools/src/main/kotlin/org/darkan/tools/cs2/Cs2Opcodes.kt
```

There are **1006** opcodes. The client's enum carried 44 of them as unnamed `instrNNNN`
entries; those now have real names.

## What the decompiler changes, and what it never changes

The bytecode a script re-compiles to no longer has to be the bytes the cache holds. What may
differ is layout and dead code:

- **The trailing implicit return is dropped** when nothing can reach it; the code generator
  writes a fresh one, the zero of each declared return type.
- **Def-initialiser stores are folded into declarations.** `int1 = 0; ... [int1, ...] = f()`
  becomes `let [int1, ...] = f()` where nothing reads the zero and the later store can carry
  the declaration.
- **Local slots are handed out in declaration order**, so a local declared where it is first
  written can end up in a different slot than the cache gave it.
- **Branches and switches are laid out canonically**: a comparison enters its body over a
  single unconditional branch, and `default:` is reached by a jump and written last.
- **Unreachable code is removed** from the source and reported instead.

What never changes is what the script does. `cs2 roundtrip` proves that for every script
(see *Fidelity*); 5501 of the 6567 still compile to the cache's exact bytes anyway.

## What the emitted TypeScript means

Some of the output is shaped by what has to compile back, not by taste:

- **`let x: T = v;`** compiles to a push and a store like any assignment - it is a
  declaration only in the sense that it introduces the name. **`let x: T;`** reserves the
  slot and produces no instructions; that is where every local the body does not introduce
  itself is declared. An older dump whose header reads `let intN: number = 0;` therefore now
  compiles to an extra store per local - re-run `decompile-all` to refresh it.
- **`let [a, b] = f();`** and **`[a, b] = f();`** put a call's results straight into locals or
  variables; the code generator stores them back to front, which is the order the stacks pop
  in. `const [tmpInt0, ...] = f();` is what is left when the results are not consumed that way.
- **`f(a, ...g(), b)`** is a call that happens between two of another call's arguments. Named
  temporaries cannot say that: the statement binding them would move the call ahead of the
  arguments pushed before it.
- **`[a, b] = [x, y]`** is a parallel assignment: CS2 computes both values before storing
  either, which is how it compiles assignments that read each other.
- **Hook callbacks carry their type spec**: `hook(script44, "IIissoioioioi", [args], [triggers])`.
  Those characters are the compiler's own types - `I` component, `o` obj, `s` string - and the
  client only inspects `s`, so reconstructing them would lose information.
- **An opcode's own operand rides as a type argument**: `ccCreate<1>(...)`. Several component
  opcodes use it to pick which active-component register they act on.
- **`while (true)`** is a loop whose test sits part way through the body, written out as
  `if (!c) break;`. No script in this revision has one; the form exists for hand-written
  sources.
- **`goto(...)`** appears where control flow has no structured form. No script in the 727
  cache needs one any more.

## Types

CS2 records no types: an item id, a colour and a component hash are all plain integers.
`cs2 types` works out what they are from how they are used, across all 6567 scripts at once,
and the decompiler writes them out.

- A symbol - a script parameter, a return slot, a variable, a local, an opcode result or
  argument slot - takes a kind only when everything flowing through it agrees. Two kinds mean
  it is used for two things, and it drops back to a plain number.
- The evidence is the opcode table's own argument types, hook format characters, the type
  codes `enumOp` and its relatives are given, the cache's param definitions, and values
  passed between scripts. Nothing is guessed from a constant's value.
- Opcode results and untyped argument slots are *learned* from the corpus: three or more
  agreeing uses from at least two scripts, at least one of them a value that something typed
  where it stands. `cs2 types <OP:slot>` prints that evidence.
- The type characters are checked against the corpus before they are used, and one that is
  ever contradicted is dropped. `i` (plain int) claims nothing, so it is ignored.

Constants are then spelled out - `item(Obj.coins)`, `component(Component.wornitems.head)`,
`colour(0xFF0000)`, `tile(x, y, plane)`, `true` / `false`. Every id a catalog covers goes
through its constant, including the ones with no proven name yet: those read as their
placeholder, `struct(Struct.struct_1234)`, `component(Component.interface_978.component_978_36)`,
exactly as scripts (`cs2_1620`) and variables (`varc_157`) already do. A placeholder is a name
an editor can rename and `cs2 sync-names` can write back to the catalog; a bare number is not.
What stays a number is what no catalog covers: `-1` and the client's other sentinels, and a
packed component the catalog has no entry for, which keeps `component(iface, comp)`.

A type code reads as `type_obj`, `type_graphic`, `type_int`. Signatures and declarations use
an alias named after the helper: `intArg0: colour`, `let int3: item = ...`, and `flag` for
booleans, TypeScript having `boolean` already. Every alias is `number`.

`gamevals.d.ts` declares all of them - 326 329 lines, 18 MB - with each id in the doc comment
above its constant. It costs nothing to check: `tsc` reads the whole dump, 6570 files and
569 000 lines, in under two seconds.

## Dead code

`decompile-all` writes `dead-code.md`: everything in the bytecode that cannot affect what a
script does, grouped by script, with the code itself.

| Kind | What it means |
|---|---|
| `unreachable` | Nothing can reach it. Dropped from the source, reported here. |
| `dead store` | The value is overwritten before anything reads it. Kept in the source. |
| `unused result` | A call runs for its effect and its results are never read. |
| `constant condition` | Both sides of a test are constants. |
| `empty branch` | An `if` with nothing in it, or with only an else. |

A constant written to a local before anything else writes it is the declaration the source
had, and is not reported. Neither is the default `return` every script ends with, nor an
unreachable block that only jumps somewhere.

## Fidelity

`cs2 roundtrip` is the contract. For every script it runs
`bytes0 -> src1 -> bytes1 -> src2 -> bytes2` and requires all of:

- **`bytes2 == bytes1`** - compiling the decompiled source is a fixed point;
- **`src2 == src1`** - decompiling the rebuilt bytecode gives the same source back;
- **the rebuild simulates** - `Cs2Analyzer.validate` finds no stack fault;
- **the work census matches** - the multiset of reachable instructions, jumps and local slot
  numbers aside, equals the original's minus the declarations that were folded away;
- **both versions behave the same** - each runs on `Cs2Vm` against a host whose answers are
  derived from what it was asked, so the two runs take the same branches or visibly do not.
  Several argument vectors are tried, and calls into other scripts go to the cached originals.

It also reports how many scripts are still byte-identical to the cache. That is
informational: byte identity is no longer required, behaviour is.

Where the structurer cannot reproduce a script's shape, the decompiler falls back to a literal
block-by-block rendering - labels and explicit jumps - which keeps every block and so keeps
the bytes exactly.

The emitted TypeScript also has to type-check, which constrains the format further: scripts
are not `export`ed (clientscripts share one flat namespace), parameter and return order follow
the order values are pushed rather than the grouped stack counts, and opcodes drawing from
more than one stack take widened parameter types because their argument order belongs to each
call site rather than to the opcode.

## The interface editor

`tools interface <id>` opens the editor: the interface renders the way the client draws it,
with a component tree, live property editing, click-to-pick selection, and a CS2 panel that
lists every hook and fires it on demand. F2 and F3 toggle the panels.

Rendering lives in `tools/.../rsinterface/`. `ComponentState` is the mutable per-component
state scripts drive; the fields that affect layout have private setters, so a change cannot
skip the relayout. `InterfaceCs2Host` answers component opcodes against that state, and
`Cs2HookDispatcher` substitutes the client's event placeholders - mouse position, source
component, slot, clicked option, typed key - into a hook's bound arguments before running it.

Both kinds of hook are listed: the ones baked into the component definitions, and the ones
scripts bind at runtime with `IF_SETON*` / `CC_SETON*`.

## Running scripts

`Cs2Vm` implements the core instruction set — the three operand stacks, locals, the global
arrays, branches, switches and calls — and hands every other opcode to a `Cs2Host`. The host
returns results shaped to the opcode's signature, so the stacks stay correct even for
opcodes it does not implement. That seam is what the interface editor plugs into.

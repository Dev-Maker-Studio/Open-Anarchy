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
| `verify` | Decodes and re-encodes every script, asserting byte identity |
| `analyse` | Stack-simulates every script; confirms all 1006 opcode signatures |
| `decompile <id>` | Prints one script as TypeScript |
| `decompile-all [dir]` | Writes every script plus `cs2.d.ts` |
| `declarations [dir]` | Writes just the ambient TypeScript declarations |
| `roundtrip` | Decompiles and recompiles everything, checking byte identity |
| `diff <id>` | Shows where one script's re-compiled bytecode diverges |
| `dump <id>` / `info <id>` | Raw instruction listing / header |
| `run <id> [args]` | Executes a script on the VM against a no-op host |
| `interface <id>` | Runs one interface's load scripts and reports what changed |
| `interfaces` | Runs every interface's load scripts |
| `compile <dir> <id>` | Compiles an edited script from disk and checks it still matches |

## Pipeline

```
cache bytes ──Cs2Codec──▶ instructions ──Cs2Lifter──▶ expressions
                                              │
                                     Cs2Structurer (if/else, while, switch)
                                              │
                                        Cs2Emitter ──▶ TypeScript
                                                            │
                                    Cs2Parser ──▶ IR ──▶ Cs2CodeGen
                                                            │
                                                    Cs2Codec ──▶ cache bytes
```

`Cs2Analyzer` sits alongside it, resolving three things the bytecode does not state
outright: every script's return signature (by fixpoint across calls, with a search for the
recursive ones), which param ids yield strings (from `Cache.params`), and confirmation that
the opcode table's stack effects actually balance.

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

## What the emitted TypeScript preserves

Byte-identical recompilation constrains the output format. These are not stylistic choices:

- **Locals are declared in slot order.** The compiler assigns slots by declaration order
  within each type, so locals can be renamed freely without changing the bytecode.
- **Hook callbacks carry their type spec**: `hook(script44, "IIissoioioioi", [args], [triggers])`.
  Those characters are the compiler's own types — `I` component, `o` obj, `s` string — and
  the client only inspects `s`, so reconstructing them would lose information.
- **An opcode's own operand rides as a type argument**: `ccCreate<1>(...)`. Several
  component opcodes use it to pick which active-component register they act on.
- **`!(a == b)`** means the comparison was used to *skip* the body rather than enter it.
  Both compile to the same test with different branch layouts.
- **`[a, b] = [x, y]`** is a parallel assignment: CS2 computes both values before storing
  either, which is how it compiles assignments that read each other.
- **`default:` position matters.** A switch's default arm either sits inline right after the
  switch or is reached by a jump and laid out last; where the clause appears records which.
- **`goto(...)`** appears where a script's control flow has no structured form.

Composite values are spelled out rather than left as packed integers: `component(755, 28)`,
`tile(x, y, plane)`, `item(...)`, `npc(...)`, `colour(0x...)`, `true`/`false`. What each
argument means is read out of the client's own handlers, not guessed.

Variables and scripts are plain identifiers - `varbit11654`, `script6559` - so they can be
renamed. The id that binds them lives outside the name: in a script's `// clientscript N`
header, and in the doc comment on its `vars.d.ts` declaration. Rename with the editor and
recompile with `cs2 compile <dir> <id>`.

`cs2.d.ts` declares all of the above plus every opcode, so an editor can type-check the
decompiled scripts and offer completions.

## Fidelity

`cs2 roundtrip` is the contract: decompile every script, recompile it, compare bytes.

Where the structurer cannot reproduce a script's exact branch layout, the decompiler falls
back to a literal block-by-block rendering — labels and explicit jumps — which always
round-trips. Readable output is preferred; fidelity wins when they conflict.

The round-trip also checks that no mismatch changed the actual work done, by comparing
instruction censuses with jumps excluded. A layout difference is a cosmetic problem; a
gained or lost instruction is a bug.

The emitted TypeScript also has to type-check, which constrains the format further: scripts
are not `export`ed (clientscripts share one flat namespace), parameter and return order
follow the order values are pushed rather than the grouped stack counts, and opcodes drawing
from more than one stack take widened parameter types because their argument order belongs to
each call site rather than to the opcode.

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

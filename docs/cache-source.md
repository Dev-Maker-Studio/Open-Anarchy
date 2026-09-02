## Definition encoders

`DefinitionJsonCodec<T>` can only store a type as editable JSON when its encoder reproduces the
shipped file exactly - `encode(decode(bytes)) == bytes` for every file - otherwise the type has to
keep a `.pristine` copy beside every file it cannot rebuild. This section records the measured
parity per type. The numbers come from the tests, run over the whole 727 cache:

- `core/src/test/kotlin/world/gregs/voidps/cache/config/ConfigEncoderParityTest.kt` (index 2)
- `core/src/test/kotlin/world/gregs/voidps/cache/definition/InterfaceEncoderParityTest.kt` (index 3)

Both enumerate files through `Cache.files(index, archive)`, decode with the production decoder
(including `changeValues`), encode into a fresh `BufferWriter` and compare byte for byte. They skip
with a message when `../cache/` is absent.

### Index 2 (`config`) and index 3 (`interfaces`)

`order` and `shadow` are how many of those files have to carry the metadata described below - the
rest are reproduced from their values alone.

| index | archive | type | decoder / encoder | files | byte identical | residuals | order | shadow |
|---|---|---|---|---|---|---|---|---|
| 2 | 1 | `underlay` | `config/…/UnderlayDecoder` / `UnderlayEncoder` | 171 | 171 | none | 13 | 0 |
| 2 | 3 | `idk` | `IdentityKitDecoder` / `IdentityKitEncoder` | 652 | 652 | none | 270 | 0 |
| 2 | 4 | `overlay` | `OverlayDecoder` / `OverlayEncoder` | 248 | 248 | none | 135 | 1 |
| 2 | 5 | `inv` | `InventoryDecoder` / `InventoryEncoder` | 674 | 674 | none | 0 | 0 |
| 2 | 11 | `param` | `definition/…/ParamDecoder` / `ParamEncoder` | 2,583 | 2,583 | none | 510 | 0 |
| 2 | 16 | `varp` | `PlayerVariableParameterDecoder` / `…Encoder` | 2,696 | 2,696 | none | 0 | 0 |
| 2 | 19 | `varc` | `ClientVariableParameterDecoder` / `…Encoder` | 1,992 | 1,992 | none | 0 | 0 |
| 2 | 26 | `struct` | `definition/…/StructDecoder` / `StructEncoder` | 11,389 | 11,389 | none | 0 | 0 |
| 2 | 32 | `bas` | `BASDecoder` / `BASEncoder` | 2,574 | 2,574 | none | 895 | 1 |
| 2 | 34 | `mapscene` | `MapSceneDecoder` / `MapSceneEncoder` | 107 | 107 | none | 0 | 0 |
| 2 | 35 | `quest` | `QuestDecoder` / `QuestEncoder` | 204 | 204 | none | 201 | 0 |
| 2 | 36 | `mapelement` | `WorldMapInfoDecoder` / `WorldMapInfoEncoder` | 1,220 | 1,220 | none | 1,213 | 2 |
| 2 | 46 | `hitmark` | `HitSplatDecoder` / `HitSplatEncoder` | 48 | 48 | none | 48 | 0 |
| 2 | 47 | `varclan` | `ClanVarDecoder` / `ClanVarEncoder` | 2,845 | 2,845 | none | 0 | 0 |
| 3 | all | `interface` component | `InterfaceDecoder` / `InterfaceEncoder` | 65,239 | 65,239 | none | - | - |

**27,403 index 2 files and 65,239 interface components, all byte identical. No pristine copies are
needed for any of these types.** Index 2's other 31 archives have no decoder and stay raw
(`RawFilesCodec`).

`InterfaceEncoder` was already byte exact and needed no change: index 3 components are a fixed
field layout rather than an opcode list, and the existing round trip test had been asserting byte
identity all along. `InterfaceEncoderParityTest` is the standalone proof, so a change to either
side shows up as a byte difference rather than only as a field difference.

### What byte exactness needed

Three things, all of which the codec stores on the definition and none of which change what the
existing consumers read:

1. **The opcode order.** Jagex's packer does not emit opcodes in a fixed order - 1,213 of the 1,220
   map elements, 859 of the 2,574 base animation sets and 201 of the 204 quests are in some other
   order - and two opcodes routinely write the same field (`hitmark` writes fade either as the bare
   opcode 11 or as opcode 14 with a cycle), so which opcodes a file carried cannot be derived from
   the values. `OpcodeOrdered.opcodeOrder` records the opcodes as the file had them;
   `OpcodeEncoder` replays that list and takes each payload from the fields. It is nulled again
   when the recorded list is the canonical ascending one the encoder derives on its own
   (`OpcodeEncoder.normalise`, called from each decoder's `changeValues`), so a canonically packed
   definition - and any definition authored by hand - carries no order at all.
2. **Payloads a repeated opcode overwrote.** Four files in the whole of index 2 carry the same
   opcode twice with different values: overlay 94 has two slot bytes, base animation set 1799 two
   stand turn pairs, map elements 779 and 780 two background colours. Only the last write survives
   on the definition, so `OpcodeOrdered.shadowedPayloads` keeps the earlier ones verbatim, indexed
   against `opcodeOrder`, and the encoder replays them in place. It stays null when a repeat
   carries the same bytes as the write that replaced it (base animation sets 1205 and 2410 repeat
   an opcode with an identical payload), and for every file with no repeat at all.
3. **Values the decoders threw away.** New fields, all additive: `OverlayDefinition.colourRgb` /
   `blendColourRgb` (the packed hsl the client derives is not invertible),
   `WorldMapInfoDefinition.displayFlags` / `randomisedMinimapPosition` (whole flag bytes, of which
   the client reads two bits), `QuestDefinition.type` (opcode 6), `ClanVarDefinition.typeChar` and
   `PlayerVariableParameterDefinition.typeChar` (the cp1252 descriptor; `CS2Type.forJagexDesc` is
   many to one), `IdentityKitDefinition.unknown3` (opcode 3, which the client's `IdkType` does not
   have at all, in 13 files), `StructDefinition.paramRecords` (the 37 structs that carry the same
   param key twice, where the map keeps only the last - the same data the old duplicate
   `config/decoder/StructDecoder` patched by hand), and the whole lower half of `BASDefinition`
   (the model box, camera easing, random stand table and per slot transforms, opcodes 26-56).

Two ordering rules matter to any future definition codec: `Parameterized.readParameters` decodes
into an insertion ordered `Int2ObjectArrayMap` and `Extra.readParameters` into an
`Object2ObjectLinkedOpenHashMap` (it was a plain open hash map, which iterated in hash order and
reordered two quests' params on the way out). A decoder that takes a `Parameters` catalog must be
built with `Parameters.EMPTY` when its output is going to be encoded again: with a catalog the
extras are keyed by param *name*, and a name cannot be turned back into the three byte key.

### Decoder bugs found and fixed

The parity work is also the first full read of these archives, and four decoders were wrong on 727:

| decoder | bug | effect before |
|---|---|---|
| `IdentityKitDecoder` | model ids (opcode 2) and head models (60-69) are big smarts, not shorts | 29 of 652 files ran off the end of the buffer, and 8 more left trailing bytes |
| `HitSplatDecoder` | the placement string (opcode 8) is a `gjstr2`, and the font and four sprites are big smarts | 47 of 48 files desynced - every opcode after the string was garbage |
| `MapSceneDecoder`, `WorldMapInfoDecoder`, `QuestDecoder` | sprite ids (mapscene 1, mapelement 1/2/18, quest 17) are big smarts | correct only because every shipped value fits in two bytes |
| `QuestDecoder` | opcodes 6 and 15 were read and discarded | `type` lost on 9 quests; `questPointRequirement` never populated |

Two duplicate types were removed. `config/decoder/StructDecoder` + `config/data/StructDefinition`
duplicated the `definition` package pair that `Cache.structs` actually uses, and carried a hand
written patch for the seven structs whose duplicate params the map dropped - now handled properly
by `paramRecords`. `config/decoder/RenderAnimationDecoder` + `RenderAnimationDefinition` were a
second, *wrong* reader of archive 32 (shorts where the format has big smarts, five values in
opcode 27 where the client reads six, two in 56 where it reads three); archive 32 is one type, and
`BASDecoder` is the reader that matches the client. Neither removed class was referenced outside
its own file.

`DefinitionDecoder.readLoop` is what records the order and the shadowed payloads, and it only does
so for definitions that implement `OpcodeOrdered`; every other type decodes exactly as before.
`DefinitionDecoder.read` is now public so a codec (and the parity tests) can drive the opcode loop
itself and know where each opcode's bytes started and ended. `Reader.readPrefixedString` is the
`gjstr2` reader the client calls `readJagString`, the exact inverse of `Writer.writePrefixedString`;
it replaces the private copy `QuestDecoder` had.

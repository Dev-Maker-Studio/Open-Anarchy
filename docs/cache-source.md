## Definition encoders

`DefinitionJsonCodec` needs `encode(decode(bytes)) == bytes` or the file keeps a `.pristine` copy.
The seven config types of indices 16 to 22 reach that for **every file in the cache** -
`DefinitionEncoderParityTest` walks the reference table of each index (not the decoder's `size`,
which rounds up to whole archives and invents ids), decodes with the decoder's `readLoop` and
compares the encoder's output to the shipped bytes:

| type | index | files | byte-identical | residuals |
|---|---|---|---|---|
| loc | 16 | 73,893 | 73,893 | none |
| enum | 17 | 6,048 | 6,048 | none |
| npc | 18 | 15,662 | 15,662 | none |
| obj | 19 | 25,355 | 25,355 | none |
| seq | 20 | 17,186 | 17,186 | none |
| spotanim | 21 | 3,252 | 3,252 | none |
| varbit | 22 | 11,713 | 11,713 | none |

**No file of these types needs a pristine copy.**

Getting there took two things the decoded fields could not hold, both on `OpcodeOrdered`, which
the seven definitions implement:

- **`opcodeOrder`** - Jagex's packer wrote fields in no fixed order. Only 1,914 of 73,893 locs,
  121 of 15,662 npcs and 7,231 of 25,355 objs are in ascending opcode order; the rest carry the
  file's own order and the encoder follows it. It is null when the order is the ascending one the
  encoder emits by itself, which is what a definition authored by hand gets - and the decoder
  nulls it only after checking the encoder agrees, so a field the encoder cannot reproduce from
  keeps the explicit order rather than silently changing the file.
- **`shadowedRecords`** - a file may hold the same opcode twice with different payloads (1,280
  locs, 597 npcs, 354 objs); the decoder keeps the last, as the client does. The payload of every
  overwritten record is kept verbatim and replayed in place.

The rest was decoder loss, fixed by adding fields (no existing field changed meaning):

| type | field | what was lost |
|---|---|---|
| loc | `singleAnimation` | opcode 24's id when it is -1, which the client throws away |
| loc | `rawPercents` | opcode 106's animation odds, rescaled to 65535ths of their sum on read |
| loc, npc | `rawOptions` | which opcode an option came from - 30 to 34 and 150 to 154 share one array |
| loc, npc, obj | `paramRecords` | opcode 249 records when a file lists a param id twice |
| npc | `unusedMovementType` | opcode 128, read and discarded by both client and decoder |
| enum | `arraySize` | the array capacity of the opcode 7 and 8 forms |
| enum | `entries` | entry records when a file lists a key twice; the map is now insertion ordered |
| seq | `soundVolumeRecords`, `soundSpeedRecords` | which frame each opcode 19 and 20 record set |
| seq | `interleaveRecords` | opcode 3's indices when the file repeats one |
| seq | `unusedFlag16` | opcode 16, a flag this revision's client ignores |

One caveat for the codec: decode with the decoder's `readLoop`, never `load`. `changeValues`
derives values that are not in the file - an obj's note and lend templates are copied wholesale
from another definition, a seq's two precedence fields are filled in from its interleave order -
and a definition re-encoded after that no longer matches its file.

Also fixed: loc transform ids (opcodes 77 and 92) are bigSmarts, not shorts, so the shared
`Transforms.writeTransforms` helper wrote `-1` as `0xffff`, which reads back as a 4-byte smart.
Npc transforms really are shorts, so the two cannot share one writer: the helper is gone and each
encoder writes its own.

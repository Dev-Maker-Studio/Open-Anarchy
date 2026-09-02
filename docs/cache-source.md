# Cache source tree: unpacking, editing and rebuilding the 727 cache

The revision-727 cache is a JS5 sector store (`main_file_cache.dat2` + `.idx*`). It is a
binary blob to git, so it lived in its own git-lfs repository and every edit meant re-uploading
876 MB. The **cache source tree** replaces that: the whole cache unpacked into one file per
asset (pretty-printed JSON where a codec exists, the raw decompressed bytes otherwise) plus a
little metadata, in a plain git repository with no LFS. The server rebuilds the packed cache
from it - incrementally, on startup - and serves the result over JS5.

The gameval catalogs (`gamevals/<type>.json`, see `docs/gameval.md`) are cache assets too, so
they live in the source tree and nowhere else.

This document is the design and the contract; `docs/deploy.md` ("The cache source tree") covers
what changes on the servers and in CI.

## Goals and guarantees

1. **Parity.** Unpacking a cache and packing the result with no edits reproduces every JS5
   container byte for byte, every reference table byte for byte, and therefore the same CRCs,
   whirlpools and version table the client already trusts. `cache verify` proves it and is run
   in tests. Parity is *by construction*: a codec that cannot reproduce a file exactly keeps the
   original bytes next to its editable form (the *pristine* fallback, below) until that form is
   edited.
2. **Git without LFS.** One file per asset, deterministic output, stable names keyed by id, no
   churn on re-unpack. Binary assets (models, sounds, textures, sprites) stay binary; they are
   incompressible either way.
3. **Feature branches.** Anyone can branch the source tree, add or change assets, and run a
   server on it: the server builds the cache from whatever tree it is pointed at.
4. **Fast rebuilds.** The packed cache is a build output in a gitignored data directory with a
   manifest of what it was built from. A rebuild stats the tree, repacks only the archives whose
   inputs changed, and is a no-op when nothing did.

## What determines the served bytes

Everything the client can observe is reproduced from the tree. The probe over the whole 727
cache (2026-09-02) established what that takes:

| Fact | Consequence |
|---|---|
| Every gzip container reproduces with Java's `Deflater` level 6, default strategy, standard 10-byte header (2 archives in index 19 carry OS byte `0xff`) | gzip needs no stored bytes; the OS byte is metadata when nonstandard |
| bzip2 containers (3,783 archives + 13 reference tables) reproduce at block size 1 with **libbzip2 1.0.8**, except the reference tables of indices 2 and 9, which only **commons-compress** reproduces | `BZip2Encoder` is a faithful Kotlin port of libbzip2's compressor; the odd two are a recorded `bzip2` variant |
| No LZMA. Reference tables use formats 6 and 7, flags `0x1` (names) and `0x2` (whirlpool, index 30 only) | nothing else needs encoding; the client ignores `0x4`/`0x8` anyway |
| Every archive in indices 0-35 ends in a 2-byte version trailer; reference tables have none | the trailer is the low 16 bits of the version in the table; JS5 never sends it |
| Index 0 groups are split into 3 chunks per archive; everything else is 1 chunk | raw multi-file groups keep their chunk layout verbatim |
| `l<x>_<y>` and `n<x>_<y>` map archives are XTEA encrypted; the client is sent the keys | stored decrypted with the key in metadata, re-encrypted on pack |
| The client validates the CRC (and whirlpool where flagged) of the exact container bytes served, and the RSA-signed whirlpool of the version table | CRCs, whirlpools and the version table are always derived, never stored |

## Layout of a source tree

```
<tree>/
  cache.json                  format version, revision, index count
  gamevals/<type>.json        the 40 canonical name catalogs (+ CONTRACT.md)
  <index>/index.json          reference-table metadata + per-archive metadata
  <index>/...                 the archives, laid out by the index's codec
```

Index directories use Jagex's own names:

| id | dir | id | dir | id | dir |
|---|---|---|---|---|---|
| 0 | `anims` | 13 | `fontmetrics` | 26 | `materials` |
| 1 | `bases` | 14 | `vorbis` | 27 | `config_particle` |
| 2 | `config` | 15 | `midi_instruments` | 28 | `defaults` |
| 3 | `interfaces` | 16 | `config_loc` | 29 | `config_billboard` |
| 4 | `synth_sounds` | 17 | `config_enum` | 30 | `dlls` |
| 5 | `maps` | 18 | `config_npc` | 31 | `shaders` |
| 6 | `midi_songs` | 19 | `config_obj` | 32 | `loading_sprites` |
| 7 | `models` | 20 | `config_seq` | 33 | `loading_screens` |
| 8 | `sprites` | 21 | `config_spotanim` | 34 | `loading_sprites_raw` |
| 9 | `textures` | 22 | `config_varbit` | 35 | `cutscenes` |
| 10 | `binary` | 23 | `worldmapdata` | 36 | `index36` (empty in 727) |
| 11 | `midi_jingles` | 24 | `quickchat` | | |
| 12 | `clientscripts` | 25 | `quickchat_global` | | |

### `index.json`

```json
{
  "index": 19,
  "format": 6,
  "revision": 1005,
  "flags": [],
  "compression": "bzip2",
  "archives": {
    "0": {"version": 1, "compression": "gzip"},
    "5": {"version": 1, "compression": "bzip2"},
    "99": {"version": 0, "compression": "gzip"}
  }
}
```

- `format`, `revision`, `flags` (`names`, `whirlpool`) and `compression` describe the index's
  reference table (index 255, archive = index id) and reproduce it exactly.
- `bzip2` - `commons` when the reference table needs commons-compress rather than the libbzip2
  port. Only indices 2 and 9 do; omitted everywhere else.
- `archives` has one line per archive, sorted by id. Fields, all omitted when default, and always
  written in this order:
  - `version` - the reference-table version (full int; the container trailer is its low 16 bits).
  - `compression` - `none` | `bzip2` | `gzip` of the archive's container. Always written.
  - `gzipOs` - the gzip header OS byte when not 0.
  - `bzip2` - `commons` when the archive's payload needs commons-compress. Nothing in 727 does.
  - `nameHash` - the archive's name hash, only in indices whose table has names. Stored as the
    number: names are a display concern and never a source of hashes at pack time.
  - `file` - the single file's id when an archive has one file whose id is not 0.
  - `fileCount` - the number of files, when the codec writes the group whole and the ids run
    `0` until the count.
  - `files` - the explicit id list, when the codec writes the group whole and the ids are not
    `0` until *n*. Between them, `file`, `fileCount` and `files` carry the file ids for the
    codecs whose layout cannot: a codec that writes a file per file needs none of them.
  - `fileNames` - `{"<fileId>": <nameHash>}` for the two indices with file names (23, 31).
  - `xtea` - `[k0, k1, k2, k3]` for encrypted map archives.
  - `pristine` - `{"<fileId>": "<sha256 of the editable file>"}`, see below.
  - `opaque` - `true` for an archive whose container could not be opened, see below.

Everything else (CRC, whirlpool, sizes, the file id list of an archive whose codec lays its files
out by id) is derived from the data files.

The file is written by hand rather than serialised, byte for byte the same way every time, so
re-unpacking an unchanged cache leaves it alone and the builder can tell a changed archive from an
untouched one by comparing single lines.

### Opaque archives

Eight `l<x>_<y>` map archives are XTEA encrypted with keys that were never published, so their
plaintext cannot be reached, let alone re-compressed. They are stored as `<archive>.container` -
the container bytes exactly as the cache holds them, minus the two byte version trailer the
metadata already carries - and written straight back out with the trailer reattached. The entry
is marked `"opaque": true` and carries no key. Region ids: `73_66`, `73_70`, `73_72`, `73_74`,
`75_66`, `75_70`, `75_72`, `75_74`.

### Codecs

Each index is unpacked by one codec (`world.gregs.voidps.cache.source.codec`). The codec owns
the file layout under the index directory and the two directions, `unpack(group) -> files` and
`pack(files) -> group`. A group is the decompressed archive: for one file, the file; for several,
the JS5 multi-file layout.

| codec | used by | layout |
|---|---|---|
| `RawArchiveCodec` | indices whose archives hold one file and have no better codec | `<archive>.dat` |
| `RawFilesCodec` | indices with multi-file archives and no better codec | `<archive>/<file>.dat` (requires and always writes 1 chunk) |
| `VerbatimGroupCodec` | index 0 | `<archive>.grp` - the decompressed group verbatim, chunk table and all |
| `DefinitionJsonCodec<T>` | indices 3, 9, 13 and 16-22, and 14 of index 2's archives | one JSON file per definition, see below |
| `ConfigCodec` | index 2 | one sub-directory per config archive, named by `Config` (`inv`, `param`, `varp`...), each with its own codec |
| `MapCodec` | index 5 | one directory per map square, `<x>_<y>/terrain.json` and friends - see *maps* below |
| `SpriteCodec` | indices 8, 32 and 34 | `<archive>/sprite.json` plus an indexed colour PNG per frame, or `<archive>.jpg` verbatim - see *sprites* |
| `MaterialCodec` | index 26 | `<id>.json` per material of the index's one blob - see *materials* |
| `Cs2SourceCodec` | index 12 | the TypeScript layout `cs2 decompile-all` writes (`<category>/<name>.ts`), provided by the `cs2` module - see *clientscripts* below |

So the indices that hold definitions are editable JSON and the rest are bytes:

| index | dir | codec | | index | dir | codec |
|---|---|---|---|---|---|---|
| 2 | `config` | `config` (14 archives JSON, 31 raw) | | 19 | `config_obj` | `json-obj` |
| 3 | `interfaces` | `json-interface` | | 20 | `config_seq` | `json-seq` |
| 8 | `sprites` | `sprite-png` | | 21 | `config_spotanim` | `json-spotanim` |
| 9 | `textures` | `json-texture` | | 22 | `config_varbit` | `json-varbit` |
| 12 | `clientscripts` | `Cs2SourceCodec`, from the `cs2` module | | 26 | `materials` | `json-material` |
| 13 | `fontmetrics` | `json-fontmetrics` | | 32 | `loading_sprites` | `sprite-png` |
| 16 | `config_loc` | `json-loc` | | 34 | `loading_sprites_raw` | `sprite-png` |
| 17 | `config_enum` | `json-enum` | | 0 | `anims` | `verbatim-group` |
| 18 | `config_npc` | `json-npc` | | 5 | `maps` | `map-json` |
| | | | | everything else | | `raw-archive` / `raw-files` |

A codec is claimed per index in `SourceCodecs`, in three layers of increasing authority:

1. **Built ins**, which is everything in the table above except index 12.
2. **Providers.** A codec that cannot live in core declares itself instead: the client script codec
   is the `cs2` module's and core must not depend on `cs2`, so `cs2` ships a
   `SourceCodecProvider` named in `META-INF/services/world.gregs.voidps.cache.source.codec.SourceCodecProvider`
   and returning `{12: Cs2SourceCodec}`. `SourceCodecs` loads every provider on the classpath once,
   the first time any codec is looked up, so a module claims its index by being on the classpath and
   nothing has to be called. A provider's entry replaces the built in for its index.
3. **Explicit registrations.** `SourceCodecs.register(index, codec)` still overrides both, for a test
   or a tool that wants a particular codec.

`ConfigCodec` keeps the same kind of registry keyed by config archive id, and hands each sub-codec
its own directory (`RawFilesCodec.FLAT` and `DefinitionLayout.Flat`, which drop the redundant archive
directory). Config directories are named in the gameval vocabulary where the type has one -
`underlay`, `idk`, `overlay`, `inv`, `param`, `seq`, `varcstr`, `varp`, `varc`, `struct`, `skybox`,
`light`, `bas`, `cursor`, `mapscene`, `quest`, `mapelement`, `hitmark`, `varclan`, `varclansetting`,
`varplayer`, `varnpc`, `varclient`, `varbit`, `headbar`, `animflowcontrol`, `vargroup` - and
`archive_<id>` for the twenty-six archives in 727 that nothing has identified yet.

Besides the two directions, a codec answers which archive a tree relative path belongs to
(`archiveOf`/`owner`, so an incremental build can map a changed file back to what it has to
repack), `files(directory, archive)` - the inputs of one archive - and, when its files are not a
function of one archive, the two per-index hooks `unpackIndex` and `packIndex`. The whole interface
is in *The `SourceCodec` interface* below.

### Definition files

`DefinitionJsonCodec<T>` stores one definition per file, named by its id, and the id is the archive
and file packed the way that type's decoder packs them (`id = archive shl bits or file`):

| layout | used by | file | id |
|---|---|---|---|
| `Packed(bits)` | indices 16-22 | `config_obj/4151.json` | `4151 = 16 shl 8 or 55`, so archive 16 file 55 |
| `Nested(16)` | index 3 | `interfaces/1477/32.json` | `1477 shl 16 or 32` |
| `Flat` | index 2's typed archives | `config/struct/26.json` | the file id; the directory is the archive |

An obj, abridged - the real file lists every field the definition carries, defaults included, and
nothing that is not a field (60 keys for this one):

```json
{
  "id": 4151,
  "modelId": 5412,
  "name": "Abyssal whip",
  "spriteScale": 840,
  "spritePitch": 280,
  ...
  "floorOptions": [null, null, "Take", null, null, "Examine"],
  "options": [null, "Wield", null, null, "Drop"],
  "exchangeable": true,
  "noteId": 4152,
  "lendId": 13444,
  "params": {
    "2195": 24,
    "644": 1578,
    "641": 820,
    "14": 4,
    "1": 82
  },
  "stringId": "",
  "opcodeOrder": [1, 7, 8, 4, 5, 36, 13, 23, 25, 16, 12, 65, 2, 126, 97, 121, 249, 127, 129]
}
```

`DefinitionCodecs` is the one list of the twenty-two opcode keyed and interface types and what registers
them, and `ImageCodecs` is the same list for the two 2D definition types (index 9's textures and index 13's
font metrics) plus the three sprite indices and the materials blob - each of those has a subsection of its
own below. `DefinitionJson` is the mapping both use, and it derives these rules from the data class rather
than being told them:

- **Keys are the fields, in declaration order**, so `id` leads. A field a decoder gains appears in the
  tree the moment it exists, and a field whose type nothing here can spell is an error rather than a
  silently dropped value.
- **Defaults are kept, nulls are omitted.** Every non-null field is written whatever its value, so a
  reader never needs the decoder's defaults to understand a file; an absent key reads back as the fresh
  definition's default, which for every nullable field here is null.
- **Ids are numbers**, and names are never duplicated into the data - they come from the gameval
  catalogs at display time.
- **Arrays are inline while they fit** on a 100 column line, then one element per line. `ShortArray`,
  `ByteArray`, `IntArray` and `BooleanArray` are arrays of numbers; `Array<String?>` options are arrays
  with `null` holes; `Array<IntArray?>` is an array of arrays.
- **Maps keep the file's order.** Parameters (`Parameterized.params`), extras (`Extra.extras`) and an
  enum's entries are objects keyed by the numeric id with int or string values, in the order the file
  listed them, because that is the order the encoder writes them back in. A record class - a parameter
  record, a component setting, a script hook - is a nested object.
- **Two fields are layout fidelity, not data.** `opcodeOrder` and `shadowedPayloads` are what
  `OpcodeOrdered` records (see "Definition encoders"): the order the file listed its records in, and
  the bytes of any record a later record of the same opcode overwrote, as hex. They are written only
  when the definition carries them - a canonically packed file, or one authored by hand, has neither -
  and they are what makes the file that comes back the file that went in. Editing a field is safe with
  an `opcodeOrder` present; deleting a record from it is not, so leave it alone.

Decoding goes through the decoder's read path alone (`readLoop` for an opcode keyed type), never
`load`: `changeValues` derives values that are not in the file. Every decoder that takes a parameter
catalogue is left with its default `Parameters.EMPTY`, because a parameter keyed by *name* cannot be
turned back into the three byte key the file needs.

What that comes to over the whole 727 cache - **files** is how many JSON files the type has, **exact**
how many of them re-encode to the shipped bytes, **pristine** how many still need those bytes beside
them (`DefinitionJsonCodecTest`, and `cache status` on a real tree):

| type | directory | codec | files | exact | pristine |
|---|---|---|---|---|---|
| loc | `config_loc` | `json-loc` | 73,893 | 73,893 | 0 |
| enum | `config_enum` | `json-enum` | 6,048 | 6,048 | 0 |
| npc | `config_npc` | `json-npc` | 15,662 | 15,662 | 0 |
| obj | `config_obj` | `json-obj` | 25,355 | 25,355 | 0 |
| seq | `config_seq` | `json-seq` | 17,186 | 17,186 | 0 |
| spotanim | `config_spotanim` | `json-spotanim` | 3,252 | 3,252 | 0 |
| varbit | `config_varbit` | `json-varbit` | 11,713 | 11,713 | 0 |
| interface component | `interfaces` | `json-interface` | 65,239 | 65,239 | 0 |
| underlay | `config/underlay` | `json-underlay` | 171 | 171 | 0 |
| idk | `config/idk` | `json-idk` | 652 | 652 | 0 |
| overlay | `config/overlay` | `json-overlay` | 248 | 248 | 0 |
| inv | `config/inv` | `json-inv` | 674 | 674 | 0 |
| param | `config/param` | `json-param` | 2,583 | 2,583 | 0 |
| varp | `config/varp` | `json-varp` | 2,696 | 2,696 | 0 |
| varc | `config/varc` | `json-varc` | 1,992 | 1,992 | 0 |
| struct | `config/struct` | `json-struct` | 11,389 | 11,389 | 0 |
| bas | `config/bas` | `json-bas` | 2,574 | 2,574 | 0 |
| mapscene | `config/mapscene` | `json-mapscene` | 107 | 107 | 0 |
| quest | `config/quest` | `json-quest` | 204 | 204 | 0 |
| mapelement | `config/mapelement` | `json-mapelement` | 1,220 | 1,220 | 0 |
| hitmark | `config/hitmark` | `json-hitmark` | 48 | 48 | 0 |
| varclan | `config/varclan` | `json-varclan` | 2,845 | 2,845 | 0 |
| **total** | | | **245,751** | **245,751** | **0** |

Index 2's other 31 archives have no decoder and stay raw bytes (`raw-files-flat`).

### maps

Index 5 is not a definition index - a map square is a grid and a list of placements, not an opcode
keyed record - but it is stored the same way and for the same reason: a region is what a map editor
opens. `MapCodec` (`map-json`) gives each map square a directory and each of the five archives it
can be made of a file in it:

```
maps/50_50/terrain.json             m50_50   4 levels of tiles, then the environment block
maps/50_50/locs.json                l50_50   the placed locations (XTEA encrypted in the cache)
maps/50_50/npcs.json                n50_50   the npc spawns (XTEA encrypted)
maps/50_50/underwater_terrain.json  um50_50  one level of tiles
maps/50_50/underwater_locs.json     ul50_50  the underwater locations
maps/<archive>.container                     one of the eight archives nobody has the key for
```

Only the files a region has are written: all 2,407 surface squares have terrain and locations, 1,208
have the underwater pair and 38 have npc spawns.

**A tile is a token group and a row of 64 is a line.** 64 x 64 x 4 tiles cannot be an object each -
that is 16,384 objects a region - so a tile's records are transcribed as tokens and one localX of 64
ascending localY is one line, which is the order the file itself stores them in:

```json
{
  "region": "50_50",
  "levels": 4,
  "tiles": [
    [
      "u49,h30 u161,h30 u160,h30 o171/1/1,s4,u164,h30 o240/0/0,u164,h26 - u164,h28 ...",
      ...
    ]
  ],
  "effects": [
    {
      "type": "environment",
      "sunColour": 14536379,
      "sunAmbient": 334,
      "sunLight": 512,
      "sunBacklight": 92,
      "sunPosition": [-4, -16, 1],
      "fogColour": 9282754,
      "fogDepth": 600,
      "cubeTexture": [682, 683, 684, 685, 686, 687]
    },
    {
      "type": "lights",
      "lights": [
        {"packedLevel": 0, "x": 726, "z": 3265, "heightOffset": 220, "packedRadius": 1, "ranges": [258, 258, 258], "colour": 6032, "packedType": 6}
      ]
    },
    {"type": "hdr", "bloom": 32, "brightpass": 8, "whitePoint": 32}
### sprites

Index 8 is 12,071 sprite *groups*: a shared palette and one to 256 frames indexed into it, each frame
trimmed and given its position in a common canvas. It is stored as standard images - one **indexed
colour PNG per frame** - plus the little JSON a PNG cannot carry:

```
sprites/316/sprite.json     the palette, the canvas, and one entry per frame
sprites/316/0.png           frame 0's palette indices, indexed colour, tRNS on index 0
sprites/316/1.png
sprites/316/0.alpha.png     frame 0's alpha plane, only when its format byte says it has one
```

The ids stay the directory names. Index 8's reference table does carry archive name hashes, and they are
recorded in `index.json` like every other index's, but a name is a display concern and never a source of
hashes at pack time - so nothing here is laid out by name.

`sprite.json` for group 316, whole:

```json
{
  "id": 316,
  "width": 16,
  "height": 16,
  "palette": [
    0,
    7759444,
    3353893,
    5063219,
    0
  ],
  "frames": [
    {"minX": 0, "minY": 0, "width": 16, "height": 16, "flags": 1},
    {"minX": 0, "minY": 0, "width": 16, "height": 16, "flags": 1}
  ]
}
```

| token | record | opcode |
|---|---|---|
| `o<id>/<shape>/<rotation>` | overlay | `2 + shape * 4 + rotation`, then the id byte |
| `s<settings>` | tile settings | `49 + settings` |
| `u<underlay>` | underlay | `81 + underlay` |
| `h<height>` | explicit height | `1`, then the height byte |
| `-` | a tile with no records at all | just the `0` terminator |

**The token order is the record order**, which is what makes the file byte exact: the tokens are a
transcription and not a set of fields an encoder has to re-derive an order for. Every one of the 727
cache's 49,332,224 tiles lists its records in the order above - measured, 16 subsequences of it and
not one repeated opcode class - so that is the order an editor should write too. A height of `1` is
how the cache spells "deliberately flat" (the client folds an explicit `1` to `0`) and no `h` token
means "let the client derive the height". `effects` is the environment block that follows the tiles
of a surface square: the sun, fog and cube texture record, point lights, the HDR triple, the skybox
and the per-level light intensity grid, each as the file holds it - an absent field is absent rather
than defaulted, because presence is what opcode 0's flag byte is derived from.

Locations and npc spawns are one object per line, in file order:

```json
{
  "region": "50_50",
  "locs": [
    {"id": 1276, "x": 12, "y": 40, "level": 0, "shape": 10, "rotation": 2}
  ]
}
```

Order is fidelity here as well: consecutive objects sharing an id are one group in the file, group
ids ascend and positions within a group never go backwards, and `MapObjectEncoder` writes a list
that satisfies that verbatim (regrouping one that does not, so an editor can still append). 48
location archives end in zero padding past their last record - the packer padded the plaintext
before encrypting it - and a `"padding": <n>` above `locs` is those bytes; `"trailing": "<hex>"`
covers the case where they are not zeroes, which nothing in 727 is.

**Parity, measured over the whole 727 cache** (`MapCodecParityTest`): every archive decoded, written
as JSON, read back, re-encoded and compared against the shipped bytes.

| shape | file | archives | byte identical | pristine |
|---|---|---|---|---|
| terrain | `<x>_<y>/terrain.json` | 2,407 | 2,407 | 0 |
| locations | `<x>_<y>/locs.json` | 2,399 | 2,399 | 0 |
| npc spawns | `<x>_<y>/npcs.json` | 38 | 38 | 0 |
| underwater terrain | `<x>_<y>/underwater_terrain.json` | 1,208 | 1,208 | 0 |
| underwater locations | `<x>_<y>/underwater_locs.json` | 1,208 | 1,208 | 0 |
| **total** | | **7,260** | **7,260** | **0** |

The eight archives whose XTEA key was never published are the other eight of index 5's 7,268 and are
`<archive>.container` as always ("Opaque archives"). The same test unpacks index 5 into a tree of its
own and verifies a fresh pack of it against the packed cache: **7,268 of 7,268 containers identical,
the reference table identical, nothing pristine**; it then edits one tile of `50_50/terrain.json`,
rebuilds, and gets exactly one repacked archive carrying the edit. Index 5 costs 654 MB of tree and
about 14 s of an unpack, which is the price of 39 million tiles being readable text.

**Reading an encrypted map archive.** `Container` has always deciphered exactly the container - offset
5 to the end of the payload, the two byte version trailer outside it - and the unpacker goes through
it, which is why the tree was never affected. `DecompressionContext.decompress`, the read path
`FileCache.data(index, archive, file, xtea)` uses, deciphered to the end of the *array* instead: two
bytes too far, which adds a ninth XTEA block whenever the trailer completes one and corrupts the last
eight bytes of the payload. It now uses the same bound as `Container` and the client's own
`decryptXtea(keys, containerLength)`, and `MapEncoderRoundTripTest` covers all 2,399 keyed location
archives instead of the 375 stored in the clear. (That test opens a `FileCache` rather than
`Cache.get()`, because the default in-memory cache decrypts at load time with the keys it was built
with - none - and answers null for every encrypted map archive.)

**Where the archive id comes from.** Index 5 is the one index whose archive names are a formula
rather than a catalogue (`MapSquare`, `docs/cache-archive-names.md`) and its reference table carries
name hashes and no names, so a region directory cannot say which archive id it is. `MapCodec` answers
`archiveOf` with the tree to look in - the binding is `index.json`'s name hashes, the way index 12's
is a script header - and `unpack`/`pack` take the hash from the `ArchiveMetadata` they are handed.
`MapSquare.of(hash)` is the way back, by hashing all 5 x 256 x 256 possible names once; no two of
them collide, which is checked rather than assumed.
- `width` and `height` are the group canvas every frame is padded into (the client's `biggestWidth` and
  `biggestHeight`); a frame's own `minX`, `minY`, `width`, `height` are its trimmed placement inside it.
- `palette` is the file's own entries, entry 0 first. It is **not** the palette the client ends up with:
  0 is the client's transparent colour, so it rewrites any entry that really is black to 1, and 5,264 of
  the 12,071 groups have at least one such entry - 316's entry 4 above is one. The raw entry is what
  writes back, and `IndexedSprite.palette` is the normalised copy the renderer uses.
- `flags` is the frame's format byte: bit 0 stores the pixels column major rather than row major, bit 1
  says an alpha plane follows them. Both are used; 9,014 frames are 0, 4,983 are 1, 3,363 are 2 and 1,730
  are 3.
- `paletteZeroEntry`, written only when true, says the file spells out an explicit `000000` for entry 0
  before entry 1 - three bytes the client seeks straight past. Only 29 groups carry it, ids 12042 to
  12070, which are the newest in the cache and were packed by a different tool than the rest.

**What lives where.** The PNG owns the pixels and nothing else. The palette is written into every frame's
`PLTE` in its original order - which is what makes a frame's pixel *indices* survive the round trip, and
what makes the image display correctly in any viewer - but `sprite.json` is what is read back, for two
reasons: 2,498 frames are zero sized (empty glyphs in the font sheets) and have no PNG at all, some groups
are nothing but, so a group's palette can have nowhere to live; and one palette in one place cannot
disagree with itself across 256 frames. So recolouring a sprite is an edit to `sprite.json` and repainting
one is an edit to the PNG.

**Alpha.** The format byte decides the shape, and both round trip:

| frame | PNG | alpha |
|---|---|---|
| `flags and 2 == 0` | indexed colour with a `tRNS` chunk making index 0 transparent | none |
| `flags and 2 != 0` | indexed colour, no `tRNS` - the plane carries the transparency | `<frame>.alpha.png`, 8 bit greyscale |

A plane whose every byte is 0xff is dropped by the client (and by `IndexedSprite.alpha`) because it
carries no transparency - 302 frames are like that - so `flags` is the only thing that says the plane was
there, and the encoder writes it back as 0xff.

Parity is measured on the *cache bytes*, never on the PNG: `ImageCodecTest` unpacks every group to real
files, reads them back through `javax.imageio` and re-encodes, and demands the archive come out identical.
**All 12,071 groups do, and none needs a pristine copy.**

### loading sprites

Indices 32 and 34 are the loading screen and the fonts the client draws before it has a cache: 34
archives each, the same ids in both. Which one the client reads is decided at startup -
`Resource.USING_JAGEX_IMAGE_FORMAT` picks index 34 when `Toolkit.createImage` cannot be used and index 32
otherwise - so the two are the same pictures in two formats.

They get `SpriteCodec`, the same codec as index 8, because most of both indices *is* the palette sprite
format:

| index | archives | what they are |
|---|---|---|
| 34 `loading_sprites_raw` | 34 | all palette sprite groups - this is the "Jagex image format" path |
| 32 `loading_sprites` | 21 | JPEG containers, which the client hands to `Toolkit.createImage` |
| 32 `loading_sprites` | 5 | palette sprite groups: 494, 495, 496, 3793 and 3794, the pre-cache fonts |

A JPEG archive is not re-encoded and never could be: it is stored as `loading_sprites/3762.jpg`, byte for
byte, with its real extension, and packed straight back out. The codec sniffs the `ff d8` magic rather
than being told which archives are which, so a tree of either index reads the same way. The sprite
archives are laid out exactly as index 8's - `<id>/sprite.json` and a PNG per frame - and the five font
groups are 256 frames each, most of them zero sized.

**All 68 archives of the two indices round trip byte for byte**, and none needs a pristine copy.

### textures

Index 9 is 2,591 textures, and a texture is not a raster: it is a small DAG of procedural operations that
the client evaluates at 64x64 or 128x128 to produce one. So the file is the graph, and `textures/<id>.json`
is the graph as JSON - `json-texture`, a `DefinitionJsonCodec` over `TextureDefinition`.

`textures/100.json`, whole - a nested material tiled twice each way and run through a curve:

```json
{
  "id": 100,
  "operations": [
    {"type": 0, "imageCacheCapacity": 1, "opcodes": [], "children": []},
    {"type": 36, "imageCacheCapacity": 1, "opcodes": [{"opcode": 0, "value": 94}], "children": []},
    {
      "type": 20,
      "imageCacheCapacity": 1,
      "opcodes": [{"opcode": 0, "value": 2}, {"opcode": 1, "value": 2}],
      "children": [1]
    },
    {
      "type": 8,
      "imageCacheCapacity": 1,
      "opcodes": [{"opcode": 0, "value": 0, "values": [0, 1, 4096, 4096]}],
      "children": [2]
    },
    {"type": 0, "imageCacheCapacity": 1, "opcodes": [{"opcode": 0, "value": 0}], "children": []}
  ],
  "opaque": 3,
  "opacity": 0,
  "hdr": 4,
  "trailer": "00000101000000220000"
}
```

- `operations` is the graph in file order. `children` are indices into that same list, which is what makes
  it a DAG; `type` is the operation (`36` is a nested material, `20` a tile, `8` a curve, `0` a
  monochrome fill), and how many children a type takes is fixed per type.
- `opcodes` are the operation's configuration records, in the order the file listed them. The payload is
  kept **raw** - the unsigned byte, short or 3 byte colour the file holds, before the scaling the client
  does on the way into its own fields - because only the raw form writes back to the same bytes. Four
  opcodes are variable length and use `values` (a curve's `x, y` pairs, a custom gradient's
  `position, r, g, b` stops, perlin noise's per-step multipliers) or `shapes` (the rasterizer's lines,
  beziers, rectangles and ellipses).
- `opaque`, `opacity` and `hdr` name the three operations the client reads the colour, the alpha and the
  intensity out of.
- The file also leads every operation with its own index in the list. It is derivable, so it is not
  stored; the decoder asserts it instead, on all 37,234 operations in the cache.

`TextureOpFormat` is the table this rests on - the child count of each of the 40 operation types and the
payload of each of their opcodes, transcribed from the client's `TextureOpX.decodeOpcode`. Two facts about
it are load bearing and were established by reading all 2,591 files rather than by reading the client:

- **An opcode a type does not handle carries nothing.** The client's `when` falls through, so the byte is
  a no-op and the next byte is the next opcode. Twelve textures give the waveform operation (type 12)
  opcodes 2, 4, 5 and 6, which it has never handled; reading them any other way runs off the end.
- **Perlin noise's opcode 2 is length-dependent on opcode 1.** A negative amplitude is followed by one
  short per noise step, and the step count is whatever opcode 1 last set - defaulting to 4, because the
  file is free to give opcode 2 first and most do.

`trailer` is the honest residual, and the only part of index 9 that is stored as opaque bytes. 2,562 of
the 2,591 textures carry **ten more bytes** after the three output indices which this revision's client
never reads: it stops at the third index. The other 29 - ids 2562 to 2590, the newest in the cache, and
the same 29 whose sprites carry an explicit palette entry 0 - carry no trailer at all, so it is not even a
fixed part of the format. Its bytes are not random (byte 7 of the ten is 0x22 in 2,554 of them and bytes
2 and 3 are 0/1 flags), but nothing in the 727 client gives them a meaning, so they are kept verbatim as
hex rather than guessed at and given wrong names.

**All 2,591 textures re-encode to the shipped bytes**, and none needs a pristine copy.

### materials

Index 26 is one archive holding one blob: a count, a presence byte per material, and then one *column*
per field carrying that field for every material at once. A material therefore has no bytes of its own,
which is why it does not get a `DefinitionJsonCodec` - `MaterialCodec` decodes the whole blob, writes
`materials/<id>.json` per material, and rebuilds the blob from all of them together.

```json
{
  "id": 0,
  "defined": true,
  "detailsOnly": false,
  "isSmall": false,
  "skipTriangles": false,
  "brightness": 0,
  "alpha": -1,
  "effectId": 0,
  "effectParam1": 0,
  "colourHsl": 6547,
  "textureSpeedU": 0,
  "textureSpeedV": 0,
  "unusedBoolean": false,
  "isBrickTile": false,
  "mipmapping": 2,
  "repeatS": true,
  "repeatT": true,
  "hdr": false,
  "combineMode": 0,
  "effectParam2": 0,
  "effectCombiner": 0
}
```

- The ids are dense - `0` until the count - so the number of files *is* the count, and a material the
  presence byte says is absent is still a file, with `"defined": false`. All 2,591 of the 727 cache's
  materials are defined, and they are one to one with index 9's textures.
- The names are the client's (`MaterialTypeList`), not the obfuscated ones the decoder used to carry:
  `aBoolean1204` is `isSmall`, `aByte1225` is `alpha`, `type` is `effectId`, `anInt1226` is
  `effectCombiner`, and so on. `unusedBoolean` is the one field the client keeps (`aBool2087`) and never
  reads.
- Every boolean column is 0 or 1 in all 2,591 materials, which is what makes reading them as booleans
  lossless - the client reads them as `byte == 1`, so any other value would have to be stored raw.

The pristine sidecar, if it were ever needed, guards the whole blob rather than a file, because one
material cannot be rebuilt on its own. **The blob re-encodes to the shipped bytes**, so there is none.

### fontmetrics

Index 13 is 27 fonts' metrics - the advance of each of the 256 cp1252 glyphs, the line spacing and the
padding - as `fontmetrics/<id>.json`, `json-fontmetrics`. The glyph *bitmaps* are the sprite group of the
same id in index 8, so a font is one file in each index.

```json
{
  "id": 494,
  "glyphWidths": [
    0,
    0,
    ...
  ],
  "verticalSpacing": 10,
  "topPadding": 10,
  "bottomPadding": 2,
  "version": 0,
  "variableWidth": false,
  "unusedByte1": 9,
  "unusedByte2": 6,
  "stringId": ""
}
```

Every one of the 27 fonts is the fixed width form and 263 bytes long, so the file is `version`,
`variableWidth`, the 256 advances, the spacing, two bytes and the two paddings, in that order and with no
choices in it. Three things were being lost before:

- `unusedByte1` and `unusedByte2` are the two bytes the client reads and discards between the spacing and
  the padding. Every one of the 27 fonts carries a nonzero pair, so they are not padding.
- `version` is the format byte the client refuses to read anything but 0 of.
- `kerningAdjustments` is the one field the tree does not write: it is a 65,536 entry table the client
  *computes* from the variable width form's glyph offset profiles, so it is derived rather than read, and
  writing it would be a megabyte of numbers the encoder ignores. It is listed in `DefinitionJson.DERIVED`
  beside the clan var descriptor for exactly that reason.

The variable width form - two coverage tables and two delta coded offset profiles per glyph, from which
the client derives both the kerning table and the spacing - is decoded and encoded for completeness, but
**no 727 font uses it**, so nothing in the cache exercises that path. Fixing it did turn up a decoder bug
worth naming: the second offset profile is `widths[glyph]` bytes long in the client, as the first one is,
and the decoder was reading it with the *heights* as its lengths, which would desync the rest of the file
for any font that had them differ.

**All 27 fonts re-encode to the shipped bytes**, and none needs a pristine copy.

### What the 2D indices cost

`ImageCodecTest` is the measurement - every archive of all six indices, unpacked into real files and
packed straight back - and the bar is the same as every other typed codec's: byte identical, zero
pristine.

| index | directory | codec | archives | byte identical | pristine | files written |
|---|---|---|---|---|---|---|
| 8 | `sprites` | `sprite-png` | 12,071 | 12,071 | 0 | 33,756 |
| 9 | `textures` | `json-texture` | 2,591 | 2,591 | 0 | 2,591 |
| 13 | `fontmetrics` | `json-fontmetrics` | 27 | 27 | 0 | 27 |
| 26 | `materials` | `json-material` | 1 | 1 | 0 | 2,591 |
| 32 | `loading_sprites` | `sprite-png` | 34 | 34 | 0 | 998 |
| 34 | `loading_sprites_raw` | `sprite-png` | 34 | 34 | 0 | 1,027 |
| **total** | | | **14,758** | **14,758** | **0** | **40,990** |

**14,758 archives, all byte identical, no residuals and no pristine copies anywhere.** The files are 63 MB
in total, and the codecs' own cost over the whole cache, single threaded, is 9.8 s - 8.6 s of it the
sprites, which are 33,756 PNG encodes and 33,756 decodes back.

Against the whole tree the change *shrinks* it: unpacking the 727 cache with these codecs writes 423,315
files and 1,316 MB where the raw codecs wrote 397,083 files and 1,366 MB, because a PNG's deflate is a
better fit for a palette raster than the raw bytes were. Timings on this machine were 23 s to unpack (12 s
of which is I/O), 17 s to pack and 17 s to verify at full parity, but they vary by a factor of two with
machine load and disk cache state and should be read as an order of magnitude rather than a benchmark.


### The pristine fallback

A codec that cannot reproduce a group keeps the original bytes next to the editable file as
`<name>.pristine` and records the editable file's SHA-256 under `pristine` in `index.json`. `pack`
emits the pristine bytes while the editable file still hashes to that value and compiles the
editable file otherwise. Assembling that is the codec's own job, since only it knows its layout:
it returns the sidecar as one more file from `unpack`, and the hashes in `UnpackedArchive.pristine`.
The raw codecs keep the bytes and are exact by construction, so none of this runs for them.
`cache status` reports the pristine count per type so encoder gaps are visible; the goal is zero,
and the count can only shrink as encoders improve or files are edited. A *stale* sidecar - the
guarded file no longer hashes to the recorded value - is `status`'s way of showing an edit that
has not been packed yet.

`SourceCodec.exact(archive)` says whether packing what the codec wrote reproduces the group, and
`unpack` packs the archive again to count the ones that do not. A codec that installs its own
fallback while it unpacks - `Cs2SourceCodec` does, because it has to compile each script anyway to
find out - is `exact` and reports its count through `UnpackedArchive.pristine` instead;
`UnpackReport.inexact` only ever sees a codec that leaves the decision to the framework, so
`cache unpack`'s pristine line reads 0 for such an index and `cache status` is the number to read.

This is what lets a normalising compiler (the CS2 decompiler drops dead code by design, so 1,066
of the 6,567 clientscripts recompile to different but equivalent bytes) and a not-yet-exact
encoder coexist with the parity guarantee.

### clientscripts

Index 12 is not stored as bytecode. `clientscripts/` **is** a CS2 editor project - exactly the
folder `cs2 decompile-all` writes (`docs/cs2-editor.md`) - so a clientscript is edited as
TypeScript and the server compiles it back when it rebuilds the cache:

```
clientscripts/
  index.json                       the reference table, as for every index
  cs2.d.ts                         every opcode's signature, the argument kinds the corpus taught us,
                                   and StringParam - which params a lookup returns a string for
  gamevals.d.ts                    the id constants (Obj.abyssal_whip, Component.wornitems.head)
  vars.d.ts                        every variable any script touches, bound to its id
  tsconfig.json                    without it an editor opens each script as its own program
  dead-code.md                     what the decompiler found that cannot affect what a script does
  proc/quicksort.ts                one file per script, at its gameval name's path
  clientscript/<name>.ts
  cs2/cs2_1620.ts                  a script with no proven name, under the catalog's placeholder prefix
  proc/<name>.ts.pristine          the shipped bytes of a script the compiler normalises
```

A script's identity is its gameval name, not a number: the path and the `// [proc,quicksort]`
header both spell it out and `gamevals/clientscript.json` turns it into the archive id. Renaming a
script therefore moves its file - `cs2 sync-names` writes an editor's rename back to the catalog
and relocates the file - and nothing else may move it.

The five project files are a property of the *corpus*, not of any one archive: `vars.d.ts` only
knows a variable exists because some script uses it, and `cs2.d.ts` carries argument kinds and param
types learned from all 6,567 scripts at once. `SourceCodec.unpackIndex` is the hook they come out
of - it runs once after the index's archives and claims what it returns, so nothing under
`clientscripts/` is carried out of an archive that has no business producing it - and `owner`
answers `Owner.Index` for all five, so editing one repacks nothing and is handed to `packIndex`
instead (see *The `SourceCodec` interface*).

**1,066 of the 6,567 scripts are pristine.** The decompiler normalises by design - it drops
unreachable code, folds initialisers into declarations, lays branches out canonically - so those
scripts compile back to equivalent but different bytes and their originals are kept in
`<name>.ts.pristine`. The other 5,501 compile back to the cache's own bytes exactly. Either way
the tree reproduces index 12 byte for byte until a script is actually edited.

**Editing a script.** Edit the `.ts` and restart the server (or run `tools cache build`). The
edited file no longer hashes to the guard `index.json` recorded, so the pack compiles it instead of
emitting the shipped bytes; a compile error names the file and the diagnostic as
`file:line:col: what went wrong` and fails the build rather than serving stale bytecode. Only the
archives whose files changed are recompiled, so an edit to one script costs one compile. Renaming
an identifier in `vars.d.ts` or `gamevals.d.ts` is a rename, not an edit: run `cs2 sync-names` to
write it back into the catalogs, which is where the names live.

**Where the compiler's context comes from: the tree.** Compiling one script needs facts about the
*others* - a call site discards exactly what its callee leaves on the stacks, and a `*_PARAM`
opcode pushes an int or a string according to the param's declared type - and none of it is in the
file being compiled. Neither is any of it in the *bytecode*: a script's argument and return order
is only recoverable from what its callers push, which is why decompiling needs the whole corpus
analysed. But a decompiled folder **states every one of them**:

| what a compile has to know | where `clientscripts/` says it |
|---|---|
| what script *n* returns, in push order | its own `function ...(): [number, string]` |
| how many arguments it takes, on which stacks | its own parameter list |
| whether param *n* yields a string | `cs2.d.ts`'s `StringParam` |
| which id a renamed identifier means | `vars.d.ts`, `gamevals.d.ts`, the script headers |

`Cs2SourceContext.read(directory)` is that reading and `Cs2Compiler.fromSources(directory)` the
compiler over it. It lexes each script's declaration line and nothing else - parsing a body would
be circular, since parsing is what needs the answers - so the whole corpus's signatures cost about
0.3 s against roughly 7 s for the analysis. It is the same `Cs2Context` the analyzer implements, so
nothing downstream can tell which one it was handed, and the parity proof is that it produces the
cache's own bytes: `Cs2SourceCodecTest` packs all 6,567 archives with the codec's cache seam wired
to throw, and gets 5,501 exact compiles and 1,066 sidecars back.

Only the param types needed writing down, because a param's type belongs to its param definition
rather than to any script and a lookup's bytecode does not say which stack it used. They go into
`cs2.d.ts` - the index's own file, so index 12 stays self-contained - as a type alias listing the
ids, 131 of the 727 cache's 2,583 params:

```ts
type StringParam =
    | 528
    | 529;
```

A folder written before that declaration existed carries no such line, which is a different answer
from an empty one: nothing fails - no id yields a string, which is what the compiler assumes
without an answer, and no script in the 727 corpus compiles differently either way - but a
hand-edited script reading a string param would be compiled against the wrong stack.
`tools cs2 declarations <dir>` or a re-unpack writes it.

**What a pack of index 12 costs.** The signature scan is about 0.3 s, done once per build and
shared; after it a compile is well under a millisecond, and all 6,567 scripts compile in 1.3 s. A
build that changes no clientscript never constructs the compiler and pays none of it. `packIndex`
is where the context is set up, and it also *drops* the previous one, so a second build in the same
process reads a tree that has moved rather than a stale scan of it.

**The one direction that still needs a cache is unpacking**, because decompiling is where the bytes
are all there is. `SourceCodec` hands a codec one archive at a time and no cache, so
`Cs2SourceCodec.cache` is the seam; it defaults to `Cache.get()`, which is right for every tool and
every test, since unpacking a cache means having one.

**On a server.** With `CACHE_SOURCE_PATH` set, `Cache.get()` runs `CacheSource.ensureBuilt()` before
it opens anything, so the first thing that asks for the cache triggers the build - index 12
included, from the tree alone, with nothing else to read. Nothing has to be handed in and nothing
has to be configured for it:

```
CACHE_SOURCE_PATH=/srv/darkan/cache-source
CACHE_BUILD_PATH=/srv/darkan/data/cache
```

`DefinitionJsonCodec` checks itself file by file rather than archive by archive, because a definition
*is* a file: on the way out it decodes the shipped bytes, writes the JSON, reads that JSON straight
back into a fresh definition and re-encodes it, and only a file whose bytes do not come out identical
gets a `<id>.json.pristine` beside it. So the guarded unit is one definition and not the 256 that
share its archive. It still reports `exact(archive) = false`, so the unpacker packs the whole group
back and compares that too - an independent second proof rather than the only one.

Measured over the whole 727 cache, **all 245,751 definition files of all 22 types survive the round
trip and not one needs a pristine copy**; `DefinitionJsonCodecTest` is the measurement and
`docs/cache-source.md`'s "Parity per type" table the per type breakdown.

## The packed build

`Builder.build(tree, outputDir)` produces a normal packed cache in `outputDir`
(`world.gregs.voidps.cache.source`; `Packer.pack` is the from-scratch half, `Verifier.verify` the
parity check, `Unpacker.unpack` the other direction):

1. Read `outputDir/manifest.json` if present: the tree path, format version, and for every input
   file its size, mtime and content hash, and for every archive the container CRC it produced and
   a digest of its `index.json` line. A manifest that is missing, of an older format, or built
   from a different tree means a full pack.
2. Walk the tree in parallel, stat every file. Size and mtime unchanged means unchanged; anything
   else is hashed and compared. Removed and added files are noticed. The walk over ~250k files
   takes about a second.
3. Map changed paths to `(index, archive)` through the index's codec, and diff `index.json`
   per archive for metadata-only changes (a version bump, a key change).
4. For each affected archive, in parallel: pack the group, compress with the recorded
   compression, encrypt, write the container into the store (`FileStore`, reusing the old
   sector chain when the new container fits, appending otherwise), and update the archive's
   entry in the in-memory reference table.
5. For each affected index: encode the reference table, write it to index 255. Write the manifest
   last. The RSA-signed version table is not written anywhere: it is derived from the reference
   table containers and signed every time the cache is opened, so a rebuilt table is enough.

`index.json` is diffed per archive rather than as a file: the manifest keeps a digest of each
archive's metadata line, so a version bump or a key change repacks that one archive and a
reformatted file repacks nothing.

No changes: step 2 finds nothing and the build returns in the time it takes to stat the tree.
A first build from an empty directory packs everything, compressing in parallel.

## Read-write caches

`FileCache` can be opened writable (`FileCache.load(path, writable = true)`); `Cache.write`
then repacks the group, recompresses with the archive's existing compression, bumps the version,
rewrites the reference table and the version table. This is the API editors use to save into a
packed cache directly. Both paths share `FileStore`, `ReferenceTable` and `Container`.

The map editor's environment write-back (`tools/.../map/environment/MapEnvironmentWriter.kt`) goes
through it, and the sector writer it used to carry (`CacheArchiveWriter`, plus a second reference
table parser) is gone. Its guard rails are unchanged - plan and apply are separate, the archive is
decoded and re-encoded *unchanged* first and the write refuses unless that reproduces the original
bytes, the container and the index 5 reference table are backed up before the first write of a
session, a plan is refused when the container it was computed from has moved on disk, and after the
write the archive is read straight back off the store and its reference table entry re-decoded from
disk and checked. What changed is who writes: the container is recompressed with the archive's own
compression rather than re-emitted uncompressed, and the reference table and the signed version
table are core's to update. Two consequences worth naming:

- Writing the archive without the reference table is no longer possible - `Cache.write` always
  brings the table (and the version table derived from it) along - so `tools env write`'s
  `--no-reference-table` flag is gone.
- The table's own `revision` is left where it is. The tools writer used to bump it; core does not,
  and does not need to, because the version table the client checks the signature of is derived
  from the reference table containers as they actually are.

## Configuration

| variable | default | meaning |
|---|---|---|
| `CACHE_SOURCE_PATH` | unset | a source tree. When set, the server builds it into `CACHE_BUILD_PATH` on startup and serves that |
| `CACHE_BUILD_PATH` | `./data/cache` | the packed build output (gitignored) |
| `CACHE_PATH` | `../cache/` | a prepacked cache, used when `CACHE_SOURCE_PATH` is unset |
| `GAMEVAL_PATH` | `<CACHE_SOURCE_PATH>/gamevals` when set, else `./cache-source/gamevals` | the catalogs (the only copy) |

The lobby and the worlds use the same switch; a world that needs its own cache in future points
`CACHE_SOURCE_PATH` at its own tree. The switch lives in `org.darkan.core.cache.CacheSource`:
`Cache.get()` calls `CacheSource.ensureBuilt()` before it opens anything, so with a tree configured
the first thing any server does is build (or incrementally refresh) `CACHE_BUILD_PATH`, and
`EnvVars.cachePath` then names that directory. `data/cache/manifest.json` records what the build was
made from; deleting the directory forces a full pack. The map XTEA keys the world sends to clients
still come from `data/map/xteaKeys.json`; the tree records the same keys per archive in
`maps/index.json`, and the unpacker reads the file, not the tree, when it decrypts.

Rolling this out to the servers and CI is in `docs/deploy.md`, "The cache source tree".

## Tools

```
tools cache unpack <tree> [packedDir]     unpack a packed cache (default CACHE_PATH) into a tree
tools cache pack <tree> <outDir>          full pack
tools cache build <tree> <outDir>         incremental pack (what the server runs on startup)
tools cache verify <tree> <packedDir>     container-level parity report against a packed cache
tools cache status <tree>                 codec per index, pristine counts, stale sidecars
```

`unpack` is the only one that opens a packed cache, and it opens the directory it is given rather
than the one the rest of the tools share, so `Main` dispatches `cache` before it loads anything.
Each command prints one line per index as it goes and then its totals:

- **unpack** writes `cache.json`, copies the gameval catalogs in from `GAMEVAL_PATH`, loads the map
  keys (`Unpacker.mapKeys()`, i.e. `data/map/xteaKeys.json`) so the encrypted map archives are
  stored decrypted, and reports archives, files, bytes, how many files it actually had to write,
  how many stale ones it deleted, and the opaque, pristine and missing counts.
- **pack** replaces whatever cache is in `<outDir>`; **build** patches it and falls back to a full
  pack when there is nothing usable to patch, saying which it did and why.
- **verify** prints, per index, how many containers came out identical, different, missing or extra,
  whether the reference table matched, and the first few differing archives with what differs about
  them; then the totals and whether the derived version table matches. **It exits 1 on any
  difference**, so it works as a test.
- **status** is read-only: per index its codec, archive count, file count, pristine count, opaque
  count and how many `.pristine` sidecars are *stale* - the guarded file no longer hashes to the
  value `index.json` recorded, so the next pack will compile the edit rather than emit the original.
  It also says whether the tree has its `gamevals` directory and how many catalogs are in it.

Against the 727 cache on one 12-core machine, on the tree at `cache-source/`: unpack 12.8 s when the
tree is already right and 44 s when every file has to be written (140,692 archives, 397,041 files,
1.9 GB, 8 opaque, 1,066 pristine - all of them clientscripts), pack 22 s, verify 20 s at full parity,
a no-op build 3.2 s and a one-archive build 7.4 s. Index 5 is 654 MB and 14 s of that. Unpacking
costs the time it does because every one of the 245,751 definition files and 7,260 map files is
decoded, written as JSON, read back and re-encoded on the way out to prove it survives the trip; the
tree is 290 MB bigger for the definitions and 650 MB for the maps, for the same reason.

With the 2D indices as images and JSON the tree is **423,315 files and 1,316 MB** - 26,232 more files
than the raw codecs wrote and 50 MB *smaller*, because a PNG's deflate beats a raw palette raster. A full
unpack of the whole cache measured 23 s, a pack 17 s and a verify 17 s at full parity, but those numbers
move by a factor of two with machine load and disk cache state; the codecs' own cost, measured on its own
and single threaded, is 9.8 s for all 14,758 archives - see *What the 2D indices cost*.

With index 12 as TypeScript the same runs cost about 8 s more to unpack (the CS2 corpus analysis
and a round-trip compile of every script) and 1.6 s more to pack (the folder's signature scan, then
5,501 compiles). Measured on the tree at `cache-source/`, building it into an empty directory and
opening the result takes **16 s** end to end; a second run with nothing changed is **2.7 s**, of
which the build itself is a stat walk; editing one clientscript and rebuilding is **4.4 s** and
repacks exactly that one archive. `cache verify` of the built cache against the tree is 12 s at
full parity: 140,692 containers, 37 reference tables and the derived version table, all identical.

## The `SourceCodec` interface

A codec is asked a dozen questions. Only the five in bold have to be answered; the rest have
defaults that are right for any codec whose files are a function of one archive, which is every
codec in core.

| member | when | what it is for |
|---|---|---|
| **`id`** | reports | the short name `cache status` and the build reports print, like `raw-archive` |
| **`unpack(directory, archive)`** | per archive | the archive as files; paths relative to the index directory, and the caller does the writing |
| **`pack(directory, archive, metadata)`** | per archive | the group rebuilt from those files, plus the file ids the reference table has to list |
| **`files(directory, archive)`** | per archive | every file that makes up the archive, sidecars included |
| `pristineFiles(directory, archive)` | per archive | the archive's `.pristine` sidecars; by default the ones `files` returns whose name says so |
| `exact(archive)` | per archive | whether packing what `unpack` wrote reproduces the group; false makes the unpacker check, archive by archive |
| `fileIdsFromMetadata(archive)` | per archive | whether the file ids have to be carried in `index.json` because the layout cannot spell them out |
| `unpackIndex(directory, index, archives)` | once per index | files that belong to the index rather than to any archive - *and* the claim that keeps them |
| `packIndex(directory, index, archives, changedIndexFiles)` | once per index | shared state to set up before the archives are packed, and the index level files that moved |
| **`archiveOf(path)`** | per changed path | which archive a tree relative path belongs to, or null |
| `archiveOf(directory, path)` | per changed path | the same with the tree to look in, for a binding that lives inside the file |
| `owner(directory, path)` | per changed path | archive, index or neither - the three-valued form the build asks |

### The per-index hooks

`Cs2SourceCodec` is the first codec whose files are not a function of one archive: index 12's five
project files (`cs2.d.ts`, `gamevals.d.ts`, `vars.d.ts`, `tsconfig.json`, `dead-code.md`) are a
property of the *corpus* - `vars.d.ts` only knows a variable exists because some script uses it -
and compiling any one script needs the whole corpus analysed first. Both halves are hooks now.

- **`unpackIndex(directory, index, archives)`** runs once, after every archive of the index has
  been unpacked, and returns files the same way `unpack` does. Returning a file here is also how a
  codec *claims* it: `Unpacker.prune` deletes everything under the index directory that neither an
  archive nor this call accounted for, so a corpus-wide file no longer has to be smuggled out of
  whichever archive happened to be unpacked first. It is called after the loop rather than before
  it because that is when a corpus-wide answer exists; `archives` is what was actually unpacked.
- **`packIndex(directory, index, archives, changedIndexFiles)`** runs once, before the archives of
  an index are packed - by the full pack, by `verify`, and by an incremental build - and is where a
  codec builds the state its per-archive `pack` will share. It is called only when there is
  something to do, so a build that touches no clientscript still never constructs the compiler.
  `archives` is every archive for a full pack and only the changed ones for a build.

**Index level files are tracked like any other input.** The manifest hashes every file in the tree
regardless of who owns it, so a project file that changed is noticed the same way a script is. What
differs is what that change *does*: it repacks no archive - nothing in the cache is a function of
it - and is instead handed to `packIndex` as `changedIndexFiles`, which lets a codec that does care
react and lets one that does not ignore it. The build then records the file's new hash, so the next
build is a no-op again. That is the whole rule; there is no "changed index file forces a repack of
the index" case, because for index 12 there is nothing to repack - renaming a variable in
`vars.d.ts` is a rename, not an edit, and the ids the bytecode carries are unmoved.

### Who owns a path

`owner(directory, path)` is the three-valued question an incremental build asks:

| answer | meaning | what the build does |
|---|---|---|
| `Owner.Archive(id)` | one of the index's archives owns it | repack that archive |
| `Owner.Index` | the codec's, but the index's rather than any archive's | hand it to `packIndex`; repack nothing |
| `Owner.None` | not this codec's file at all | report it as belonging to no codec and ignore it |

The default answers with the archive or with `None`, so only a codec with index level files has to
override it. Underneath it, `archiveOf(directory, path)` is `archiveOf(path)` with the tree to look
in, for a layout whose binding is inside the file rather than in its name - index 12's is: a
script's `// [proc,quicksort]` header survives the file being copied or moved, and it is what
`Cs2Layout` trusts. `archiveOf(path)` remains the member every simple codec implements and the
other two come back to it by default.

### Pristine sidecars are the framework's, not a codec's

`SourceFiles` is public and owns the naming: `SourceFiles.PRISTINE_EXTENSION`, `pristine(path)`,
`guarded(path)` and `sha256` are the one spelling of `<name>.pristine`, of the file it guards and
of the guard `index.json` records. A codec, a tool or a test that has to find a sidecar uses them
rather than a private copy of the same two lines.

`SourceTree.status()` is the read-only inventory `cache status` prints - per index the codec, the
archive, file, pristine and opaque counts, and every sidecar whose guard no longer holds with the
reason - so a tool reads the tree's contract rather than re-deriving it. It resolves each sidecar
through `owner`, which is the only thing that knows the layout.

### The one thing still outside the interface

A codec is still not told which `Cache` the tree is being unpacked from. Packing needs none - index
12's compiler is built from the tree's own sources, which is what makes a build from a tree
self-sufficient (see *clientscripts*) - but unpacking is the direction where the bytes are all
there is, and `Cs2SourceCodec` has to analyse the whole corpus to decompile one script correctly.
So it reaches for `Cache.get()` by default, with `Cs2SourceCodec.cache` as the seam a caller can
point elsewhere. Unpacking a cache means having one, so nothing is blocked by this; what it costs
is that `tools cache unpack <tree> <somewhere-else>` analyses `CACHE_PATH` rather than the
directory it was given, and says so loudly (the decompile requires the bytes it is handed to be the
ones its corpus came from) instead of writing a wrong tree.

A codec is also not told the tree *root*, only its own index directory. Index 12 would otherwise be
able to read the param types straight out of `config/param/<id>.json` rather than keeping the list
in `cs2.d.ts`; the copy in `cs2.d.ts` is the price of the index staying self-contained.

## Future work the design leaves room for

- World servers hosting their own JS5 from their own tree (`CACHE_SOURCE_PATH` per world; the
  lobby's world flag decides whether the client re-inits JS5 against the world).
- Typed codecs for the indices that are still bytes (sprites as PNG, models). Each is one codec
  class plus a byte-exact proof, the way the definition indices and index 5 got theirs; the pristine
  fallback carries the rest meanwhile.
- Typed codecs for the indices that are still bytes (maps as JSON for the map editor, models,
  index 3's remaining raw archives). Each is one codec class plus a byte-exact proof, the way the
  definition indices and the 2D indices got theirs; the pristine fallback carries the rest meanwhile.
- Having `tools`' texture rasteriser read core's `TextureDecoder` instead of parsing index 9 a second
  time. The op *definitions* are pure data and now live in core (`TextureOpFormat`,
  `TextureDefinition`), but `tools`' `TextureType` is the raster evaluator: its 40 operation classes
  decode straight into already-scaled rendering fields, so pointing them at core's records means
  rewriting all 40 `decodeOpcode` implementations, and the only check on that is a visual one. The
  format knowledge is duplicated until then, and core's is the one with a whole-cache proof behind it.
- Gameval-named file layouts (`config_obj/abyssal_whip.json`) with rename sync, as `cs2` has.
- Handing a codec the cache a tree is being *unpacked from*, the last thing `SourceCodec` does not
  offer - see *The `SourceCodec` interface* above. Packing needs no cache any more.


## Definition encoders

`DefinitionJsonCodec<T>` can only store a type as editable JSON when its encoder reproduces the
shipped file exactly - `encode(decode(bytes)) == bytes` for **every** file - otherwise the type has
to keep a `.pristine` copy beside every file it cannot rebuild. All of them do, so none keeps one;
see "Definition files" for what the JSON looks like. Twenty-two types reach that bar for
the whole 727 cache: the seven opcode keyed types of indices 16 to 22, the fourteen decoded
archives of index 2, and index 3's interface components. Two more join them from the 2D indices -
index 9's textures and index 13's font metrics - and the sprite and material codecs clear the same bar
without being `DefinitionJsonCodec`s; see *What the 2D indices cost*.

Three tests measure it, each against the shipped bytes rather than against a re-decode:

- `core/src/test/kotlin/world/gregs/voidps/cache/definition/DefinitionEncoderParityTest.kt` (indices 16-22)
- `core/src/test/kotlin/world/gregs/voidps/cache/config/ConfigEncoderParityTest.kt` (index 2)
- `core/src/test/kotlin/world/gregs/voidps/cache/definition/InterfaceEncoderParityTest.kt` (index 3)

The 2D encoders - sprites, textures, materials and font metrics - are measured the same way by
`core/src/test/kotlin/world/gregs/voidps/cache/source/ImageCodecTest.kt`, which goes one step further and
writes the files to disk before reading them back, because a sprite's pixels make the trip through a PNG
and `javax.imageio` is half of that round trip. See *What the 2D indices cost*.

All three enumerate files through the reference table (`Cache.archives` / `Cache.files`), not through
a decoder's `size`, which rounds up to whole archives and invents ids that have no file. They decode
with the decoder's `readLoop`, encode into a fresh `BufferWriter` and compare byte for byte, and they
skip with a message when `../cache/` is absent.

### One mechanism

Everything a byte-exact encoder needs beyond the decoded fields lives on one interface, is replayed
by one base class and is recorded in one place:

| piece | where | what it does |
|---|---|---|
| `OpcodeOrdered` | `cache/definition/OpcodeOrdered.kt` | `opcodeOrder`, `shadowedPayloads`, `repeats(opcode)` - the file shape the fields cannot hold |
| `OpcodeEncoder<T>` | `cache/OpcodeEncoder.kt` | replays the record list; subclasses implement `opcodes(definition)` and `encodeOpcode(definition, members, opcode, occurrence)` |
| `DefinitionDecoder.readLoop` | `cache/DefinitionDecoder.kt` | records the shape for any `OpcodeOrdered` definition; `canonicalOpcodes(definition)` is each decoder's one line into its encoder |

A definition file is a list of records - an opcode byte and its payload - closed by a 0 terminator,
so an encoder built on `OpcodeEncoder` is driven by a list of opcodes rather than by a fixed sequence
of `if (field != default)` blocks. `encodeOpcode` only ever writes one payload; it never decides
whether to write a record at all. Three things make that exact:

1. **The opcode order.** Jagex's packer emitted fields in no fixed order: only 1,913 of 73,893 locs,
   120 of 15,662 npcs, 7,231 of 25,355 objs, 7 of 1,220 map elements and none of the 48 hitmarks are
   in the order an encoder derives on its own. Two opcodes also routinely write the same field - a
   hitmark's fade is either the bare opcode 11 or opcode 14 with a cycle, a loc's contoured ground
   mode has six spellings, a mapscene with no sprite is either opcode 4 or opcode 1 with a sentinel -
   so which opcodes a file carried cannot be derived from the values either.
   `OpcodeOrdered.opcodeOrder` records the opcodes exactly as the file had them and the encoder
   replays that list.
2. **Payloads a repeated opcode overwrote.** A file may hold the same opcode twice with different
   values (603 locs, 149 objs, 111 npcs, 8 seqs, and four index 2 files: overlay 94 with two slot
   bytes, base animation set 1799 with two stand turn pairs, map elements 779 and 780 with two
   background colours). Only the last write survives on the definition, as in the client, so
   `OpcodeOrdered.shadowedPayloads` keeps the earlier payloads verbatim, indexed against
   `opcodeOrder`, and the encoder replays them in place. An entry stays null when the overwritten
   record carried the same bytes as the write that replaced it - re-encoding from the fields
   reproduces those - and for every opcode `repeats` declares to be one record per array element
   rather than a field written twice (a seq's sound frames, a base animation set's per slot
   transforms), which is written from its `occurrence`th element instead.
3. **Values the decoders threw away.** Additive fields only; no existing field changed meaning. See
   the table below.

**The recording rule is self-validating.** `readLoop` records the order for every file and nulls it
again only when the encoder's own `opcodes(definition)` reproduces it exactly. So a file that was
packed canonically - and any definition authored by hand - carries no order at all, while anything
the encoder would not emit exactly keeps the file's own order and comes back unchanged regardless.
Parity therefore never rests on a presence rule (`if (field != default)`) being right: a wrong rule
costs a stored `opcodeOrder`, not a corrupted file. A definition with any shadowed payload always
keeps its order, because the two are indexed against each other.

**Decode with `readLoop`, never `load`.** The recording happens in the read path for exactly this
reason: `changeValues` derives values that are not in the file - an obj's note and lend templates are
copied wholesale from another definition, an overlay packs its id into its slot, a quest falls back
to its name for its list name - and a definition re-encoded after that no longer matches its file.
Nothing an encoder reads is derived in `changeValues`: where the client derives an unrecoverable
value, the decoder keeps the raw one beside it (`OverlayDefinition.slot`, `colourRgb`).

### Parity per type

`order` and `shadow` are how many files have to carry that metadata; the rest are reproduced from
their values alone.

| index | archive | type | decoder / encoder | files | byte identical | residuals | order | shadow |
|---|---|---|---|---|---|---|---|---|
| 16 | all | `loc` | `ObjectDecoder` / `ObjectEncoder` | 73,893 | 73,893 | none | 71,980 | 603 |
| 17 | all | `enum` | `EnumDecoder` / `EnumEncoder` | 6,048 | 6,048 | none | 1,734 | 0 |
| 18 | all | `npc` | `NPCDecoder` / `NPCEncoder` | 15,662 | 15,662 | none | 15,542 | 111 |
| 19 | all | `obj` | `ItemDecoder` / `ItemEncoder` | 25,355 | 25,355 | none | 18,124 | 149 |
| 20 | all | `seq` | `AnimationDecoder` / `AnimationEncoder` | 17,186 | 17,186 | none | 11,260 | 8 |
| 21 | all | `spotanim` | `GraphicDecoder` / `GraphicEncoder` | 3,252 | 3,252 | none | 584 | 0 |
| 22 | all | `varbit` | `VarBitDecoder` / `VarBitEncoder` | 11,713 | 11,713 | none | 0 | 0 |
| 2 | 1 | `underlay` | `UnderlayDecoder` / `UnderlayEncoder` | 171 | 171 | none | 13 | 0 |
| 2 | 3 | `idk` | `IdentityKitDecoder` / `IdentityKitEncoder` | 652 | 652 | none | 270 | 0 |
| 2 | 4 | `overlay` | `OverlayDecoder` / `OverlayEncoder` | 248 | 248 | none | 135 | 1 |
| 2 | 5 | `inv` | `InventoryDecoder` / `InventoryEncoder` | 674 | 674 | none | 0 | 0 |
| 2 | 11 | `param` | `ParamDecoder` / `ParamEncoder` | 2,583 | 2,583 | none | 510 | 0 |
| 2 | 16 | `varp` | `PlayerVariableParameterDecoder` / `…Encoder` | 2,696 | 2,696 | none | 0 | 0 |
| 2 | 19 | `varc` | `ClientVariableParameterDecoder` / `…Encoder` | 1,992 | 1,992 | none | 0 | 0 |
| 2 | 26 | `struct` | `StructDecoder` / `StructEncoder` | 11,389 | 11,389 | none | 0 | 0 |
| 2 | 32 | `bas` | `BASDecoder` / `BASEncoder` | 2,574 | 2,574 | none | 895 | 1 |
| 2 | 34 | `mapscene` | `MapSceneDecoder` / `MapSceneEncoder` | 107 | 107 | none | 0 | 0 |
| 2 | 35 | `quest` | `QuestDecoder` / `QuestEncoder` | 204 | 204 | none | 201 | 0 |
| 2 | 36 | `mapelement` | `WorldMapInfoDecoder` / `WorldMapInfoEncoder` | 1,220 | 1,220 | none | 1,213 | 2 |
| 2 | 46 | `hitmark` | `HitSplatDecoder` / `HitSplatEncoder` | 48 | 48 | none | 48 | 0 |
| 2 | 47 | `varclan` | `ClanVarDecoder` / `ClanVarEncoder` | 2,845 | 2,845 | none | 0 | 0 |
| 3 | all | `interface` component | `InterfaceDecoder` / `InterfaceEncoder` | 65,239 | 65,239 | none | - | - |

**245,751 files - 153,109 of indices 16 to 22, 27,403 of index 2 and 65,239 interface components -
all byte identical. No file of any of these types needs a pristine copy.** Index 2's other 31
archives have no decoder and stay raw (`RawFilesCodec`).

Interface components are the one type here that is not opcode keyed: index 3 is a fixed field layout,
`InterfaceEncoder` was already byte exact and needed no change, and it implements neither
`OpcodeOrdered` nor `OpcodeEncoder`. `InterfaceEncoderParityTest` is the standalone proof, so a change
to either side shows up as a byte difference rather than only as a field difference.

### Lossless fields added

| type | field | what was lost |
|---|---|---|
| loc | `singleAnimation` | opcode 24's id when it is -1, which the client throws away |
| loc | `rawPercents` | opcode 106's animation odds, rescaled to 65535ths of their sum on read |
| loc, npc | `rawOptions` | which opcode an option came from - 30 to 34 and 150 to 154 share one array |
| loc, npc, obj, struct | `paramRecords` | opcode 249 records when a file lists a param id twice (60 objs, 62 npcs, 37 structs) |
| npc | `unusedMovementType` | opcode 128, read and discarded by both client and decoder (named unused because the world module derives a `movementType` of its own from `slowWalk`) |
| enum | `arraySize` | the array capacity of the opcode 7 and 8 forms |
| enum | `entries` | entry records when a file lists a key twice; the map is now insertion ordered |
| seq | `soundVolumeRecords`, `soundSpeedRecords` | which frame each opcode 19 and 20 record set |
| seq | `interleaveRecords` | opcode 3's indices when the file repeats one |
| seq | `unusedFlag16` | opcode 16, a flag this revision's client ignores |
| overlay | `colourRgb`, `blendColourRgb` | opcodes 1 and 7's rgb; the packed hsl the client derives is not invertible |
| overlay | `slot` | opcode 11's raw byte, before `changeValues` packs the id into it |
| mapelement | `displayFlags`, `randomisedMinimapPosition` | whole flag bytes, of which the client reads two bits |
| quest | `type`, `questPointRequirement` | opcodes 6 and 15, read and discarded |
| idk | `unknown3` | opcode 3, which the client's `IdkType` does not have at all, in 13 files |
| varclan, varp | `typeChar` | the cp1252 type descriptor; `CS2Type.forJagexDesc` is many to one |
| bas | the whole lower half of `BASDefinition` | the model box, camera easing, random stand table and per slot transforms (opcodes 26-56), all previously read and dropped |
| sprite | `IndexedSprite.flags` | the frame's format byte: which storage order it used and whether it carried an alpha plane (302 frames carry an entirely opaque one, which the client drops) |
| sprite | `SpriteDefinition.rawPalette` | the palette before the client rewrites a black entry to 1; 5,264 of 12,071 groups have one |
| sprite | `SpriteDefinition.paletteZeroEntry` | the explicit `000000` entry 0 that 29 groups write and the client seeks past |
| sprite | `SpriteDefinition.width`, `height` | the group canvas, which the client only keeps as each frame's derived border |
| fontmetrics | `version`, `unusedByte1`, `unusedByte2` | the format byte, and the two bytes the client reads and discards before the padding |
| fontmetrics | `variableWidth`, `glyphCoverageWidths`, `glyphCoverageHeights`, `horizontalOffsets`, `verticalOffsets` | the variable width form's coverage tables and offset profiles, from which the client derives the kerning table it keeps instead |
| material | `defined` | the blob's presence byte, which is the only thing that says a material exists |
| material | every field's name | `aBoolean1204`, `aByte1225`, `type`, `anInt1226` and the rest are now the client's `isSmall`, `alpha`, `effectId`, `effectCombiner` |
| texture | the whole type | index 9 had no decoder in core at all; `TextureDefinition` is the operation graph as data, `TextureOpFormat` the wire shapes |
| texture | `trailer` | the ten bytes past the three output indices that this revision's client never reads, on 2,562 of 2,591 textures |

Two ordering rules matter to any future definition codec: `Parameterized.readParameterRecords`
decodes into an insertion ordered `Int2ObjectArrayMap` and `Extra.readParameters` into an
`Object2ObjectLinkedOpenHashMap` (it was a plain open hash map, which iterated in hash order and
reordered two quests' params on the way out). A decoder that takes a `Parameters` catalog must be
built with `Parameters.EMPTY` when its output is going to be encoded again: with a catalog the extras
are keyed by param *name*, and a name cannot be turned back into the three byte key - `writeExtras`
says so rather than writing a wrong file. `Parameters.EMPTY` keys by the id as a decimal string, and
is the default for every decoder here.

### Decoder bugs found and fixed

| decoder | bug | effect before |
|---|---|---|
| `IdentityKitDecoder` | model ids (opcode 2) and head models (60-69) are big smarts, not shorts | 29 of 652 files ran off the end of the buffer, and 8 more left trailing bytes |
| `HitSplatDecoder` | the placement string (opcode 8) is a `gjstr2`, and the font and four sprites are big smarts | 47 of 48 files desynced - every opcode after the string was garbage |
| `MapSceneDecoder`, `WorldMapInfoDecoder`, `QuestDecoder` | sprite ids (mapscene 1, mapelement 1/2/18, quest 17) are big smarts | correct only because every shipped value fits in two bytes |
| `QuestDecoder` | opcodes 6 and 15 were read and discarded | `type` lost on 9 quests; `questPointRequirement` never populated |
| `Transforms.writeTransforms` | loc transform ids (opcodes 77 and 92) are big smarts, npc's are shorts | the shared writer wrote a loc's `-1` as `0xffff`, which reads back as a 4 byte smart; the helper is gone and each encoder writes its own |
| `FontDecoder` | the variable width form's second glyph offset profile is `widths[glyph]` bytes long, as the first one is, not `heights[glyph]` | latent: it would desync every byte after the profiles for any font whose two tables differ, and none of the 27 fonts in 727 is variable width at all |

Every decoder of an opcode keyed type now also fails loudly on an opcode it does not know
(`error("Unhandled … opcode")`) instead of silently desyncing the rest of the file.

### Removed duplicates

- `config/decoder/StructDecoder` + `config/data/StructDefinition` duplicated the `definition` package
  pair that `Cache.structs` actually uses, and carried a hand written patch for the seven structs
  whose duplicate params the map dropped - now handled properly by `paramRecords`.
- `config/decoder/RenderAnimationDecoder` + `RenderAnimationDefinition` were a second, *wrong* reader
  of archive 32 (shorts where the format has big smarts, five values in opcode 27 where the client
  reads six, two in 56 where it reads three); archive 32 is one type, and `BASDecoder` is the reader
  that matches the client.
- `Parameterized.readParameters(buffer)` and `Parameterized.writeParameters(writer)`: the lossy
  reader that dropped a repeated param id and a second way of writing opcode 249.
  `readParameterRecords` and `writeParams` are the one reader and the one writer.
- `config/ConfigEncoder`: an empty base every config encoder has replaced with `OpcodeEncoder`.

`Reader.readPrefixedString` is the `gjstr2` reader the client calls `readJagString`, the exact
inverse of `Writer.writePrefixedString`; it replaces the private copy `QuestDecoder` had. Shared
record payloads (colours, colour palettes, params, extras) live in
`definition/encoder/Records.kt`; unlike the mixin helpers on `Recolourable` and friends they write a
payload only, never the opcode and never a presence decision.

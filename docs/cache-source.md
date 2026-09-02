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
| Index 0 groups are split into 3 chunks per archive; everything else is 1 chunk | the split is the frame format's own three sections, so the animation frame codec rebuilds it; a raw multi-file group keeps its layout verbatim |
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
| `VerbatimGroupCodec` | nothing, in 727 | `<archive>.grp` - the decompressed group verbatim, chunk table and all. Index 0's codec until `AnimationFrameCodec` replaced it; the answer for a multi-chunk index whose split follows from nothing |
| `DefinitionJsonCodec<T>` | indices 1, 3, 10, 16-22, 33, 35, both quick chat archives, index 23's two typed archive kinds, index 28's six and 14 of index 2's archives | one JSON file per definition, see below |
| `AnimationFrameCodec` | index 0 | `<archive>/<frame>.json`, with the three chunk group rebuilt from the frames - see *anims* below |
| `ConfigCodec` | index 2 | one sub-directory per config archive, named by `Config` (`inv`, `param`, `varp`...), each with its own codec |
| `ArchiveDirectoryCodec` | indices 10, 23, 24, 25, 28 | the generalisation of `ConfigCodec`: a name and a codec per archive, either in a directory of its own (`worldmapdata/main_staticelements/`) or as one named file (`defaults/equipment.json`) |
| `Cs2SourceCodec` | index 12 | the TypeScript layout `cs2 decompile-all` writes (`<category>/<name>.ts`), provided by the `cs2` module - see *clientscripts* below |

So the indices that hold definitions are editable JSON and the rest are bytes:

| index | dir | codec | | index | dir | codec |
|---|---|---|---|---|---|---|
| 0 | `anims` | `json-animframe` | | 19 | `config_obj` | `json-obj` |
| 1 | `bases` | `json-base` | | 20 | `config_seq` | `json-seq` |
| 2 | `config` | `config` (14 archives JSON, 31 raw) | | 21 | `config_spotanim` | `json-spotanim` |
| 3 | `interfaces` | `json-interface` | | 22 | `config_varbit` | `json-varbit` |
| 10 | `binary` | `json-huffman` | | 23 | `worldmapdata` | `worldmap` (2 kinds JSON, the area images raw) |
| 12 | `clientscripts` | `Cs2SourceCodec`, from the `cs2` module | | 24 | `quickchat` | `quickchat` |
| 16 | `config_loc` | `json-loc` | | 25 | `quickchat_global` | `quickchat` |
| 17 | `config_enum` | `json-enum` | | 28 | `defaults` | `defaults` |
| 18 | `config_npc` | `json-npc` | | 33 | `loading_screens` | `json-gametip` |
| | | | | 35 | `cutscenes` | `json-cutscene` |
| | | | | everything else | | `raw-archive` / `raw-files` |

Thirty-two of the 37 indices' *records* are now editable JSON; what is left as bytes is the assets -
models, sprites, textures, maps, sounds, fonts, particles, billboards, materials, native libraries,
shaders and the loading sprites - and index 2's 31 undecoded archives.

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
| `Packed(0)` | indices 1, 33, 35 | `bases/2405.json` | the archive id, because the archive holds one file whose id is 0 |
| `Nested(16)` | indices 3 and 0 | `interfaces/1477/32.json`, `anims/12/5.json` | `1477 shl 16 or 32` |
| `Flat` | index 2's typed archives, index 23's and both quick chat indices' | `config/struct/26.json`, `quickchat/quickchatphrase/12.json` | the file id; the directory is the archive |
| `NamedLayout` | indices 10 and 28 | `defaults/equipment.json` | the archive id; the one file is named after the archive rather than after its id |

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

`DefinitionCodecs` is the one list of the twenty-two types and what registers them; `DefinitionJson`
is the mapping, and it derives these rules from the data class rather than being told them:

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
them (`DefinitionJsonCodecTest` for the config types, `MiscCodecTest` for the eight other indices, and
`cache status` on a real tree):

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
| animation frame | `anims` | `json-animframe` | 490,049 | 490,049 | 0 |
| animation base | `bases` | `json-base` | 3,535 | 3,535 | 0 |
| huffman | `binary` | `json-huffman` | 1 | 1 | 0 |
| world map area | `worldmapdata/details` | `json-worldmaparea` | 45 | 45 | 0 |
| world map element | `worldmapdata/<area>_staticelements` | `json-worldmapelement` | 1,091 | 1,091 | 0 |
| quick chat option | `quickchat*/quickchatoption` | `json-quickchatoption` | 242 | 242 | 0 |
| quick chat phrase | `quickchat*/quickchatphrase` | `json-quickchatphrase` | 1,216 | 1,216 | 0 |
| defaults | `defaults` | `json-defaults` | 5 | 5 | 0 |
| equipment defaults | `defaults/equipment.json` | `json-equipment` | 1 | 1 | 0 |
| game tip | `loading_screens` | `json-gametip` | 31 | 31 | 0 |
| cutscene | `cutscenes` | `json-cutscene` | 16 | 16 | 0 |
| **total** | | | **741,983** | **741,983** | **0** |

Index 2's other 31 archives have no decoder and stay raw bytes (`raw-files-flat`).

The rest of this section is one heading per index that is not a config: what its files hold, what the
client destroys on the way in that the tree therefore keeps, and anything about the index that is not
obvious from its format. `clientscripts` is the same kind of section and has its own, further down.

### anims

**Index 0, 4,085 frame sets, 490,049 animation frames, one JSON file each.** It is the only index whose
*group layout* has to come back as well as its bytes.

A frame is a sparse edit of the skeleton it names: for each of that skeleton's transforms in turn a flag
byte says which of the x, y and z components the frame moves, and the components it does move follow as
signed smarts in one run at the end of the file.

```json
{
  "id": 5,
  "baseId": 1,
  "header": 1,
  "flags": [3, 7, 0, 3, 7],
  "values": [-64, 60, -112, -80, -16, -68, -28, -48, -112, -64]
}
```

**The three chunk split is derived, not recorded.** Every multi-file archive in index 0 is stored in three
chunks, and a group joined as one chunk is different - equally valid, but different - bytes, which is why
index 0 used to be kept as the decompressed group verbatim (`<archive>.grp`, `VerbatimGroupCodec`). It does
not have to be, because for index 0 the split *is* the frame format's own three sections:

| chunk | holds | length |
|---|---|---|
| 0 | the header: one byte, the skeleton id, the transform count | always 4 |
| 1 | one flag byte per transform | the transform count |
| 2 | the transform values, as signed smarts | whatever is left |

So `AnimationFrameCodec` rebuilds the layout from the frames rather than storing it: nothing about it goes
into `index.json`, nothing about it can go stale against the files, and adding or removing a frame is
adding or removing a file exactly as it is everywhere else. The eleven single-file archives carry no chunk
table at all and get none. `MiscCodecTest` proves the split on all 4,085 archives.

**What the client throws away and the tree keeps.** `AnimFrame` is lossy in five ways, so none of what it
keeps is what the file holds:

- a rotation transform's values are rescaled `(v shl 2) and 0x3fff`, which is not invertible;
- a component whose flag bit is clear has no bytes at all, and the client substitutes `0` - or `128` for a
  scale - which a decoded value cannot be told apart from, so the **raw flag byte** is what is stored;
- flag bits `0x20`, `0x40` and `0x80` are read and dropped, and only a raw flag byte can carry them;
- the file's first byte is never read by anything (it is `1` in all 490,049 frames), and is kept as
  `header`;
- the skeleton id is read by the frame set loader rather than by the frame, and is kept as `baseId`.

`labels`, `modifiesAlpha`, `modifiesColour` and `modifiesBillboard` are the other direction - derived from
the skeleton's transform types - and are not fields of the file, so they are not in the tree.

**Two quirks of the 727 packer, both reproduced.** The transform count is one byte, and archive 162's nine
frames declare **0** transforms and then carry **256** flag bytes: `count and 0xff` from a packer that
never checked. The client reads 0, finds the file longer than its four byte header and throws the frame
away, so those nine animations have never worked; the tree still has to reproduce them, and both directions
read a declared 0 with a payload behind it as 256. Separately, a signed smart's two byte form can hold a
value the one byte form fits, which would not survive a re-encode - `wideValues` records any such position
and **no frame in the 727 cache has one**, so the field is never written.

**The cost.** Index 0 is by a wide margin the most expensive index in the tree: 490,049 files and **747 MB**
of JSON against 60 MB of `.grp` blobs, because a frame is almost all numbers and a number costs more as
text than as a smart. That is the price of a frame being a file somebody can open, and `VerbatimGroupCodec`
is still there for anyone who decides it is not worth paying.

### bases

**Index 1, 3,535 skeletons, one archive and one JSON file each** (`bases/2405.json`).

Five columns of one row per transform, no opcodes and no length but its own: the transform's kind, whether
it is shadowed, which submeshes it applies to, and the model labels it moves. The file's length is a
function of its contents - the label table's own lengths are the fourth column - so anything past the table
is kept verbatim in `trailing`, which nothing in 727 needs.

Two values the client destroys on the way in are kept raw:

- `AnimBase` rewrites a transform type of `6` to `2` in place, so a decoded `2` could have been either.
  **No 727 base has a 6**, but a decoder that folds it is not one you can encode from, so `types` is the
  file's byte.
- `shadowed` is a byte the client compares `== 1`, so `0` and `2` both decode to false. It is kept as the
  byte; 727 only ever holds 0 or 1.

The same one byte count wrap as index 0: bases **161 and 3445** declare 0 transforms in 2,559 bytes, which
is 256 transforms written into a byte, and both directions read it that way.

### binary

**Index 10, one archive, one file: the huffman table chat is compressed with** (`binary/huffman.json`).

The file is 256 unsigned bytes, one per cp1252 symbol, holding the number of bits that symbol's code takes;
the tree the server builds from it (`Huffman`) is derived from those lengths alone. So the file *is* the
lengths, and as JSON it is 256 editable numbers with the short codes sitting on the letters chat uses.

The archive is called `huffman`, which is the name the client asks for and a certain one
(`docs/cache-archive-names.md`, "Index 10"), so the file is named after it rather than after its id. The
reference table's name hash still lives in `index.json` like every other index's - a name is a display
concern here too.

### worldmapdata

**Index 23, 86 archives of three unrelated things.**

```
worldmapdata/details/29.json                 one world map area; the file id is the area id
worldmapdata/main_staticelements/0.json      one placed icon of that area
worldmapdata/main/4.dat                      that area's rendered image, bytes
```

An **area** (`details`, 45 files) is a flat struct rather than an opcode list: the internal name, the shown
name, the bitpacked origin, the colour, three display bytes and then the rectangles that map game
coordinates onto world map coordinates, 17 bytes each. Two values are kept raw because the client folds
them - `rawZoom`, because a zoom of 255 becomes 0 (one area, `null`/"Loading...", has it), and `mapSizeId`,
which the client reads and drops. The four `minX`/`minY`/`maxX`/`maxY` bounds are the extremes of the
sections and are derived, so they are not written.

A **static element** (1,091 files across 34 archives) is seven bytes: a bitpacked coordinate, a `mapelement`
id and the members flag, which is `1` on 25 of them.

An **area image** is the single file of an area archive - 91 KB for Ardougne's underground - and has no
decoder, so it stays bytes (`raw-files-flat`). Its file id is 4 in most areas and 0 in one, and the file
name carries it, so nothing about it goes in `index.json` either.

**The directory names are the archives' real names, and only because they are certain.** Index 23 is the one
index in 727 whose archive names are neither a formula nor a catalog, so they were recovered once and are
recorded in `WorldMapCodec`; every one of the 86 re-hashes to the name hash the reference table holds, which
is what makes a name a fact rather than a guess. The table is checked against the cache on every unpack and
by `MiscCodecTest`, so a tree built from a cache whose index 23 has moved fails loudly instead of writing
wrong directories. The *file* names index 23 also carries - 731 of its 1,181 files have one - are not used
for anything: a static element is addressed by its file id like every other definition and its name hash
rides along in `index.json`, exactly as index 31's do. See `docs/cache-archive-names.md`, "Index 23", and
`docs/reference/worldmap-cache-names.json`.

### quickchat and quickchat_global

**Indices 24 and 25, two archives each: the menus and the phrases.**

```
quickchat/quickchatoption/12.json         a quick chat menu
quickchat/quickchatphrase/113.json        a quick chat phrase
quickchat_global/quickchatoption/1.json   the same two archives, in the global index
```

The directories are the gameval type names, so a file's path and its catalog entry line up:
`quickchat/quickchatoption/5.json` is `quickchatoption.json`'s entry 5. Both indices hold the same two
archives and the same two types; at runtime the client tells them apart by setting `0x8000` on every id it
reads out of index 25, which is a `changeValues` derivation and therefore not in the tree.

Both types are opcode keyed and both gained the opcode this revision's decoders were dropping:

- a **menu** (242 files) has opcode 4, a bare flag the client reads and does nothing with. Exactly one file
  has it - index 25's `Quick Chat` root menu - and it is the *first* record in that file, so without
  `unusedFlag4` the record would be lost and the file would not come back. That one file is also the only
  one of the 242 that needs an `opcodeOrder`.
- a **phrase** (1,216 files) has opcode 4, which clears `searchable`; nothing in 727 writes it.

A phrase's text is stored split on `<`, one fragment per dynamic value slot and one trailing fragment,
which is what the client does and is exactly invertible. Opcode 3's value types carry as many shorts as the
type table declares, and a type the table does not know carries none - which is what the client does too,
and is recorded rather than guessed at.

### defaults

**Index 28, six archives of six unrelated settings, one named JSON file each.**

```
defaults/scene_cube_textures.json      the six skybox cube face textures
defaults/entity.json                   hitsplat stacking, the login interface, the recolour palettes
defaults/titlescreen_music_info.json   which music archive the title screen plays
defaults/equipment.json                the customisable worn slots and the weapon/shield slots
defaults/interactions.json             the mouse and key bindings
defaults/cutscene.json                 the key that exits a cutscene
```

The names are the client's own (`DefaultsFile`), and the archive holds one file so its name is the file's
name - there is no directory in between. The ids are 1, 3, 4, 6, 7 and 8; 0, 2 and 5 do not exist in 727.

Equipment is `BodyDefinition`, which the server already had, extended with the two layout fidelity fields
and given an encoder. The other five share one `DefaultsDefinition` whose fields are all nullable, so an
archive's file carries only its own settings and reads as what it is; which archive a definition is is its
id, because the same opcode means a different thing in each. A `Boolean?` field is a bare flag opcode:
non-null means the file carried the record.

Three of the six needed a field for something the client reads and drops: interaction opcodes 8, 9 and 10
decode an input binding and discard it, and `inputs` keeps them like any other. Two of the six carry a
non-canonical record order - `entity` writes opcode 3 before opcode 1 because 3 is what sizes 1, and
`equipment` writes 1, 3, 5, 4, 6 - and both keep an `opcodeOrder`.

### loading_screens

**Index 33, 31 archives: a stage table and 30 loading screens.**

Archive 0 is the master table - a format version, the ten render types' signature, and per stage the list
of screens it may show with their durations - and archives 2 to 37 are one screen each: a count and then a
render type byte and a body per element. `GameTipDefinition` carries both shapes and which one a file is is
whether it is archive 0.

The ten element types are implemented from the client; **three of them occur in 727** - `SPRITE` 548 times,
`ANCHORED_TEXT` 145 and `ANIMATED_LOADING_BAR` 30, 723 elements in all - so the other seven rest on the
client code rather than on the bytes, and are called out here rather than left to be discovered.

Everything the client checks and then folds is kept raw: the version and the render type signature (the two
values it bails out on), `hasUpdates` and `drawOutline` as bytes rather than booleans, the anchors and
alignments as ordinals, and `SPRITE_LOADING_BAR`'s fill colour, which the client reads and then passes its
background colour twice instead. `wideFileIds` records a big smart the file packed wide when narrow would
have held it; no element in 727 has one.

The archive names are 31 hashes nobody has recovered (`docs/cache-archive-names.md`, "Indices with names
still unrecovered"), so the directories here are ids.

### cutscenes

**Index 35, 16 archives, one cutscene each.**

A cutscene is a header - a viewport size and a `255` terminator - and then six counted lists: the map areas
it assembles, the camera movements, the actors, the objects, the actor movements, and the actions. An
action is an id, a duration in cycles and a body, and there are 31 action ids.

`CutsceneActionRecord` is one flat record with a `type` and a nullable field per member, which is both what
the JSON mapper can hold and what reads best, because a null field is left out: a `PlaySong` action is
`{"type": 30, "lengthInCycles": 6, "musicId": 62, "volume": 255}` and nothing else. Fields that mean the
same thing across actions - `entityIndex`, `packedPos`, `plane`, `volume` - are shared rather than
duplicated per action.

**14 of the 31 action ids occur in 727**, 1,296 actions in all: `DirectCameraTransition` 248,
`MoveEntity` 246, `AnimatedMovement` 164, `ResetEntity` 129, `BasicMovement` 123, `PlayVorbis` 116,
`ReplaceObject` 97, `DestroyObject` 54, `AnimateObject` 42, `FadeScreen` 40, `Finished` 16,
`EntitySpotAnimation` 12, `PlaySong` 7 and `ExecuteScript` 2. The other 17 - including all four projectile
forms, whose source/target packing is the only place the client's dispatch table could be misread - are
implemented from the client and unexercised by parity. Ids 2, 3 and 43 are dead in the client (it reads
nothing and then dereferences null) and the decoder fails loudly on them.

Two readings the bytes settled rather than the client:

- `MoveEntity`'s direction is the **signed** smart, not the unsigned one. Both widths turn on the same
  `first < 128` test so parity holds either way, but under the signed reading the 246 values are `0..16382`
  - a 14-bit angle with its modes on the cardinal points - and under the unsigned one they would all be
  `16384..32767`, off the end of the angle space.
- an actor carries a null-terminated label the client reads and discards. 177 of them are non-empty, and
  without keeping it the file cannot be rebuilt at all.

Camera keyframes are stored as the file's unsigned shorts rather than the client's `- 5120` world
coordinates, and an area's coordinate stays packed, so the JSON is what the file says.

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

Measured over the whole 727 cache, **all 741,983 definition files of all 33 types survive the round trip
and not one needs a pristine copy**; `DefinitionJsonCodecTest` and `MiscCodecTest` are the measurement and
the "Parity per type" table below the per type breakdown.

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
tree is already right and 42 s when every definition file has to be written (140,692 archives,
398,112 files, 1.37 GB, 8 opaque, 1,066 pristine - all of them clientscripts), pack 12.1 s, verify
10.6 s at full parity, a no-op build 2.0 s and a one-archive build 3.0 s. Unpacking costs six seconds more than it did as raw bytes
because every one of the 245,751 definition files is decoded, written as JSON, read back and
re-encoded on the way out to prove it survives the trip; the tree is 290 MB bigger for the same
reason.

With index 12 as TypeScript the same runs cost about 8 s more to unpack (the CS2 corpus analysis
and a round-trip compile of every script) and 1.6 s more to pack (the folder's signature scan, then
5,501 compiles). Measured on the tree at `cache-source/`, building it into an empty directory and
opening the result takes **16 s** end to end; a second run with nothing changed is **2.7 s**, of
which the build itself is a stat walk; editing one clientscript and rebuilding is **4.4 s** and
repacks exactly that one archive. `cache verify` of the built cache against the tree is 12 s at
full parity: 140,692 containers, 37 reference tables and the derived version table, all identical.

**Those whole-tree numbers predate the eight record indices above and have not been re-measured.** They
add 496,232 files and 769 MB of JSON to the tree, of which index 0 alone is 490,049 files and 747 MB, so
both the unpack and the tree's size grow by roughly a factor of two and a half; `WholeCacheSourceTest`'s
unpack time limit was raised to match. What has been measured, on the same machine, is the per index round
trip: `MiscCodecTest` decodes all 7,764 archives of the eight indices, writes all 496,277 files to disk,
reads them back and packs every group in **107 s**, and `MiscEncoderParityTest` encodes all 496,232
definitions - no disk - in a few seconds. Index 0 is essentially all of both.

| index | dir | archives | files | JSON |
|---|---|---|---|---|
| 0 | `anims` | 4,085 | 490,049 | 747 MB |
| 1 | `bases` | 3,535 | 3,535 | 15 MB |
| 10 | `binary` | 1 | 1 | 1 KB |
| 23 | `worldmapdata` | 86 | 1,181 | 6.6 MB (6.5 MB of it the raw area images) |
| 24 | `quickchat` | 2 | 1,372 | 178 KB |
| 25 | `quickchat_global` | 2 | 86 | 9 KB |
| 28 | `defaults` | 6 | 6 | 22 KB |
| 33 | `loading_screens` | 31 | 31 | 223 KB |
| 35 | `cutscenes` | 16 | 16 | 334 KB |
| | **total** | **7,764** | **496,277** | **769 MB** |

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
- Typed codecs for the indices that are still bytes (sprites as PNG, maps as JSON for the map
  editor, models). Each is one codec class plus a byte-exact proof, the way the definition indices
  got theirs; the pristine fallback carries the rest meanwhile.
- Gameval-named file layouts (`config_obj/abyssal_whip.json`) with rename sync, as `cs2` has.
- Handing a codec the cache a tree is being *unpacked from*, the last thing `SourceCodec` does not
  offer - see *The `SourceCodec` interface* above. Packing needs no cache any more.


## Definition encoders

`DefinitionJsonCodec<T>` can only store a type as editable JSON when its encoder reproduces the
shipped file exactly - `encode(decode(bytes)) == bytes` for **every** file - otherwise the type has
to keep a `.pristine` copy beside every file it cannot rebuild. All 33 do, so none of them keeps one;
see "Definition files" for what the JSON looks like. Thirty-three types reach that bar for
the whole 727 cache: the seven opcode keyed types of indices 16 to 22, the fourteen decoded
archives of index 2, index 3's interface components, and the eleven types of the eight other indices
that hold records rather than assets - animation frames and skeletons, the huffman table, the world map's
areas and static elements, both quick chat types, the six client defaults, the loading screens and the
cutscenes.

Four tests measure it, each against the shipped bytes rather than against a re-decode:

- `core/src/test/kotlin/world/gregs/voidps/cache/definition/DefinitionEncoderParityTest.kt` (indices 16-22)
- `core/src/test/kotlin/world/gregs/voidps/cache/config/ConfigEncoderParityTest.kt` (index 2)
- `core/src/test/kotlin/world/gregs/voidps/cache/definition/InterfaceEncoderParityTest.kt` (index 3)
- `core/src/test/kotlin/world/gregs/voidps/cache/definition/MiscEncoderParityTest.kt` (indices 0, 1, 10, 23, 24, 25, 28, 33, 35)

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
| 0 | all | `animation frame` | `AnimationFrameDecoder` / `AnimationFrameEncoder` | 490,049 | 490,049 | none | - | - |
| 1 | all | `animation base` | `AnimationBaseDecoder` / `AnimationBaseEncoder` | 3,535 | 3,535 | none | - | - |
| 10 | 1 | `huffman` | `HuffmanTableDecoder` / `HuffmanTableEncoder` | 1 | 1 | none | - | - |
| 23 | `details` | `world map area` | `WorldMapDetailsDecoder` / `WorldMapDetailsEncoder` | 45 | 45 | none | - | - |
| 23 | `*_staticelements` | `world map element` | `WorldMapStaticElementDecoder` / `…Encoder` | 1,091 | 1,091 | none | - | - |
| 24, 25 | 0 | `quick chat option` | `QuickChatOptionDecoder` / `QuickChatOptionEncoder` | 242 | 242 | none | 1 | 0 |
| 24, 25 | 1 | `quick chat phrase` | `QuickChatPhraseDecoder` / `QuickChatPhraseEncoder` | 1,216 | 1,216 | none | 0 | 0 |
| 28 | 1, 3, 4, 7, 8 | `defaults` | `DefaultsDecoder` / `DefaultsEncoder` | 5 | 5 | none | 1 | 0 |
| 28 | 6 | `equipment` | `BodyDecoder` / `BodyEncoder` | 1 | 1 | none | 1 | 0 |
| 33 | all | `game tip` | `GameTipDecoder` / `GameTipEncoder` | 31 | 31 | none | - | - |
| 35 | all | `cutscene` | `CutsceneDecoder` / `CutsceneEncoder` | 16 | 16 | none | - | - |

**741,983 files - 153,109 of indices 16 to 22, 27,403 of index 2, 65,239 interface components and 496,232
of the eight other record indices - all byte identical. No file of any of these types needs a pristine
copy.** Index 2's other 31 archives have no decoder and stay raw (`RawFilesCodec`), and so do index 23's 45
per-area images.

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
| animation frame | `header`, `flags`, `values`, `wideValues` | the file's unread first byte; the raw flag byte, whose top three bits the client drops and whose clear bits are indistinguishable from a decoded 0 or 128; the smarts before the client rescales a rotation's by `shl 2 and 0x3fff`; and the width a smart was written in |
| animation base | `types`, `shadowed`, `trailing` | a transform type of 6, which the client rewrites to 2 in place; a shadow byte that is neither 0 nor 1; anything past the label table |
| world map area | `rawZoom`, `mapSizeId` | the zoom byte before the client folds 255 to 0, and the map size byte it reads and drops |
| quick chat option | `unusedFlag4` | opcode 4, a bare flag this revision's client does nothing with, and the first record of one file |
| quick chat phrase | `searchable` | opcode 4, which hides a phrase from the quick chat search |
| defaults | `inputs` entries for opcodes 8, 9 and 10 | three input bindings the client decodes and discards |
| game tip | `version`, `renderTypes`, `hasUpdates`, `drawOutline`, `fillColour`, `wideFileIds` | the two values the client checks and then bails out on; two booleans that are bytes in the file; the fill colour of a sprite loading bar, which the client reads and then overwrites with its background colour; and the width a big smart was written in |
| cutscene | `label`, `hitmarkFlags`, raw camera keyframes | an actor's dev label, read and discarded by the client; the hitmark flag byte, of which one bit selects two values the client throws away; and the keyframe coordinates before the client's `- 5120` |

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

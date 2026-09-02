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
| `DefinitionJsonCodec<T>` | indices 3 and 16-22, and 14 of index 2's archives | one JSON file per definition, see below |
| `ConfigCodec` | index 2 | one sub-directory per config archive, named by `Config` (`inv`, `param`, `varp`...), each with its own codec |
| `Cs2SourceCodec` | index 12 | the TypeScript layout `cs2 decompile-all` writes (`<category>/<name>.ts`), provided by the `cs2` module - see *clientscripts* below |
| `TranscodedArchiveCodec` | indices 6 and 11 | `<archive>.mid` - a standard MIDI file, rebuilt into the cache's compact encoding on the way back |
| `BinaryArchiveCodec` | index 15 | `<archive>.jagpatch` - the payload verbatim, under an extension that says what it is |
| `VorbisCodec` | index 14 | `<archive>.jagvorbis`, plus one derived `samples.json` listing every sample's header |
| `NamedBytesCodec` | index 30 | `<os>/<arch>/<library>` - one file per archive, at the archive's own recovered name |
| `ShaderCodec` | index 31 | `<archive name>/<file name>` - the names the archives spell out; program definitions as JSON |

So the indices that hold definitions are editable JSON and the rest are bytes:

| index | dir | codec | | index | dir | codec |
|---|---|---|---|---|---|---|
| 2 | `config` | `config` (14 archives JSON, 31 raw) | | 19 | `config_obj` | `json-obj` |
| 3 | `interfaces` | `json-interface` | | 20 | `config_seq` | `json-seq` |
| 4 | `synth_sounds` | `json-synth` | | 21 | `config_spotanim` | `json-spotanim` |
| 6 | `midi_songs` | `midi` | | 22 | `config_varbit` | `json-varbit` |
| 11 | `midi_jingles` | `midi` | | 30 | `dlls` | `dlls` |
| 12 | `clientscripts` | `Cs2SourceCodec`, from the `cs2` module | | 31 | `shaders` | `shaders` |
| 14 | `vorbis` | `vorbis` | | 0 | `anims` | `verbatim-group` |
| 15 | `midi_instruments` | `jagpatch` | | everything else | | `raw-archive` / `raw-files` |
| 16 | `config_loc` | `json-loc` | | | | |
| 17 | `config_enum` | `json-enum` | | | | |
| 18 | `config_npc` | `json-npc` | | | | |

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
| `Packed(0)` | index 4 | `synth_sounds/1234.json` | the archive id, since the index holds one file per archive |

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

Index 4's synth sounds are the twenty-third type this codec stores and the only one outside indices
2, 3 and 16-22, so they are counted in *synth sounds (index 4)* below rather than in this table,
which `DefinitionJsonCodecTest` produces from `DefinitionCodecs`. The audio and native asset indices
each get a section of their own after *clientscripts*.

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

### synth sounds (index 4)

Index 4 is not audio. Each of its 10,084 archives is the *recipe* for a sound - ten instrument
slots, each an additive synthesiser of up to five oscillators shaped by envelopes, an echo and a
resonant filter, plus the loop points - which the client mixes into 22 kHz PCM at play time. That is
why an archive is a few hundred bytes and the sound it makes is a second long, and why it is worth
storing as JSON: every number in it is a knob somebody might want to turn.

```
synth_sounds/1234.json
```

`SynthSoundDecoder` / `SynthSoundEncoder` are the pair, and the layout is a fixed sequence rather
than an opcode list, so there is no `opcodeOrder` to record:

| field | width | note |
|---|---|---|
| ten instrument slots | a byte, or an instrument | the byte is the slot's pitch envelope form, and a zero means the slot is empty |
| an envelope | form, `start`, `end`, then its shape | shape is a phase count and that many duration/peak short pairs |
| an optional envelope pair | a zero byte, or two envelopes | vibrato, tremolo and the gater are each written as a pair or not at all |
| oscillators | volume, pitch, delay | smart, *signed* smart, smart; a zero volume ends the list, and the terminator is always written |
| echo and timing | `delayTime`, `delayDecay` smart, `duration`, `offset` short | |
| the filter | a nibble each of pole pairs, two gains, a mask byte, then the pairs | the sweep envelope follows exactly when the mask is set or the gains differ |
| loop | `loopBegin`, `loopEnd` short | milliseconds |

Two values the client throws away are kept because the file cannot be rebuilt without them: the
filter's **mask byte** (`varying`), whose unused high bits still decide whether a sweep envelope
follows, and each pole pair's **end values**, which the client copies from the start values for a
pair whose mask bit is clear. Everything else is a field a reader would expect. The signed smart the
oscillator pitch uses is `Reader.readSignedSmart` / `Writer.writeSignedSmart`, added for this.

**10,084 of 10,084 files are byte identical and none needs a pristine copy** - measured twice, by
the encoder alone (`encode(decode(bytes)) == bytes`) and through the JSON
(`encode(read(write(decode(bytes)))) == bytes`), in `SynthSoundCodecTest`.

### midi songs and jingles (indices 6 and 11)

Indices 6 and 11 hold **Jagex's compact MIDI encoding**, which is a column store of exactly the
events a standard MIDI file carries. A standard file interleaves everything - a delta time, a status
byte, that event's data bytes, over and over; Jagex's splits the same stream into one column per
kind of byte and delta-codes each column against its own running value:

```
opcodes        one byte per event, tracks back to back, each track closed by 0x07
deltas         one variable-length int per event, in the same order
controllers    one byte per controller change: the delta of the controller number
<18 columns>   one byte per event that uses them, in event order
tempo          three bytes per tempo event
trackCount     one byte
division       two bytes
```

An opcode's low nibble is the event type and its high nibble is the **exclusive or** of the channel
with the running channel, so a run of events on one channel costs a nibble of zero; `0x07` ends a
track and `0x17` is a tempo change. A status byte goes into the standard file only when the opcode
differs from the previous opcode's low nibble, which is where the standard file's running status
comes from - and is why both directions agree on it without anything being recorded.

The tree stores the **standard MIDI file**:

```
midi_songs/42.mid
midi_jingles/7.mid
```

That is only allowed because the way back is exact, and it was measured rather than assumed
(`JagexMidiTest`): expand every one of the 1,662 files, rebuild the compact encoding from the
expansion alone, and compare. **1,137 of 1,137 songs and 525 of 525 jingles come back byte for
byte.** Two conventions had to be measured, because the format admits more than one encoding of the
same music and only one of them is what Jagex's packer wrote:

- **a column's delta is the non-negative byte** congruent to the difference. The client adds the byte
  to a running total and keeps the low seven bits, so `-3` and `125` decode identically; the shipped
  files use `125`. This is the whole difference between 18 files round-tripping and all 1,662.
- **a delta time is the shortest variable-length int.** No file uses a padded one.

Round-tripping through `.mid` costs the tree nothing and saves it 10 MB: the compact encoding is
*larger* raw (50.6 MB against 40.0 MB for the same music), because it spends a byte per event on the
opcode to save the status bytes that running status would have elided. Its advantage is that similar
bytes end up adjacent, which is worth about a third to the gzip the container wraps it in - and the
container still gzips whatever the tree packs, so nothing is lost either way.

`JagexMidi.toStandard` and `JagexMidi.toCompact` are the pair, and `TranscodedArchiveCodec` is the
codec both indices share. It keeps the pristine mechanism even though nothing uses it, so an archive
that ever stopped reproducing would cost a `<archive>.mid.pristine` sidecar rather than the parity
guarantee.

### vorbis (index 14)

Index 14 is 16,824 mono samples and, in archive 0, the one vorbis **setup header** they all share.
The samples are **bare vorbis packets in Jagex's own framing** - five big endian ints and then each
audio packet preceded by its length:

| field | width |
|---|---|
| `sampleRate` | int |
| `samples` | int, the decoded length in mono samples |
| `loopStart` | int |
| `loopEnd` | int, whose **sign bit** is a loop consistency flag and whose value is then inverted |
| `packets` | int |
| per packet | its length as bytes that add up, `0xff` meaning "255 and more", then that many bytes |

**These stay bytes** (`vorbis/1234.jagvorbis`), and the reason is that an `.ogg` is not a
re-encoding of them, it is an invention. Ogg carries, and index 14 does not: a stream serial number,
a granule position per page, a page segmentation, a CRC per page, an identification header (version,
channel count, three bitrate fields), and a comment header with a vendor string. A converter would
have to make every one of those up, and *nothing* about the file it produced could be checked back
against the cache - which is the opposite of the parity this tree exists for. The two blocksize
nibbles the identification header would carry are not in the samples either; they are the first byte
of archive 0.

What the codec does instead is describe them. `vorbis/samples.json` lists every sample's header -
sample rate, length in samples and in milliseconds, loop points, packet count - and the setup
header's block sizes and codebook count. It is **derived**: it belongs to the index rather than to
any archive (`Owner.Index`), so editing it changes nothing and packs nothing. A per-file sidecar was
the alternative and is worse, because a header field and the packets it counts are not independent:
two files could disagree and nothing would notice. One listing that owns nothing cannot.

**16,825 of 16,825 archives, exact by construction.** 228 MB, which is two thirds of everything
these seven indices weigh.

### midi instruments (index 15)

Index 15's 247 archives are the sound bank: for each of 128 keys, which sample plays
(index 4 or index 14, packed into one varint), at what pitch, volume, pan and loop mode, plus the
volume and release envelopes of the patches those keys share. **These stay bytes**
(`midi_instruments/12.jagpatch`), and for a different reason than index 14's.

The format is a run-length column store - four run tables terminated by `0x00`, value regions
reserved at the front and consumed a hundred lines later, per-key cumulative deltas, a varint column,
and two curves interpolated across the keyboard - and the client's decode is **destructive**: it
folds the volume curve into `volumeOffset` and the pan curve into `panOffset`, clamps the result,
never stores either curve, and drops all four run tables on the floor. The decoded object is
therefore not enough to rebuild the file, and re-deriving the runs by run-length-encoding the decoded
arrays would not reproduce a packer that split a run redundantly. On top of that the client
reference's own reader is visibly damaged in this class (its run-table scan double-counts the buffer
offset, so it is correct only for the first of the four tables), so there is no trustworthy decoder
to port in the first place.

A lossless JSON of this format is possible - it would have to keep the raw columns and the run
lengths, which is a hex dump wearing a schema - and it is not worth it until something wants to edit
a sound bank. Meanwhile the bytes are exact by construction and say what they are.

**247 of 247 archives, exact by construction.**

### native libraries (index 30)

Index 30's 33 archives are the client's native libraries - `hw3d`, `sw3d`, `jaggl`, `jaclib`,
`jagdx` - one per platform, and the payload is the PE, ELF or Mach-O image itself, byte for byte
what the client writes to disk and hands to `System.load`. There is nothing to decode; what there is
to recover is the **name**.

```
dlls/windows/x86/hw3d.dll
dlls/linux/x86_64/libjaggl.so
dlls/macos/universal/libhw3d.dylib
```

The names are a formula, not a catalogue (`docs/cache-archive-names.md`): `<os>/<arch>/<file>` over
`windows|linux|macos`, `x86|x86_64|ppc|universal` and the platform's spelling of the library name.
`world.gregs.voidps.cache.NativeLibrary` builds the 60 candidates the client can ask for and hashes
them, and **all 33 archives are accounted for** with none left over - which `NativeAssetCodecTest`
asserts, so a cache with an archive the formula does not cover fails rather than quietly becoming
`<archive>.dat`.

The hash stays the truth, exactly as `index.json`'s `nameHash` field promises. Packing hashes the
path back and looks it up in the metadata, so a renamed file is a file the pack does not recognise -
never a silently re-hashed archive.

**33 of 33 archives, exact by construction.**

### shaders (index 31)

Index 31 is two archives - `gl` (archive 1) and `dx` (archive 3) - and 112 files, all of them named
in the reference table and none of the names a formula. It is the one index that **describes
itself**: a dozen files in each archive are *program definitions*, and a program definition spells
out the program's name and the source file names its techniques compile. The table hashes the
**lower case** form of exactly those strings, so reading an archive's definitions recovers the names
of every file in it. Nothing is stored for the recovery; it runs from the archive's own bytes while
it is unpacked.

```
shaders/gl/model.json                              the program definition
shaders/gl/model.vertex.glsl                       GLSL source, verbatim
shaders/dx/model.vertex.vs_2_0.0000000400000001    compiled Direct3D bytecode, verbatim
```

A GLSL source is plain text and a Direct3D source is a compiled `vs_2_0`/`ps_2_0` blob; both are the
payload verbatim under their own name, because there is nothing to decode and the leading blank
lines in the GLSL are really in the file. A program definition is a small binary structure and is
stored as JSON:

```json
{
  "header": 4,
  "name": "Sprite",
  "variables": [
    {"type": 45, "elementType": null, "arraySize": null, "name": "SpriteSampler",
     "alias": "", "program": [-1, -1, -1], "slot": [0, 0, 0]}
  ],
  "optionalVariables": [],
  "mappings": [
    {"name": "Masked", "vertexSource": "Sprite.vertex.glsl", "fragmentSource": "Sprite.fragment.glsl",
     "vertexDefines": [{"name": "Masked", "value": "true"}],
     "fragmentDefines": [{"name": "Masked", "value": "true"}, {"name": "AlphaTex", "value": "false"}]}
  ]
}
```

Every scalar in that format is a **signed 16 bit little endian** int - the only little endian
anything in the cache - and a string is one of those as a length followed by that many bytes, so
`-1` is null and `0` is the empty string and both occur. `ShaderProgramDecoder` /
`ShaderProgramEncoder` are the pair. The `program` and `slot` arrays are the Direct3D constant
register and sampler slot per technique; the OpenGL renderer ignores them and looks its uniforms up
by name, but they are the file's and they are kept. The permutation suffix on a `dx` source name
(`.0000000400000001`) is a packed decimal spelling of that technique's `#define` set and lives only
in the name, which is why the name had to be recovered rather than invented.

**Both archives pack back identically, all 112 file names are recovered - no file falls back to
`<file>.dat` - and none of the 24 program definitions needs a pristine copy.** As for index 30, the
file name hashes in `index.json` stay the truth: packing hashes each path's lower case form and
looks it up in `fileNames`.

### What these seven cost

Measured on the 727 cache on one 12-core machine (`AudioSourceTreeTest`), unpacking the seven
indices into a tree, packing that tree into a cache and verifying it against the shipped one:

| index | dir | codec | archives | files | tree size | exact | pristine |
|---|---|---|---|---|---|---|---|
| 4 | `synth_sounds` | `json-synth` | 10,084 | 10,084 | 33 MB | 10,084 | 0 |
| 6 | `midi_songs` | `midi` | 1,137 | 1,137 | 40 MB | 1,137 | 0 |
| 11 | `midi_jingles` | `midi` | 525 | 525 | 1.7 MB | 525 | 0 |
| 14 | `vorbis` | `vorbis` | 16,825 | 16,826 | 228 MB | 16,825 | 0 |
| 15 | `midi_instruments` | `jagpatch` | 247 | 247 | 92 KB | 247 | 0 |
| 30 | `dlls` | `dlls` | 33 | 33 | 20 MB | 33 | 0 |
| 31 | `shaders` | `shaders` | 2 | 112 | 159 KB | 2 | 0 |
| **total** | | | **28,853** | **28,964** | **336 MB** | **28,853** | **0** |

Unpack 13.3 s, pack 7.6 s, verify 6.2 s at full parity - 28,853 containers and 7 reference tables,
all identical - a no-op build 0.8 s, and a re-unpack writes nothing and touches no file. The one
thing that run cannot compare is the derived version table, which is a digest of *every* index's
reference table and so differs between a tree of seven indices and a cache of thirty-seven;
`WholeCacheSourceTest` is where that is checked, over all of them at once.

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

The whole-cache timings below were measured before the audio and native asset codecs landed, so
they are the cost of a tree whose indices 4, 6, 11, 14, 30 and 31 were still `.dat` files; what those
seven cost on their own is measured in *What these seven cost* above, and the shape of the change is
about 30 MB more tree for index 4's JSON against 10 MB less for the songs and jingles.

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
- A lossless form for index 15's sound bank patches, which would have to keep the raw columns and
  run lengths the client's own decode discards - see *midi instruments (index 15)*. Index 14's
  streams are the one case where there is nothing to reach for: an `.ogg` of them would be mostly
  invented and could not be checked back.
- Gameval-named file layouts (`config_obj/abyssal_whip.json`) with rename sync, as `cs2` has.
- Handing a codec the cache a tree is being *unpacked from*, the last thing `SourceCodec` does not
  offer - see *The `SourceCodec` interface* above. Packing needs no cache any more.


## Definition encoders

`DefinitionJsonCodec<T>` can only store a type as editable JSON when its encoder reproduces the
shipped file exactly - `encode(decode(bytes)) == bytes` for **every** file - otherwise the type has
to keep a `.pristine` copy beside every file it cannot rebuild. All 22 do, so none of them keeps one;
see "Definition files" for what the JSON looks like. Twenty-two types reach that bar for
the whole 727 cache: the seven opcode keyed types of indices 16 to 22, the fourteen decoded
archives of index 2, and index 3's interface components.

Three more encoders reach it outside that mechanism, because their formats are a fixed sequence
rather than an opcode list and so have no order to replay: `SynthSoundEncoder` (index 4, 10,084
files), `ShaderProgramEncoder` (index 31, 24 files) and `JagexMidi.toCompact` (indices 6 and 11,
1,662 files, which rebuilds a whole archive rather than a definition). Their measurements are in
*synth sounds*, *shaders* and *midi songs and jingles* above.

Three tests measure it, each against the shipped bytes rather than against a re-decode:

- `core/src/test/kotlin/world/gregs/voidps/cache/definition/DefinitionEncoderParityTest.kt` (indices 16-22)
- `core/src/test/kotlin/world/gregs/voidps/cache/config/ConfigEncoderParityTest.kt` (index 2)
- `core/src/test/kotlin/world/gregs/voidps/cache/definition/InterfaceEncoderParityTest.kt` (index 3)

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

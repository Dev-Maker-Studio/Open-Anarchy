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
| bzip2 containers (3,796 archives + 13 reference tables) reproduce with **libbzip2 1.0.8** at block size 1, and with nothing else on the JVM | `BZip2Encoder` is a faithful Kotlin port of libbzip2's compressor, verified against every bzip2 container |
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
- `archives` has one line per archive, sorted by id. Fields, all omitted when default:
  - `version` - the reference-table version (full int; the container trailer is its low 16 bits).
  - `compression` - `none` | `bzip2` | `gzip` of the archive's container.
  - `gzipOs` - the gzip header OS byte when not 0.
  - `nameHash` - the archive's name hash, only in indices whose table has names. Stored as the
    number: names are a display concern and never a source of hashes at pack time.
  - `file` - the single file's id when an archive has one file whose id is not 0.
  - `fileNames` - `{"<fileId>": <nameHash>}` for the two indices with file names (23, 31).
  - `xtea` - `[k0, k1, k2, k3]` for encrypted map archives.
  - `pristine` - `{"<fileId>": "<sha256 of the editable file>"}`, see below.

Everything else (CRC, whirlpool, sizes, the file id list of a multi-file archive) is derived from
the data files.

### Codecs

Each index is unpacked by one codec (`world.gregs.voidps.cache.source.codec`). The codec owns
the file layout under the index directory and the two directions, `unpack(group) -> files` and
`pack(files) -> group`. A group is the decompressed archive: for one file, the file; for several,
the JS5 multi-file layout.

| codec | used by | layout |
|---|---|---|
| `RawArchiveCodec` | single-file indices with no better codec | `<archive>.dat` |
| `RawFilesCodec` | multi-file archives with no better codec | `<archive>/<file>.dat` (requires and always writes 1 chunk) |
| `VerbatimGroupCodec` | index 0 | `<archive>.grp` - the decompressed group verbatim, chunk table and all |
| `DefinitionJsonCodec<T>` | every definition type with a byte-exact encoder | `<definitionId>.json` (id = archive/file packed the way the decoder does) |
| `ConfigCodec` | index 2 | one sub-directory per config archive, named by `Config` (`inv`, `param`, `varp`...), each with its own codec |
| `Cs2SourceCodec` | index 12 | the TypeScript layout `cs2 decompile-all` writes (`<category>/<name>.ts`), provided by the `cs2` module |

JSON is pretty-printed, keys in declaration order, ids numeric. Names come from the gameval
catalogs at display time; the data never duplicates them.

### The pristine fallback

`unpack` re-encodes every file it decodes and compares. When the bytes differ, the original bytes
are written next to the editable file as `<name>.pristine` and the editable file's SHA-256 is
recorded under `pristine` in `index.json`. `pack` emits the pristine bytes while the editable
file still hashes to that value and compiles the editable file otherwise (deleting the stale
sidecar). `cache status` reports the pristine count per type so encoder gaps are visible; the
goal is zero, and the count can only shrink as encoders improve or files are edited.

This is what lets a normalising compiler (the CS2 decompiler drops dead code by design, so ~1,000
scripts recompile to different but equivalent bytes) and a not-yet-exact encoder coexist with the
parity guarantee.

## The packed build

`CacheSource.build(tree, outputDir)` produces a normal packed cache in `outputDir`:

1. Read `outputDir/manifest.json` if present: the tree path, format version, and for every input
   file its size, mtime and content hash, and for every archive the container CRC it produced.
2. Walk the tree in parallel, stat every file. Size and mtime unchanged means unchanged; anything
   else is hashed and compared. Removed and added files are noticed. The walk over ~250k files
   takes about a second.
3. Map changed paths to `(index, archive)` through the index's codec, and diff `index.json`
   per archive for metadata-only changes (a version bump, a key change).
4. For each affected archive, in parallel: pack the group, compress with the recorded
   compression, encrypt, write the container into the store (`FileStore`, reusing the old
   sector chain when the new container fits, appending otherwise), and update the archive's
   entry in the in-memory reference table.
5. For each affected index: encode the reference table, write it to index 255. Rebuild the
   RSA-signed version table. Write the manifest last.

No changes: step 2 finds nothing and the build returns in the time it takes to stat the tree.
A first build from an empty directory packs everything, compressing in parallel.

## Read-write caches

`FileCache` can be opened writable (`FileCache.load(path, writable = true)`); `Cache.write`
then repacks the group, recompresses with the archive's existing compression, bumps the version,
rewrites the reference table and the version table. This is the API editors use to save into a
packed cache directly; it replaces the map editor's `CacheArchiveWriter`. Both paths share
`FileStore`, `ReferenceTable` and `Container`.

## Configuration

| variable | default | meaning |
|---|---|---|
| `CACHE_SOURCE_PATH` | unset | a source tree. When set, the server builds it into `CACHE_BUILD_PATH` on startup and serves that |
| `CACHE_BUILD_PATH` | `./data/cache` | the packed build output (gitignored) |
| `CACHE_PATH` | `../cache/` | a prepacked cache, used when `CACHE_SOURCE_PATH` is unset |
| `GAMEVAL_PATH` | `<CACHE_SOURCE_PATH>/gamevals` when set, else `./cache-source/gamevals` | the catalogs (the only copy) |

The lobby and the worlds use the same switch; a world that needs its own cache in future points
`CACHE_SOURCE_PATH` at its own tree.

## Tools

```
tools cache unpack <tree> [packedDir]     unpack a packed cache (default CACHE_PATH) into a tree
tools cache pack <tree> <outDir>          full pack
tools cache build <tree> <outDir>         incremental pack (what the server runs on startup)
tools cache verify <tree> <packedDir>     container-level parity report against a packed cache
tools cache status <tree>                 codec per index, pristine counts, stale sidecars
```

## Future work the design leaves room for

- World servers hosting their own JS5 from their own tree (`CACHE_SOURCE_PATH` per world; the
  lobby's world flag decides whether the client re-inits JS5 against the world).
- Typed codecs for more indices (sprites as PNG, maps as JSON for the map editor, models). Each
  is one codec class plus a byte-exact proof; the pristine fallback carries the rest meanwhile.
- Gameval-named file layouts (`config_obj/abyssal_whip.json`) with rename sync, as `cs2` has.

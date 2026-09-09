# Porting RS3 assets into the 727 cache

The skilling urns (`docs/reference/rs3-urns.json`) were the first assets brought across from the
RS3 revision-949 tree, and the tooling written for them is the path for the next one. The RS3 tree
is `~/darkan/darkan-3-undercut/unpacked-cache`, read-only; everything written lands in
`cache-source/` under next-free ids.

## What crosses, and what does not

Same-id models exist in both caches (the 135 models of the 26 urn sets already in 727), and they
are the calibration corpus: converting them from the RS3 side and diffing against the shipped 727
files is how the converter is proven. Measured on that corpus:

| RS3 `models_rt7/<id>.glb` | 727 |
|---|---|
| `POSITION` integers, split per normal and per UV | the same integers with `y` negated, welded by position and skin label |
| `extras.rs.streamB` (ushort per vertex) | the HSL16 face colour, exact on every face |
| `extras.rs.submeshes[i][1]` | the face priority |
| `extras.rs.streamA` / `streamC` (worn models only) | `vertexSkins` / `faceSkins` |
| `extras.rs.submeshes[i][2]`, an RS3 material id | a different 727 material id: RS3 re-mapped every face when it retextured, so the ids are crosswalked, never copied |
| `TEXCOORD_0` | nothing: the shipped 727 urns project their body textures cylindrically (mapping type 1) and their trim through one planar triangle whose corners are three synthesised vertices, so the projection block is taken from a template learned per (role, material) on the corpus |
| `bones`, `weights`, `normals` | nothing (RT7 skeleton; 727 lights from face normals it computes itself) |

Face order differs between the two (RS3 concatenates submeshes), so faces are paired by their
vertex-position triple.

## The tools

All under `tools rs3port` (headless, never opens the packed cache):

```
rs3port materials <rs3-tree> <727-tree> [spec.json]
rs3port model <rs3-tree> <727-tree> <rs3Id> --id <727Id> --role <role> [--out <dir>]
rs3port calibrate <rs3-tree> <727-tree> [spec.json] [--out <dir>]
rs3port urns <rs3-tree> <727-tree> [spec.json]
icon <objId>[,<objId>...] <out.png> [--sheet] [--wiki <dir>] [--glb <id>=<path>]
```

- `materials` learns `docs/reference/rs3-material-crosswalk.json` from the corpus: per-face
  (RS3 material -> 727 material) counts, unanimous on every urn material, plus the projection
  template per role and material. `docs/reference/rs3-material-overrides.json` wins over the
  table and is where a material with no precedent is decided by hand, with its reason.
- `calibrate` is the acceptance gate. On the 135 shared models: geometry exact on 134 (the
  other has one vertex RS3 re-rounded), colours, materials, skins and mapping types 100%,
  priorities 96.65% (RS3 says "all 0" for 20 models that 727 ships as uniform 4 or uniform 0; a
  uniform priority orders nothing, 4 is taken), and the full projection block exact on 19 models -
  the rest split one material across two cylindrical pointers with different rotations that
  nothing in RS3 distinguishes. The icons rendered from the converted copies differ from the
  shipped ones only by sub-visible texture phase noise.
- `urns` is the batch importer driven by `docs/reference/rs3-urns.json`: it converts the models,
  writes the objs from the same-role 727 mining-tier-1 files, clones the same-tier mining seqs with
  `rightHandItem` moved, writes the crafting-guide structs and enum rows, and registers every id
  in the catalogs and `gamevals.d.ts`. It refuses to run when an allocated id is not exactly one
  past the tree's ceiling, so a hole can never appear, and re-running rewrites the same bytes.
  `docs/reference/rs3-urns-ids.json` is the RS3 -> 727 id map it wrote, the provenance of every
  catalog name.
- `icon` renders inventory icons at 36x32 through the obj's own camera fields (`--wiki` fetches
  the RS3 wiki image beside each for comparison; `--glb` substitutes a model file for a cache
  model, which is how converted and shipped copies are compared).

## Porting the next asset

1. Write a spec: the RS3 source ids, the roles, and the 727 ids allocated next-free in a fixed
   order (`docs/reference/rs3-urns.json` is the shape). RS3 ids are references only.
2. If the asset uses a material the crosswalk has not seen, decide it in the overrides file with
   a reason, after looking at both textures (`tools texdump <id>` on the 727 side).
3. If the asset family has no shared models to learn a projection template from, the planar
   fallback in `ModelConverter` solves one triangle per face from the RS3 UVs; check the result
   in `tools model <id>` before trusting it.
4. Run the importer (or `rs3port model` per model), pack, `cache verify`, and compare the
   per-index lines against the tree's baseline: the only lines that may change are the archives
   the new ids land in.
5. Register the server side against the generated constants; `UrnTableTest` is the shape of the
   test that proves names, options, guide rows and animations against the cache.

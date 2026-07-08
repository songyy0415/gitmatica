# LVC git repo structure

Each LVC project is a git repo.

## Directory structure

- lvc.json
- indexes/
- objects/
- .git/

## Files

`lvc.json`:

Versioned project metadata, site definitions, and sub-region definitions.

Placement origin:

Stored in Litematica's schematic placement state, not in the Gitmatica project repository.

e.g.
```json
{
  "master_origin": [-18, -61, -12]
}
```

index.json:

Records the subregion definitions and other metadata.

**Subregion bounding box changes**:
Use this file to detect any bbox changing.
If bbox changed, when merging, fir prompt the user to choose which bbox to use, and then update
the index.nbt block coordinates accordingly.

e.g.
```json
{
  "lvc_version": 1,
  "name": "Unnamed",
  "sub_regions": [
    {
      "name": "Unnamed",
      "pos1": [0, 0, 0],
      "pos2": [6, 4, 5],
      "size": [7, 5, 6]
    }
  ]
}
```

index.nbt, *.nbt:

subregions. stored im vanilla structure block format.

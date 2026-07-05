# Future Storage Notes

## Raw Storage Decision

Versioned hash indexes and chunk objects now use raw deterministic files instead of pre-compressed storage.

Profiling with matching Yams Storage histories showed raw files are larger in the checked-out working tree, but pack better after `git gc` because Git can delta-compress similar blobs across history.

Current direction:

- Keep raw deterministic `.lvcidx`.
- Keep raw deterministic `.lvcchunk`.
- Use packed Git size, not loose working-tree size alone, when evaluating history storage.

Optional future work: benchmark chunked/page index formats only if `.lvcidx` files become a measurable bottleneck.

Gitmatica
==============

Gitmatica is a Litematica fork that adds Git-backed version control for Minecraft builds, with in-game commits, branches, merging, restores, remotes, diff viewing, and inventory-aware change tracking. It is built for technical builds where exact block, state, and container changes matter. Project history is stored in normal Git repos, making collaboration, storing to git servers and rollback workflows easier.

Gitmatica is currently pre-alpha. Release versions use SemVer for Gitmatica itself, independent from the upstream Litematica base version. Pre-alpha builds use `0.1.0-prealpha.N`; first public alpha builds should use `0.1.0-alpha.N`.

## License

Gitmatica is licensed under LGPL-3.0-only. Portions of the inventory diff
implementation reference TechUtils, which is released under the Unlicense.

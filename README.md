Gitmatica
==============

Gitmatica is a Litematica fork that adds Git-backed version control for Minecraft builds, with in-game commits, branches, merging, restores, remotes, diff viewing, and inventory-aware change tracking. It is built for technical builds where exact block, state, and container changes matter. Project history is stored in normal Git repos, making collaboration, storing to git servers and rollback workflows easier.

Gitmatica is currently pre-alpha. Gitmatica release versions use SemVer independent from the upstream Litematica base version: pre-alpha builds use `0.1.0-prealpha.N`, and first public alpha builds should use `0.1.0-alpha.N`. Release jar names use the Gitmatica version, for example `gitmatica-26.2-0.1.0-prealpha.1.jar`. While the Fabric mod id remains `litematica`, the loader-facing version in `fabric.mod.json` includes the compatible Litematica base prefix, for example `0.28.3-gitmatica.0.1.0-prealpha.1`.

## License

Gitmatica is licensed under LGPL-3.0-only. Portions of the inventory diff
implementation reference TechUtils, which is released under the Unlicense.

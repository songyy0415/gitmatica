Gitmatica Notices
=================

Gitmatica is a standalone Litematica addon and is licensed under LGPL-3.0-only.

The initial addon implementation was migrated from the Gitmatica Litematica
fork. Litematica and MaLiLib remain separate runtime dependencies and are not
bundled in this jar.

Portions of the inventory diff implementation reference TechUtils, which is
released under the Unlicense.

Gitmatica bundles the following runtime libraries as Fabric/Loom nested jars so
Git-backed project operations work without requiring a separate system Git
installation:

- Eclipse JGit, licensed under the Eclipse Distribution License 1.0. Its
  upstream notices are preserved inside the nested jar.
- JavaEWAH, licensed under the Apache License 2.0. Its upstream metadata is
  preserved inside the nested jar.

# Conflict Resolution

Gitmatica first merges sub-region definitions, then merges tracked content.

- Sub-regions are matched by their exact names.
- Renaming a sub-region is treated as deleting the old name and adding the new
  name.
- Definition changes to different named sub-regions merge automatically.
- Different definition changes to the same named sub-region create a structural
  conflict. The selected Base, Incoming, or Yours definition is used for every
  structural conflict.
- The merged definitions establish the final tracking masks.
- Blocks and block entities are then merged per position with the normal
  three-way rules, but only inside those final masks. A conflict choice selects
  only conflicting payloads; non-conflicting changes inside the same sub-region
  still merge automatically.

The Change Viewer remains focused on physical unsaved changes. It does not add
rows for unsaved sub-region metadata edits.

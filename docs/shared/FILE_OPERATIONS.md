# File operations and data safety

Applies to **every product**. The products share this *contract*, not code: each implements it
in its own stack and proves it with its own tests.

A photographer culling a shoot trusts the tool with original camera files on an SD card, a local
drive or a Storage Access Framework document tree. Filing, trashing and undoing must never silently
overwrite or destroy a file the photographer did not ask to remove.

## The contract

### 1. Never overwrite on transfer into Selection

A transfer (Copy or Move) into `Selection/` — or any configured destination folder — **never
overwrites** an existing file.

- **Bundle-wide collision suffix.** Before transferring a candidate and its companions (primary
  image, RAW/JPEG twin, `<file>.xmp` / `<stem>.xmp` sidecars, and `<stem>-Edit.*` /
  `<stem>_Edit.*` / `<stem>-Enhanced-NR.*` derivatives), the product checks whether *any* member's
  target path in the destination folder (including `RAW/` or `JPEG/` subfolders when file sorting
  is enabled) is already occupied.
- **Shared numeric suffix.** If any member collides and the source and destination are not the exact
  same canonical file, the **entire bundle** receives the lowest integer suffix `-N` ($N \ge 2$)
  inserted immediately before the file extension for which every member of the bundle is free:
  - Primary and twin: `IMG_0001-2.CR3`, `IMG_0001-2.JPG`
  - Sidecars: `IMG_0001-2.CR3.xmp` (file-specific) or `IMG_0001-2.xmp` (stem-specific)
  - Edit derivatives: `IMG_0001-2-Edit.tif` (inserted before `-Edit` / `_Edit` / `-Enhanced-NR` so
    Lightroom and Capture One stem association survives)
- **No overwrite primitives.** File transfers must use primitives that fail when the destination
  already exists (`FileManager.moveItem` / `copyItem` on macOS, `O_CREAT | O_EXCL` or existence
  checks under the directory lock on Linux, and single-query collision resolution plus created
  display-name verification on Android SAF). Removing or truncating an existing destination file to
  make room for a transfer is prohibited.
- **Self-move guard.** If source and destination resolve to the exact same canonical file, the
  operation is an idempotent no-op.

### 2. Verify destination before deleting source on Move

A Move operation may delete the source file **only after** the destination file is confirmed
present and complete.

- **Same-filesystem atomic rename.** When source and destination reside on the same filesystem or
  document authority and the platform supports atomic move (`rename` on POSIX/APFS, or
  `DocumentsContract.moveDocument` when the SAF provider advertises `FLAG_SUPPORTS_MOVE`), the
  atomic move is used directly after collision-free naming.
- **Cross-volume / stream-copied move.** When a move copies bytes across filesystems or through a
  stream:
  1. Write the destination file under its collision-free name (and `fsync` on POSIX cross-mount
     transfers).
  2. Verify that the destination exists, its recorded display name matches the requested name (on
     SAF providers), and its byte size equals the source byte size ($\text{size} > 0$).
  3. Unlink the source file **only after** verification succeeds for the file (or for the entire
     staged bundle on desktop two-phase transactions).
- If verification fails, any newly created partial destination file from the failed transfer is
  cleaned up and the original source file remains untouched.

### 3. Undo never deletes a file it did not create

Undoing a file operation must never overwrite or delete a pre-existing file.

- **Copy undo.** Where Copy is undoable (macOS Desktop, Linux Desktop), Undo removes **only** the
  exact destination replica(s) created by that Copy operation (using the recorded destination paths,
  including any `-N` suffix assigned at copy time). Original source files are never touched. On
  Android Desktop, Copy is deliberately not undoable because the original file was never removed.
- **Move and Trash undo.** Undoing a Move or Trash operation restores the bundle to its recorded
  original directory:
  - If every original path in the bundle is free, the bundle is restored under its original names.
  - If any original path is now occupied by a file created or imported after the operation, the
    existing file at that path is **never deleted or overwritten**. Instead, the restored bundle is
    assigned a shared collision-free suffix (`-2`, `-3`, …) in the original directory so companion
    pairing stays intact.
- **Undo of a partial Move.** A Move that ended as a partial result (§4: the destination copy was
  verified but the original could not be removed) is undone per member. For a member whose original
  still exists, Undo deletes **only the destination copy that operation created** (a copy made by
  that very Move, at its recorded path) and leaves the original untouched: restoring the copy next
  to the surviving original would only create a `-N` duplicate in the source folder. Members whose
  original is gone are restored as in a complete Move. If a member's original has disappeared since
  (the user removed it), its copy is the only one left and is restored, never deleted.
- **Trash path recording.** Trash operations record the exact trashed location (the URL returned by
  `FileManager.trashItem` on macOS, or the exact `files/` and `.trashinfo` paths in the mount's or
  home FreeDesktop Trash on Linux) at the moment of trashing, and Undo restores exclusively from
  that recorded location rather than guessing by filename or prefix in the trash directory.

### 4. Partial failure is reported as partial, never as silent success

When a multi-step or multi-file operation succeeds only in part, the product must report the exact
outcome rather than claiming full success or destroying verified data to force a rollback.

- **Source removal failure after verified copy.** Once a destination copy is verified during a Move,
  a subsequent failure to remove the source file (for example, a read-only SD card lock or transient
  SAF provider error) **never** deletes the verified destination copy. Instead, the operation
  completes as a partial result and informs the user that the photo was copied to the destination
  folder while the original could not be removed.
- **Rollback failure.** When a multi-file Move or Copy fails midway and the compensating step (moving
  a member back, removing a staged copy) fails as well, the file that is still in the destination
  folder is never forgotten: the operation ends as a partial result whose undo record covers exactly
  those files, and the user is told they stayed in the destination folder. A message that says the
  operation was rolled back is shown only when every compensating step succeeded. Undo then moves
  such a member back (Move) or deletes the stray copy (Copy).
- **Companion bundle partial failure.** On platforms where each file in a companion bundle transfers
  through separate provider calls (Android SAF), any companion that fails leaves its source intact
  and is reported in a partial-failure summary (`Moved K of N … (M failed)`), while successfully
  moved members remain at the destination.

## User-facing feedback templates

To keep notifications scannable across workstations, tablets and phones without modal interruption,
products use these concise status templates (substituting the configured destination folder name
for `«folder»`):

| Outcome | Notification text |
|---|---|
| Move (no collision) | `Moved to «folder»` |
| Copy (no collision) | `Copied to «folder»` |
| Move (collision-suffixed) | `Moved to «folder» as «stem»-«N».«ext»` (or `(+«K» related)` when companions are included) |
| Copy (collision-suffixed) | `Copied to «folder» as «stem»-«N».«ext»` (or `(+«K» related)` when companions are included) |
| Undo Move / Trash (original name occupied) | `Restored as «stem»-«N».«ext» (original name was in use)` |
| Move where source could not be removed | `Copied to «folder» — original could not be removed` |
| Move whose rollback failed (members stayed in the folder) | `Move interrupted — some files of «name» stayed in «folder»/ and could not be put back` |
| Copy whose cleanup failed (stray copies in the folder) | `Copy interrupted — some copies of «name» stayed in «folder»/ and could not be removed` |
| Partial companion bundle transfer | `Moved «succeeded» of «total» to «folder» («failed» failed)` / `Copied «succeeded» of «total» to «folder» («failed» failed)` |

# Changelog

## 0.6.1

- **Air no longer turns into stone.** A `.schem` loaded into Litematica keeps its own palette
  order, and once saved as a `.litematic` Litematica read it back with every block shifted one
  place: all the air became one block (stone, in the reported case) and the rest came back as
  their neighbours. Files already saved this way load correctly again, and saving rewrites them
  so plain Litematica reads them too.

## 0.6.0

- **Shared schematics.** Shift-click the share button, pick players, and they sync with one
  click. Every edit reaches the whole group, including Litematica's own Schematic Edit mode and
  placement moves. Undo and redo are the group's. Needs the Paper plugin, released alongside.
- **Edit who has a share**, with a search box and invites by name for offline players.
  Stopping or ending a share leaves everyone an ordinary copy.
- **Material list counts are right after a change.** They were counted while Litematica was
  still rebuilding the schematic, so a big replace could show a fraction of the real number.
  Litematica's "Scheduled task added" popup no longer fires after every change.
- **Moved entities stay moved.** Box move, box stack and trim moved entities on screen only;
  saving put them back where they were.
- **No more floating mobs** left behind when a stack preview reached a neighbouring chunk and
  was taken back.

## 0.5.0

- **Box tools**: stack and move a box inside the schematic, from the box screen or in the world
  with the *Box in schematic* tool mode. Stacking in several directions makes a grid, the
  schematic grows to fit, and in-world edits stay a preview until Litematica's Execute hotkey.
- **Redo**, and a settings screen with malilib hotkeys (also in Mod Menu).

## 0.4.0

- **Replace in these layers**: confine a Replace or palette change to a layer selection.
- **Top or bottom** for the slabs a swap creates. Double slabs are left alone.
- **Normalize** bark blocks to stand upright.
- All buttons moved to the material list's title row.

## 0.3.0

- **Shrink to contents** on save, opt-in.
- Entities go with the blocks when layers are cut.

## 0.2.0 and earlier

- Replace keeping state, the block picker with rendered previews, palette changes, remove
  layers, undo, and saving to a tagged working copy.

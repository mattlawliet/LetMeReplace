# Changelog

## 0.6.2

- **Box previews show exactly what Execute will do.** Moving or stacking with the scroll wheel
  could leave some of the unmoved blocks drawn in the preview until Execute. The data was right;
  Litematica's chunk redraw lost a race between taking the old preview off and putting the new
  one on. The schematic is now redrawn once more after every change, once Litematica goes quiet.
- **Change palette** marks your choice with a ring instead of greying it out like a disabled
  button. It counts blocks, not block types ("Change 43 blocks"), and Enter applies.
- **Replace picker:** a long block state wraps instead of losing its end. Enter with nothing
  typed no longer picks Delete (air).
- **Normalize** asks "Turn 50 upright" and names blocks the way the material list does.
- **Real checkboxes** on Save and in the share picker. Share stays greyed out until someone is
  ticked, and the share search box's cursor no longer sits in the corner of the screen.
- **Material list:** a proper undo arrow; the buttons show hover and greyed-out states; a long
  placement name is shortened instead of running under the buttons.
- **Box in schematic HUD:** shorter lines and readable key names ("Ctrl+Alt+E"), clear of the
  hotbar and Litematica's own HUD at large GUI scales.
- Wide screens keep the picker and palette centred instead of stretched; Box puts Cancel last
  like every other screen; the Layers screen no longer flashes Litematica's layer-mode message.

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

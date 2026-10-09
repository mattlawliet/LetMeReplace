# LetMeReplace

A client-side Fabric mod for **Minecraft 26.2** that edits a schematic Litematica has loaded,
in place, from its material list. Your original file is never overwritten: changes go to a
working copy when you save.

## What it does

- **Replace a material everywhere** with one click on its material-list row. Each block keeps
  its rotation and state: oak stairs facing north become spruce stairs facing north. Positions
  that can't carry over are skipped and counted on the confirm screen, never silently changed.
  Chests and other block entities say how many items a swap would drop.
- **Change a whole palette** at once: wood type, colour, or copper oxidation (waxed stays waxed).
- **Normalize** bark blocks (`*_wood`, `*_hyphae`) so they all stand the same way up.
- **Remove layers** along any axis with a live preview, using Litematica's own layer controls,
  or **replace only inside** a layer selection.
- **Stack and move a box** inside the schematic, from a screen or in the world with the
  *Box in schematic* tool mode and the scroll wheel. The schematic grows to fit.
- **Undo and redo** for every change, with Ctrl+Z / Ctrl+Shift+Z by default.
- **Share a schematic** with other players on a server running the LetMeReplace plugin (below).

## Requirements

- Minecraft 26.2, Fabric Loader 0.19.3+, Fabric API
- [Litematica](https://modrinth.com/mod/litematica) 0.28.x and [malilib](https://modrinth.com/mod/malilib) 0.29.x
- Optional: [Mod Menu](https://modrinth.com/mod/modmenu), for the settings page

## Using it

Open a placement's **material list** in Litematica. Each row gets a **Replace** button, and the
title row gets LetMeReplace's buttons: palette, normalize, undo, box, remove layers, share and
save. Hover any of them for what it does. Keys are malilib hotkeys, set in the settings screen.

## Sharing schematics

With the server plugin installed, players who have LetMeReplace can work on one schematic
together.

1. **Shift-click** the share button on the material list and tick who gets it. A plain click does
   nothing, so you can't share by accident. You can search, and type a name to invite someone
   who is offline.
2. They get a **[Sync]** link in chat. One click loads the schematic where you have it placed.
3. From then on, every edit by anyone in the group reaches everyone: replaces, palettes, layer
   cuts, box edits, Litematica's own Schematic Edit mode, and moving the placement.
   **Undo is shared too**: Ctrl+Z takes back the group's newest edit, whoever made it.
4. Click the green share button to change who has it (owner) or stop syncing (member). Every
   copy stays behind as an ordinary schematic.

Only files in `schematics/lmr_sync/<server>/` are ever synced or overwritten. Sharing copies your
schematic there, so the file you shared from is never touched.

### Server plugin

Drop `LetMeReplace-server-<version>.jar` into a **Paper 26.2** server's `plugins/` folder. The mod
and the plugin must be the same version.

| Permission | Default | Allows |
|---|---|---|
| `lmr.share` | everyone | sharing a schematic |
| `lmr.sync` | everyone | accepting a share |
| `lmr.edit` | everyone | editing, undo and moving the placement |
| `lmr.admin` | op | reserved for managing all shares |

Shares and their last 100 versions are kept in `plugins/LetMeReplace/shares/`.

## Building

```sh
./gradlew build                                        # the mod, in build/libs/
PAPER_SERVER=~/paper-26.2-server server/build.sh       # the plugin, in server/out/
```

`PAPER_SERVER` is any Paper 26.2 server folder that has been started once.

## License

[LGPL-3.0-only](LICENSE)

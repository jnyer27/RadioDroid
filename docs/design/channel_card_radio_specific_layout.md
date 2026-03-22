# Channel card: radio-specific block (main list)

Design reference for the main channel list row (`item_channel.xml` / `ChannelAdapter`).  
**Illustrative only** — real keys and values come from `channel.extra` at runtime.

## Layout goal

- **Row 1 (universal):** Channel number, frequency, name, tones, duplex, power, optional slots, drag handle — unchanged.
- **Row 2 (radio-specific):** Caption + a **vertical list** of `key: value` lines (one `TextView` per extra). This avoids width-dependent multi-column math during `RecyclerView` bind, which often saw `width == 0` until a later layout pass and produced missing or wrong columns until configuration change (e.g. rotation).

## Display rules

- **Ordering:** Keys appear in `channelExtraSchema` order first, then any remaining keys alphabetically (same as before).
- **Styling:** Secondary text color, 11sp, up to 2 lines with end ellipsize per line.

## Earlier multi-column design

Older builds used 1–N horizontal columns based on `floor(usableWidth / minCellWidth)`. That is **removed** from the main list for reliability; the **channel editor** still uses full schema-driven controls.

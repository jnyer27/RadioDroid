# Channel card: main list (compact)

Design reference for the main channel list row (`item_channel.xml` / `ChannelAdapter`).  
Real keys and values come from `channel.extra` at runtime.

## Layout goal

- **Row 1:** Channel number, **name** (prominent), **inline badges** (power, mode, duplex, then user main-display slots that are not redundant), drag handle.
- **Row 2:** Frequency (primary readout).
- **Row 3 (optional):** Single-line tone summary (`T: … · R: …`).
- **Row 4 (optional):** One dense **radio-specific** summary `TextView` (max 2 lines, ellipsize end) — no per-key vertical stack.

## Radio-specific summary rules

- **Groups:** `Group 1`–`Group 4` / `group1`–`group4` values are merged into one segment, e.g. `Groups: A, G`. Empty or `None` group slots are omitted.
- **Ordering:** `Groups: …` first when present; remaining extras follow `channelExtraSchema` order, then any other keys alphabetically.
- **Booleans:** `True`/`False` (and busy-lock–style keys) display as `On`/`Off` (e.g. `BusyLock: Off`).
- **Noise:** Blank values and `None` are omitted from the summary line.
- **Separator:** Middle dot `·` between segments (with spacing).

## Earlier designs

Vertical one-line-per-extra and multi-column layouts were replaced by this compact summary to improve information density while avoiding fragile width-dependent column math during `RecyclerView` bind.

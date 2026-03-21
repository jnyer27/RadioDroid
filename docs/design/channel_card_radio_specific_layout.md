# Channel card: radio-specific responsive multi-column block

Design reference for the main channel list row (`item_channel.xml` / `ChannelAdapter`).  
**Illustrative only** — real keys and values come from `channel.extra` and the groups summary at runtime.

## Layout goal

- **Row 1 (universal):** Channel number, frequency, name, tones, duplex, power, optional slots, drag handle — unchanged priority.
- **Row 2 (radio-specific):** Caption + a **multi-column** region that reflows with available width instead of a single horizontal line of text.

## ASCII wireframes

Conceptual logical widths; fold devices vary by OEM.

### A — Narrow / fold closed (~360–380dp)

One column (stacked lines):

```
+------------------------------------------------------------------+
| #12  462.5625 MHz        T67.0 / R67.0   DUP PWR S1 S2      [::]|
|      My Channel Name                                            |
|              [ Radio-specific ]                                 |
|              Groups: (summary)                                  |
|              extra_key_1: value                                 |
|              extra_key_2: value                                 |
|              extra_key_3: value                                 |
+------------------------------------------------------------------+
```

### B — Standard phone portrait (~390–430dp)

Two columns (illustrative reflow):

```
+------------------------------------------------------------------------+
| #12  462.5625 MHz           T67.0 / R67.0    DUP PWR S1 S2        [::]|
|      My Channel Name                                                   |
|         [ Radio-specific ]    Groups: …     |    extra_key_4: …        |
|                               extra_key_1   |    extra_key_5: …        |
|                               extra_key_2   |                          |
|                               extra_key_3   |                          |
+------------------------------------------------------------------------+
```

### C — Large phone / small tablet / wide front (~480–600dp)

Three columns (illustrative):

```
+----------------------------------------------------------------------------------+
| #12  462.5625 MHz              T67.0 / R67.0       DUP PWR S1 S2            [::]|
|      My Channel Name                                                             |
|    [ Radio-specific ]   Groups: …  |  extra_2: …  |  extra_4: …                  |
|                         extra_1    |  extra_3: …  |  extra_5: …                  |
+----------------------------------------------------------------------------------+
```

### D — Tablet / fold inner open (~700–900+dp)

More columns or one compact row:

```
+----------------------------------------------------------------------------------------------------------+
| #12  462.5625 MHz                         T67.0 / R67.0              DUP PWR S1 S2                  [::]|
|      My Channel Name                                                                                     |
| [ Radio-specific ]  Groups …  |  k1:v1  |  k2:v2  |  k3:v3  |  k4:v4  |  k5:v5                         |
+----------------------------------------------------------------------------------------------------------+
```

If cells do not fit at the configured minimum width, additional **wrap lines** stay inside the radio-specific region only.

## Width rules (implementation)

- Measure **usable width** for the column container (caption uses fixed space; remainder gets `layout_weight`).
- `columnCount = clamp(1, maxColumns, floor(usableWidth / minCellWidthDp))` (convert `dp` to px with display density).
- Recompute on **configuration change** (rotation, fold, window resize).
- **Dedupe:** When the groups summary (from `Channel.group1`–`group4`) is non-empty, omit `extra` entries with keys `group1`–`group4` so group membership is not shown twice.

## Risks

- Long values: use `maxLines` + `ellipsize` on cells.
- Very many extras: grid grows vertically; acceptable for v1.
- **RecyclerView:** Deferred `post { … }` layout for the radio-specific block must not run after the row is rebound to another channel (stale closure). Use a per-bind layout token + `bindingAdapterPosition` / channel-number checks before applying columns.
- **Zero-width passes:** On some fold/narrow layouts the column container can measure `width == 0` briefly; cap blind reposts and fall back to one pre-draw retry or a single-column layout so the block does not stay empty.

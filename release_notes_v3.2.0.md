## RadioDroid v3.2.0

### Highlights

- **Edge-to-edge + display cutout** — Root window padding now uses **system bars ∪ display cutout** insets (not status/navigation alone), so toolbars and content stay below **punch-holes, notches, and foldable front displays** (e.g. Galaxy Z Fold) instead of overlapping camera islands and status icons. **Radio select**, **CHIRP CSV import**, and **Customize main screen** now use the same **edge-to-edge** setup as the rest of the app. Theme sets **`windowLayoutInDisplayCutoutMode`** to **`shortEdges`** on API 27+ with insets applied in code.
- **User guide** — Documents **multi-select**, the **radio-specific field** bulk action (tag icon), and **Search → Select All**; clarifies **Memory.extra** on the main list and in the channel editor.

### Install

Download **`app-release.apk`** and install on Android 7.0+.

### User guide

- **Online (MkDocs):** https://jnyer27.github.io/RadioDroid/
- **PDF:** file **`radiodroid-v3.2.0-userguide.pdf`** in Assets (uploaded by the *Update User Guide* workflow), or live at https://jnyer27.github.io/RadioDroid/user-guide.pdf
- **Source:** [`userguide.md`](https://github.com/jnyer27/RadioDroid/blob/main/userguide.md) in the repo

### Full context

[README](https://github.com/jnyer27/RadioDroid/blob/main/README.md) — features and architecture.

<!-- radiodroid-docs:userguide -->

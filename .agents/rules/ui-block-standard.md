# Project UI Block Standard — Drag-and-Drop, Shrink/Expand & Hide/Show

Every feature screen, hub tile, control menu, and device/monitor card ("block") added to **Google Screen Control and Cast** MUST implement the following standard container capabilities:

1. **Drag-and-Drop Reordering (`☰`)**:
   - Every top-level block/container MUST include a `☰` drag handle (or long-press drag) using Android `startDragAndDrop` + `OnDragListener` with neon cyan (`#00FFFF`) drop-target highlight.
   - Custom block order MUST be persisted in `SharedPreferences` as a JSON array of stable block IDs.
2. **Shrink / Expand (`▾` / `▸`)**:
   - Every multi-row block (menus, device cards, rule cards, thermostat cards) MUST support collapsing to a 1-line header bar and expanding back, persisted in `SharedPreferences`.
3. **Hide / Show (`Hide` / `Show`) + Carved-Out Restore Button**:
   - Every block MUST include a `Hide` / `Show` pill button in its header, persisted in `SharedPreferences`.
   - Every screen with hideable blocks MUST include a carved-out, always-visible **`Hidden Tiles (N)`** / **`Hidden Blocks (N)`** toggle button outside the hideable blocks so users can always restore any hidden block (including control menus or rule cards).
4. **Wrappable Screen Titles**:
   - Screen titles MUST be bold, generously sized (`20–24sp`), and allow up to 2 lines (`maxLines = 2`, `wrap_content`) so they never clip on narrow screens.

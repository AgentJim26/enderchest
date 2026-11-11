# EnderChest

A Paper 26.3 plugin that replaces the vanilla ender chest with a paged, searchable storage GUI.

## Features

- Right-clicking a vanilla ender chest opens the custom GUI instead of the vanilla inventory.
- 36 storage slots per page, up to 17 pages (612 slots total).
- First open clones the player's existing vanilla ender chest into the plugin's SQLite store.
- Three sort modes:
  - **Custom** — free arrangement; the search view is take-only.
  - **Ascending** — automatic A-Z sorting; search view supports insertion.
  - **Descending** — automatic Z-A sorting; search view supports insertion.
- Search sign: `Search:` on the first line, query on lines 2-4. Filters by material and display name.
- Upgrade UI: put Eyes of Ender in the empty slot. 1 eye = 1 page, max 17 pages. Accept consumes only the eyes actually used and returns the rest.
- SQLite storage at `plugins/EnderChest/enderchest.db`; no JSON.
- `/ec` and `/enderchest` command; permission `enderchest.use` (default: true).

## Build

Requires Java 25 and Maven.

```bash
mvn package
```

The shaded plugin jar is written to `target/EnderChest-1.1.0.jar`.

## Notes

- The vanilla sign editor always focuses its first line; the plugin strips a leading `Search:` prefix and also reads lines 2-4, so typing after the label or on the next line both work.
- PlugManX load/unload/reload has been tested, but a full server restart is recommended after upgrading the jar.

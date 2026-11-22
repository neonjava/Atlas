# Journal

Times are the machine clock (IST, UTC+5:30), format `YYYY-MM-DD HH:MM`.

- 2026-11-22 22:50 — Checked GitHub: neonjava/Atlas is already a GitHub fork of
  PaperMC/Folia, identical to upstream `ver/26.3.x`, with no `main` branch.
  Cloned it to the SSD, added `upstream` (push disabled), created `main` from
  `ver/26.3.x`, made it the default branch, enabled Issues, added topics, and
  enabled GitHub Pages (build type: GitHub Actions). The description was
  already set.
- 2026-11-22 22:55 — Confirmed from the repo: `gradle.properties` says
  `mcVersion=26.3`, and `build.gradle.kts` sets the Java toolchain to 25. The
  system JDK is OpenJDK 25.0.3, so no extra JDK is needed.
- 2026-11-22 22:58 — Detected the machine (see MACHINE.md). The SSD is exFAT;
  trying the build on it first.
- 2026-11-22 23:00 — Bot tool: mineflayer (MIT) is the usual choice, but its
  data package (minecraft-data 3.119.0) only goes up to Minecraft 26.1, so it
  cannot join a 26.3 server. A translation proxy (ViaVersion/ViaProxy) would
  add its own work in the middle of what we measure. Decision: write a small
  bot client in `tools/atlas-bot` that reads packet IDs from the server's own
  generated `packets.json` report, so it follows the server version without
  hand-copied tables.
- 2026-11-22 23:20 — First `applyAllPatches` on the SSD worked, but the disk
  guard then blocked the build: free space went from 180 GB to 83 GB. Cause:
  exFAT with 1 MiB clusters; 90k generated files used 97 GB for 1.3 GB of data.
  Deleted the generated (git-ignored) files, back to 180 GB free. Builds now
  run in git worktrees under `~/atlas-build` (see MACHINE.md). Tagged the
  untouched upstream commit `eca40f9` as `baseline-folia-26.3`.
- 2026-11-22 23:25 — The owner asked for every change to be committed and
  pushed to Atlas, with PRs opened on neonjava/Atlas for them to review and
  merge. So I do not merge PRs myself: I open them against Atlas `main`, keep
  CI green, and leave the merge to the owner. Work that depends on an unmerged
  PR is stacked on that PR's branch and says so in its description.
- 2026-11-22 23:25 — Folia's jar task in this version is `createPaperclipJar`
  (not `createMojmapPaperclipJar`); README fixed.

# Needs a human check

Things the overnight work could not check alone, or that need the owner's
decision. Newest last.

- **Real-client check of chunk loading.** All chunk numbers come from bots,
  which count a chunk as "there" when its packet arrives. A real client also
  has to build the chunk's mesh before it is visible. Please fly around on a
  real client once on baseline and on Atlas `main` and say whether it feels
  smoother.
- **Merging PRs.** As asked, PRs are opened on neonjava/Atlas and left for
  review; none are merged by the automation.

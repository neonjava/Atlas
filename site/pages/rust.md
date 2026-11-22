# What Rust helped, and what it did not

Rust is only used where three things are true: the work is heavy, it does
not need to touch the rest of the server while it runs, and a measurement
shows that crossing from Java to Rust (calling it and copying data to it)
costs less than it saves.

**So far: nothing.** No Rust code has been added yet, because the baseline
measurements and profiles that decide where Rust could help are still
running. This page will list every attempt, the ones that won and the ones
that lost, with their numbers.

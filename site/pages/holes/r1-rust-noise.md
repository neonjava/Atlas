# Hole R1: Perlin noise in Rust

Status: open

## What we saw

A profile of chunk generation shows where the time goes. Most of it is spread
over many pieces of code that keep calling back into the rest of the server.
The biggest piece that is **self-contained** (it gets numbers in and gives
numbers out, nothing else) is *Perlin noise*: the smooth random function that
shapes hills, caves and biomes. It fills a 3D grid of sample points at a time
and uses about 5% of the generation CPU time.

That makes it a fair test of Rust: heavy maths, no shared state, and one call
covers hundreds of points, so the cost of crossing from Java to Rust is paid
rarely.

## What we tried

A Rust version of the server's noise loop (`native/atlas-native`). For each
column it prepares the eight corner values of a noise cell once and then
computes all the points inside that cell together, which lets the processor
work on 8 numbers per instruction (SIMD).

Three checks, all with the real server code:

- **Same answers.** 2,000 random grids with fixed seeds give bit-for-bit the
  same numbers as the Java code. Anything else would change the world.
- **Speed of the loop alone.** For slowly changing (low-frequency) noise the
  Rust loop is 2.2 times faster; for quickly changing noise it is no faster,
  because then every point needs its own corner values anyway.
- **Cost of the crossing.** Calling Rust through Java's newer FFM interface
  costs about 0.2 microseconds per call, through the older JNI about 0.3.
  FFM is used.

Raw numbers: `bench/results/native/noise-kernel.csv`.

## The honest expectation

Even if the loop were infinitely fast, generation could only get about 5%
faster, because that is all the time it takes. A 2× faster loop means a few
percent at most, which is close to how much repeated runs already differ.
So the real test is the full chunk benchmark on the same server jar with Rust
switched on and off (`bench/experiments/r1-native-noise.sh`). If it shows no
clear gain, this change is **not** merged and is listed under Failed
Experiments.

[[chart chunks fill100_s exp-r1-java,exp-r1-native]]

Issue: [#4](https://github.com/neonjava/Atlas/issues/4).

# Glossary

**Allocation.** When a program asks for new memory for an object. Lots of
allocation makes the garbage collector work more.

**Baseline.** The unmodified version every change is compared against.

**Bot.** A small program that pretends to be a player. Atlas's bots join the
server, move around and write down when things arrive.

**Chunk.** A 16 × 16 block column of the world, from bedrock to the sky. The
server generates, saves and sends the world one chunk at a time.

**Chunk generation.** Creating a chunk that has never existed: terrain shape,
caves, biomes, trees, ores and so on. It is much slower than loading a chunk
that is already saved.

**CPU core / thread.** A core is one worker inside the processor. Many
processors run two *threads* per core, so 8 threads can mean 4 cores.

**FFM and JNI.** Two ways for Java code to call code written in another
language such as Rust. JNI is the old way; FFM (Foreign Function & Memory API)
is the newer one built into recent Java.

**Folia.** A Minecraft server by the PaperMC team that ticks different parts
of the world (regions) at the same time on different cores.

**Fork.** A copy of a project that is developed separately.

**GC (garbage collection) and GC pause.** Java frees unused memory
automatically. Some of that work stops the whole program for a moment: a
*pause*. A long pause makes ticks late. **G1**, **ZGC** and **Shenandoah**
are three different garbage collectors in Java.

**Heap.** The memory Java uses for objects. Its size is set with `-Xmx`.

**JFR.** Java Flight Recorder, a profiler built into Java.

**Median.** The middle value when results are sorted. It is not pulled up or
down by one unusual run, which is why Atlas reports it.

**MSPT.** *Milliseconds per tick*: how long the server worked on one tick. At
20 ticks per second, a tick has 50 ms, so MSPT under 50 means the server keeps
up.

**Profile / profiler.** A recording of where a program spends its time.

**Region (Folia).** A group of nearby loaded chunks that Folia ticks as one
unit, on one thread at a time.

**Render distance / view distance.** How far around a player chunks are sent,
counted in chunks.

**Rust.** A programming language that is fast like C and checks memory safety
when the program is compiled.

**Spread (of results).** How far apart repeated runs are. Atlas shows the
lowest and highest run and the middle half.

**Tick / TPS.** The server updates the world in steps called *ticks*; the
target is 20 ticks per second (TPS).

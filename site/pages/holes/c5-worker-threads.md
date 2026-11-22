# Hole C5: one chunk worker on small machines

Status: open

## What we saw

When a player reaches land that does not exist yet, the server must
*generate* it. In Folia that work runs on "chunk worker" threads. Folia picks
how many from the number of CPU threads it may use:

| CPU threads | chunk workers | region tick threads |
|---|---|---|
| 2 to 7 | 1 | 1 |
| 8 to 9 | 2 | 1 |
| 10 and more | a quarter of them | an eighth of them |

So on a 4-core machine, **one thread generates all new terrain** while the
other cores mostly wait.

The baseline shows it plainly: giving the server 4 CPUs instead of 2 does not
make new chunks arrive any faster, while 8 CPUs (which get a second worker)
roughly halve the wait.

[[table chunks baseline fill100_s fill90_s]]

[[chart chunks fill100_s baseline]]

A profile of the 4-CPU case shows 89% of the server's CPU samples on chunk
worker threads: generation really is the work.

## What we are trying

Setting the number of chunk workers higher (2 on 2 CPUs; 3 or 4 on 4 CPUs;
6 on 8 CPUs) and measuring two things: how fast new chunks arrive, and
whether the extra workers steal so much CPU from the tick thread that ticks
get slower. The experiment is `bench/experiments/c5-workers.sh`; its results
appear here when it finishes.

[[chart chunks fill100_s baseline,exp-c5-control-t4,exp-c5-t2w2,exp-c5-t4w3,exp-c5-t4w4,exp-c5-t8w6]]

Issue: [#3](https://github.com/neonjava/Atlas/issues/3).

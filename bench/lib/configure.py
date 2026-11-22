#!/usr/bin/env python3
# configure.py — applies the Atlas benchmark settings to a server directory.
#
# Why: every run must use the same, written-down settings. Changes from the
# Folia defaults are only the ones bots need to join and move (listed and
# explained in docs/BENCHMARKS.md); performance settings stay at defaults
# unless a scenario sets them on purpose with --set.
# Done: server.properties keys, YAML keys in spigot.yml and config/*.yml.
# Not done: comments in YAML files are lost when a YAML file is rewritten.
#
# Usage: configure.py <server-dir> [--seed N] [--port N] [--view-distance N]
#                     [--set file.yml:dotted.key=value ...]
import argparse
import pathlib

import yaml

# Needed so bots can join and fly; none of these change server work per tick
# except allow-flight/gamemode (creative players are not damaged or hungry).
BASE_PROPERTIES = {
    "online-mode": "false",
    "white-list": "false",
    "enforce-secure-profile": "false",
    "gamemode": "creative",
    "force-gamemode": "true",
    "allow-flight": "true",
    "spawn-protection": "0",
    "max-players": "500",
}
# Bots fly faster than the vanilla speed check allows for creative flight
# (10x walking/flying speed); raising the multiplier avoids rubber-banding.
BASE_YAML = {
    "spigot.yml": {"settings.moved-too-quickly-multiplier": 100.0},
}


def set_properties(path: pathlib.Path, values: dict) -> None:
    lines = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
    seen = set()
    out = []
    for line in lines:
        key = line.split("=", 1)[0]
        if not line.startswith("#") and key in values:
            out.append(f"{key}={values[key]}")
            seen.add(key)
        else:
            out.append(line)
    out.extend(f"{k}={v}" for k, v in values.items() if k not in seen)
    path.write_text("\n".join(out) + "\n", encoding="utf-8")


def parse_value(text: str):
    return yaml.safe_load(text)


def set_yaml(path: pathlib.Path, dotted: str, value) -> None:
    data = yaml.safe_load(path.read_text(encoding="utf-8")) if path.exists() else {}
    node = data
    parts = dotted.split(".")
    for part in parts[:-1]:
        node = node.setdefault(part, {})
    node[parts[-1]] = value
    path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("dir", type=pathlib.Path)
    ap.add_argument("--seed")
    ap.add_argument("--port")
    ap.add_argument("--view-distance")
    ap.add_argument("--set", action="append", default=[])
    args = ap.parse_args()

    props = {}
    if args.seed is not None:
        props.update(BASE_PROPERTIES)
        props["level-seed"] = args.seed
        for file, entries in BASE_YAML.items():
            for key, value in entries.items():
                set_yaml(args.dir / file, key, value)
    if args.port is not None:
        props["server-port"] = args.port
    if args.view_distance is not None:
        props["view-distance"] = args.view_distance
    if props:
        set_properties(args.dir / "server.properties", props)
    for item in args.set:
        file, rest = item.split(":", 1)
        key, value = rest.split("=", 1)
        if file == "server.properties":
            set_properties(args.dir / file, {key: value})
        else:
            set_yaml(args.dir / file, key, parse_value(value))


if __name__ == "__main__":
    main()

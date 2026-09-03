#!/usr/bin/env python3
"""
The one check CI could not make.

Chrome builds itself with nineteen builders in a row, each assigning the
`lateinit` controls it owns. Nothing in the compiler stops one of them from
READING a control that a later builder has not made yet, and nothing in the
test suite sees it either: `:core` has no Android in it, and the APK job only
proves the code compiles. The app crashed on every launch for four commits
because buildColorCard refreshed the whole screen halfway through construction.

So this reads Chrome.kt and fails on the two shapes of that fault:

  1. a builder that reads a `lateinit` a later builder assigns, and
  2. a missing `built` guard on refresh(), which is what stops a builder from
     reaching every control in the app through one helper call.

It needs nothing but Python, so it runs in the job that has no Android SDK.
"""
import re
import sys

SRC = "app/src/main/kotlin/art/plume/anvil/Chrome.kt"


def main() -> int:
    lines = open(SRC).read().split("\n")
    faults = []

    # member functions, by line range
    funs = [
        (i, m.group(1))
        for i, l in enumerate(lines)
        if (m := re.match(r"    (?:private |internal )?fun (\w+)\(", l))
    ]
    funs.append((len(lines), "<end>"))
    rng = {funs[k][1]: (funs[k][0], funs[k + 1][0]) for k in range(len(funs) - 1)}

    # the order init calls them in
    start = next(i for i, l in enumerate(lines) if l.strip() == "init {")
    order = []
    for l in lines[start + 1:]:
        if l.strip() == "}":
            break
        if m := re.match(r"\s*(\w+)\(\)", l):
            order.append(m.group(1))
    at = {f: i for i, f in enumerate(order)}

    # which function assigns each lateinit, and where its owner sits in the order
    names = [
        re.search(r"lateinit var (\w+)", l).group(1)
        for l in lines
        if "lateinit var" in l
    ]
    owner = {}
    for name in names:
        for i, l in enumerate(lines):
            if re.match(r"\s*" + name + r" = ", l):
                owner[name] = next(
                    (f for f, (a, b) in rng.items() if a < i < b), None
                )
                break

    for name in names:
        home = owner.get(name)
        # a control assigned inside a helper is placed by whoever calls it;
        # only the ones a builder owns outright can be ordered here
        if home not in at:
            continue
        for f, (a, b) in rng.items():
            if f not in at or at[f] >= at[home]:
                continue
            for i in range(a, b):
                if re.search(r"\b" + name + r"\b", lines[i]) and not re.match(
                    r"\s*" + name + r" = ", lines[i]
                ):
                    faults.append(
                        f"{SRC}:{i + 1}: {f}() reads `{name}`, which {home}() "
                        f"assigns later in init"
                    )

    body = "\n".join(lines)
    if not re.search(r"fun refresh\(\) \{\n\s*if \(!built\) return\b", body):
        faults.append(
            f"{SRC}: refresh() has lost its `if (!built) return` guard — a "
            f"builder that refreshes reads controls that do not exist yet"
        )
    if not re.search(r"built = true\n\s*applyMode\(\)\n\s*refresh\(\)", body):
        faults.append(
            f"{SRC}: init must set `built = true` before its closing refresh()"
        )

    for f in faults:
        print(f)
    print(
        "initorder: clean" if not faults else f"initorder: {len(faults)} fault(s)"
    )
    return 1 if faults else 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""
The one check CI could not make.

Chrome builds itself with nineteen builders in a row, each assigning the
`lateinit` controls it owns. Nothing in the compiler stops one of them from
READING a control that a later builder has not made yet, and nothing in the
test suite sees it either: `:core` has no Android in it, and the APK job only
proves the code compiles. The app crashed on every launch for four commits
because buildColorCard refreshed the whole screen halfway through construction.

So this reads Chrome.kt and fails on the shapes of that fault:

  1. a builder that reads a `lateinit` a later builder assigns,
  2. a missing `built` guard on refresh(), which is what stops a builder from
     reaching every control in the app through one helper call, and
  3. a property with an initialiser declared BELOW the init block.

The third is a second door onto the same crash, and it caught this project
out once the guard was in place. Kotlin runs property initialisers and init
blocks in SOURCE ORDER, so a `val panel = LinearLayout(act)` written below
init is still null while init runs — and the builder that touches it throws a
NullPointerException out of the constructor. It is invisible to the compiler,
because the type says non-null and it will be non-null a moment later.

Five hundred lines of new panels were added at the end of the class with their
fields beside them, which is the tidy place to put them, and every one of
those fields was null by the time the builders ran. The fix is structural:
init goes LAST, after every declaration, and this holds it there.

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

    # ---- 3. nothing with an initialiser may be declared below init ----------
    #
    # A `lateinit` is exempt: it has no initialiser to be waiting for, and the
    # builder that assigns it is ordered by check 1 above. Everything else —
    # a val holding a view, a var holding a default — has to exist before the
    # first builder runs, and source order is the only thing that decides.
    init_line = start
    companion = next(
        (i for i, l in enumerate(lines) if re.match(r"    (?:private )?companion object", l)),
        len(lines),
    )
    if init_line > companion:
        faults.append(f"{SRC}: the init block sits inside or after the companion object")
    for i in range(init_line + 1, companion):
        m = re.match(r"    private (?:val|var) (\w+)\s*(?::[^=]+)?=", lines[i])
        if m and "lateinit" not in lines[i]:
            faults.append(
                f"{SRC}:{i + 1}: `{m.group(1)}` is declared below the init block, "
                f"so it is still null while the builders run — move it up, or "
                f"move init further down"
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

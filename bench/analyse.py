#!/usr/bin/env python3
"""analyse.py - W7 measurement analysis for the evaluation chapter.

Reads every *.csv that pm/PmBench wrote into the folder given, drops the warm-up rows (run 0),
and reports per mode (plain / gcm / head): the median and the interquartile range (IQR, the 25th
to 75th percentile) of the store and retrieve totals, the median of every lap, and the head's
overhead = head minus gcm. Writes summary.md and latency.png / latency.pdf next to the CSVs.

usage: python3 bench/analyse.py bench/results/<date>
"""
import csv
import glob
import os
import sys

import numpy as np
import matplotlib
matplotlib.use("Agg")                      # draw to a file, no window needed
import matplotlib.pyplot as plt

MODES = ["plain", "gcm", "head"]
MODE_LABEL = {"plain": "plain S3\n(no E2SE)", "gcm": "sealed vault,\nno head", "head": "sealed vault\nwith head"}
STORE_LAPS = [("take_ns", "take (unlock)"), ("head_get_ns", "head_get"), ("seal_ns", "seal"),
              ("head_update_ns", "head_update (+fingerprint)"), ("s3_put_ns", "s3_put")]
RETRIEVE_LAPS = [("r_take_ns", "take (unlock)"), ("r_head_get_ns", "head_get"), ("s3_get_ns", "s3_get"),
                 ("verify_ns", "verify (version + fingerprint)"), ("open_ns", "open (seal check)")]

# colours: the dataviz reference palette, light surface; one hue because the bars are one series (latency)
BAR, TEXT, TEXT2, GRID, SURFACE = "#2a78d6", "#0b0b0b", "#52514e", "#e6e5e2", "#fcfcfb"


def load(folder):
    rows = []
    for path in sorted(glob.glob(os.path.join(folder, "*.csv"))):
        with open(path, newline="", encoding="utf-8") as f:
            rows.extend(csv.DictReader(f))
    return rows


def ms(rows, col):
    """The column as milliseconds, empty fields (a lap this mode does not have) left out."""
    return np.array([int(r[col]) / 1e6 for r in rows if r.get(col) not in ("", None)])


def med_iqr(a):
    return float(np.median(a)), float(np.percentile(a, 25)), float(np.percentile(a, 75))


def fmt(x, nd=1):
    return "-" if x is None else f"{x:.{nd}f}"


def main(folder):
    rows = load(folder)
    if not rows:
        sys.exit(f"no CSV files in {folder}")
    stats = {}
    for m in MODES:
        mrows = [r for r in rows if r["mode"] == m and r["warmup"] == "0"]
        if not mrows:
            continue
        s = {"n": len(mrows), "ok": sum(r["ok"] == "1" for r in mrows), "bytes": mrows[0]["vault_bytes"]}
        s["store"] = med_iqr(ms(mrows, "store_total_ns"))
        s["retrieve"] = med_iqr(ms(mrows, "retrieve_total_ns"))
        s["laps"] = {}
        for col, _ in STORE_LAPS + RETRIEVE_LAPS:
            a = ms(mrows, col)
            s["laps"][col] = float(np.median(a)) if a.size else None
        stats[m] = s
    present = [m for m in MODES if m in stats]

    lines = []
    lines.append(f"# W7 campaign: {os.path.basename(os.path.normpath(folder))}")
    lines.append("")
    runlog = os.path.join(folder, "run-log.txt")
    if os.path.exists(runlog):
        lines.append("Run log: " + open(runlog, encoding="utf-8").read().strip())
        lines.append("")
    lines.append("Milliseconds; median and IQR (25th to 75th percentile) over the runs after the warm-up (run 0 excluded). "
                 "One JVM per mode, one open S3 connection; each key-server request opens its own TLS connection.")
    lines.append("")
    lines.append("| configuration | runs (ok) | vault bytes | store median | store IQR | retrieve median | retrieve IQR |")
    lines.append("|---|---|---|---|---|---|---|")
    for m in present:
        s = stats[m]
        lines.append(f"| {m} | {s['n']} ({s['ok']}) | {s['bytes']} | {fmt(s['store'][0])} | {fmt(s['store'][1])}-{fmt(s['store'][2])} "
                     f"| {fmt(s['retrieve'][0])} | {fmt(s['retrieve'][1])}-{fmt(s['retrieve'][2])} |")
    lines.append("")
    lines.append("Lap medians (ms); '-' = the configuration has no such phase.")
    lines.append("")
    lines.append("| phase | lap | " + " | ".join(present) + " |")
    lines.append("|---|---|" + "---|" * len(present))
    for col, name in STORE_LAPS:
        lines.append(f"| store | {name} | " + " | ".join(fmt(stats[m]['laps'][col], 2) for m in present) + " |")
    for col, name in RETRIEVE_LAPS:
        lines.append(f"| retrieve | {name} | " + " | ".join(fmt(stats[m]['laps'][col], 2) for m in present) + " |")
    lines.append("")
    if "head" in stats and "gcm" in stats:
        h, g = stats["head"], stats["gcm"]
        ds = h["store"][0] - g["store"][0]
        dr = h["retrieve"][0] - g["retrieve"][0]
        hs = (h["laps"]["head_get_ns"] or 0) + (h["laps"]["head_update_ns"] or 0)
        hr = (h["laps"]["r_head_get_ns"] or 0) + (h["laps"]["verify_ns"] or 0)
        lines.append("Head overhead (median with head minus median without):")
        lines.append(f"- store: {ds:+.1f} ms ({100 * ds / g['store'][0]:+.1f} % of the no-head store); the two head laps add up to "
                     f"{hs:.1f} ms (head_get {fmt(h['laps']['head_get_ns'], 2)}, head_update {fmt(h['laps']['head_update_ns'], 2)})")
        lines.append(f"- retrieve: {dr:+.1f} ms ({100 * dr / g['retrieve'][0]:+.1f} % of the no-head retrieve); head_get "
                     f"{fmt(h['laps']['r_head_get_ns'], 2)} + verify {fmt(h['laps']['verify_ns'], 2)} = {hr:.2f} ms")
        lines.append("  (a difference of medians is not the median of the differences; the lap sums are the direct measure of the head's own cost)")
    else:
        lines.append("Head overhead: not available (needs both the gcm and the head CSV).")
    lines.append("")
    text = "\n".join(lines)
    print(text)
    with open(os.path.join(folder, "summary.md"), "w", encoding="utf-8") as f:
        f.write(text + "\n")

    # the figure: two panels (store, retrieve), one bar per configuration = median, whiskers = IQR, value on the cap
    fig, axes = plt.subplots(1, 2, figsize=(7.2, 3.3), dpi=200, sharey=True)
    fig.patch.set_facecolor(SURFACE)
    n_runs = min(stats[m]["n"] for m in present)
    for ax, phase, title in ((axes[0], "store", "store"), (axes[1], "retrieve", "retrieve")):
        ax.set_facecolor(SURFACE)
        xs = np.arange(len(present))
        meds = [stats[m][phase][0] for m in present]
        lo = [stats[m][phase][0] - stats[m][phase][1] for m in present]
        hi = [stats[m][phase][2] - stats[m][phase][0] for m in present]
        ax.bar(xs, meds, width=0.5, color=BAR, linewidth=0, zorder=2)
        ax.errorbar(xs, meds, yerr=[lo, hi], fmt="none", ecolor=TEXT2, elinewidth=1, capsize=3, zorder=3)
        for x, med, top in zip(xs, meds, hi):
            ax.text(x, med + top, f"{med:.0f}", ha="center", va="bottom", fontsize=8, color=TEXT, zorder=4,
                    transform=ax.transData, clip_on=False)
        ax.set_xticks(xs)
        ax.set_xticklabels([MODE_LABEL[m] for m in present], fontsize=8, color=TEXT2)
        ax.set_title(title, loc="left", fontsize=10, color=TEXT)
        ax.grid(axis="y", color=GRID, linewidth=0.8, zorder=0)
        ax.set_axisbelow(True)
        for side in ("top", "right"):
            ax.spines[side].set_visible(False)
        for side in ("left", "bottom"):
            ax.spines[side].set_color(GRID)
        ax.tick_params(axis="y", colors=TEXT2, labelsize=8, length=0)
        ax.tick_params(axis="x", colors=TEXT2, length=0)
        ax.margins(y=0.15)
    axes[0].set_ylabel("milliseconds", fontsize=8, color=TEXT2)
    fig.suptitle(f"Latency per configuration: median, whiskers = IQR, N = {n_runs} runs each (warm-up excluded)",
                 fontsize=8, color=TEXT2, x=0.01, ha="left")
    fig.tight_layout(rect=(0, 0, 1, 0.94))
    for ext in ("png", "pdf"):
        fig.savefig(os.path.join(folder, f"latency.{ext}"), facecolor=SURFACE)
    print(f"wrote summary.md, latency.png and latency.pdf to {folder}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: python3 bench/analyse.py bench/results/<date>")
    main(sys.argv[1])

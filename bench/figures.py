#!/usr/bin/env python3
"""figures.py - two further figures and the intervals for the evaluation chapter, from the CSV files analyse.py reads.

  thesis/figures/fig-phases.pdf   where the time of a store and a retrieve goes, with and without the head (phase medians)
  thesis/figures/fig-runs.pdf     every one of the 25 runs per configuration, with the median marked
  bench/results/intervals.md      the head's cost with a 95 % interval, and whether ten times the vault changes it

No new measurement is made: this reads bench/results/2026-10-26 (100 entries) and bench/results/2026-10-27-1000
(1000 entries), drops the warm-up run, and resamples the 25 runs of each configuration.

The interval, in plain words: take the 25 runs, draw 25 of them at random with repeats, note the median; do that
20 000 times; the middle 95 % of those medians is the interval. It says how far the median could move if the campaign
were repeated on the same machine on the same day. The random generator is seeded, so the numbers repeat exactly.

usage, from the repository root:  python3 bench/figures.py
"""
import csv
import os
import warnings

import numpy as np
warnings.filterwarnings("ignore", message="Unable to import Axes3D")   # two matplotlib installs on this machine; 3D is not used here
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Patch
from matplotlib.lines import Line2D

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CAMPAIGNS = [("100 entries (3 300 bytes)", "2026-10-26"), ("1000 entries (33 002 bytes)", "2026-10-27-1000")]
OUT = os.path.join(ROOT, "thesis", "figures")

# colours: one accent, the blue of analyse.py's bars, here always "the head"; the rest is grey, lighter and darker
# than the blue, so that the three fills stay apart when printed in black and white (L* 78, 50, 34)
HEAD, UNLOCK, STORAGE = "#2a78d6", "#c3c2b7", "#52514e"
TEXT, TEXT2, GRID, SURFACE = "#0b0b0b", "#52514e", "#e6e5e2", "#fcfcfb"
B, SEED = 20000, 20261105


def load(folder, mode):
    with open(os.path.join(ROOT, "bench", "results", folder, mode + ".csv"), newline="", encoding="utf-8") as f:
        rows = [r for r in csv.DictReader(f) if r["warmup"] == "0"]
    assert len(rows) == 25 and all(r["ok"] == "1" for r in rows), (folder, mode, len(rows))
    return rows


def ms(rows, col):
    return np.array([int(r[col]) / 1e6 for r in rows])


def boot_median(x):
    rng = np.random.default_rng(SEED)
    return np.percentile(np.median(rng.choice(x, size=(B, len(x)), replace=True), axis=1), [2.5, 97.5])


def boot_diff(a, b):
    """95 % interval of median(a) - median(b), the two groups resampled independently."""
    rng = np.random.default_rng(SEED)
    d = np.median(rng.choice(a, size=(B, len(a)), replace=True), axis=1) - np.median(rng.choice(b, size=(B, len(b)), replace=True), axis=1)
    return np.percentile(d, [2.5, 97.5])


def style(ax):
    ax.set_facecolor(SURFACE)
    ax.grid(axis="x", color=GRID, linewidth=0.8, zorder=0)
    ax.set_axisbelow(True)
    for side in ("top", "right", "left"):
        ax.spines[side].set_visible(False)
    ax.spines["bottom"].set_color(GRID)
    ax.tick_params(axis="x", colors=TEXT2, labelsize=8, length=0)
    ax.tick_params(axis="y", colors=TEXT, labelsize=8, length=0)


def bars(data):
    """The four rows of a panel, top to bottom: (label, [(kind, column)], rows, total column)."""
    g, h = data["gcm"], data["head"]
    return [
        ("store, no head",      [("unlock", "take_ns"), ("storage", "s3_put_ns")], g, "store_total_ns"),
        ("store, with head",    [("unlock", "take_ns"), ("head", "head_get_ns"), ("head", "head_update_ns"), ("storage", "s3_put_ns")], h, "store_total_ns"),
        ("retrieve, no head",   [("unlock", "r_take_ns"), ("storage", "s3_get_ns")], g, "retrieve_total_ns"),
        ("retrieve, with head", [("unlock", "r_take_ns"), ("head", "r_head_get_ns"), ("storage", "s3_get_ns")], h, "retrieve_total_ns"),
    ]


def fig_phases(all_data):
    colour = {"unlock": UNLOCK, "head": HEAD, "storage": STORAGE}
    fig, axes = plt.subplots(2, 1, figsize=(7.2, 4.4), dpi=200, sharex=True)
    fig.patch.set_facecolor(SURFACE)
    for ax, (title, folder) in zip(axes, CAMPAIGNS):
        style(ax)
        rows = bars(all_data[folder])
        for y, (label, parts, src, total_col) in zip(range(len(rows) - 1, -1, -1), rows):
            left = 0.0
            for kind, col in parts:
                w = float(np.median(ms(src, col)))
                ax.barh(y, w, left=left, height=0.5, color=colour[kind], edgecolor=SURFACE, linewidth=1.6, zorder=2)
                if kind == "head":                       # the story of the figure: label only what the head adds
                    ax.text(left + w / 2, y, f"{w:.0f}", ha="center", va="center", fontsize=8, color="#ffffff", zorder=3)
                left += w
            ax.text(left + 3, y, f"{np.median(ms(src, total_col)):.0f} ms", ha="left", va="center", fontsize=8, color=TEXT, zorder=3)
        ax.set_yticks(range(len(rows) - 1, -1, -1))
        ax.set_yticklabels([r[0] for r in rows])
        ax.set_ylim(-0.6, len(rows) - 0.4)
        ax.set_xlim(0, 262)
        ax.set_title(title, loc="left", fontsize=9, color=TEXT)
    axes[-1].set_xlabel("milliseconds (median of 25 runs; the number at the end of a bar is the median of the whole command)", fontsize=8, color=TEXT2)
    handles = [Patch(facecolor=UNLOCK, label="unlock (E2SE's Take)"), Patch(facecolor=HEAD, label="the head's requests (added by this thesis)"),
               Patch(facecolor=STORAGE, label="storage transfer")]
    fig.legend(handles=handles, loc="upper left", bbox_to_anchor=(0.005, 0.995), ncol=3, frameon=False, fontsize=8, labelcolor=TEXT,
               handlelength=1.2, handleheight=0.9, columnspacing=1.6)
    fig.tight_layout(rect=(0, 0, 1, 0.94))
    for ext in ("pdf", "png"):
        fig.savefig(os.path.join(OUT, f"fig-phases.{ext}"), facecolor=SURFACE)
    plt.close(fig)


def fig_runs(all_data):
    rng = np.random.default_rng(SEED)
    fig, axes = plt.subplots(2, 1, figsize=(7.2, 4.2), dpi=200, sharex=True)
    fig.patch.set_facecolor(SURFACE)
    for ax, (title, folder) in zip(axes, CAMPAIGNS):
        style(ax)
        rows = bars(all_data[folder])
        for y, (label, parts, src, total_col) in zip(range(len(rows) - 1, -1, -1), rows):
            x = ms(src, total_col)
            c = HEAD if "with head" in label else STORAGE
            ax.scatter(x, y + rng.uniform(-0.2, 0.2, size=len(x)), s=20, color=c, edgecolor=SURFACE, linewidth=0.7, zorder=2)
            m = float(np.median(x))
            ax.plot([m, m], [y - 0.36, y + 0.36], color=TEXT, linewidth=1.3, zorder=3)
            ax.text(m, y + 0.4, f"{m:.0f}", ha="center", va="bottom", fontsize=7.5, color=TEXT, zorder=3)
        ax.set_yticks(range(len(rows) - 1, -1, -1))
        ax.set_yticklabels([r[0] for r in rows])
        ax.set_ylim(-0.6, len(rows) - 0.25)
        ax.set_xlim(100, 272)
        ax.set_title(title, loc="left", fontsize=9, color=TEXT)
    axes[-1].set_xlabel("milliseconds for the whole command (the axis starts at 100)", fontsize=8, color=TEXT2)
    handles = [Line2D([], [], marker="o", linestyle="none", markersize=5, markerfacecolor=STORAGE, markeredgecolor=SURFACE, label="one run, sealed vault without the head"),
               Line2D([], [], marker="o", linestyle="none", markersize=5, markerfacecolor=HEAD, markeredgecolor=SURFACE, label="one run, with the head"),
               Line2D([], [], color=TEXT, linewidth=1.3, label="median of the 25 runs")]
    fig.legend(handles=handles, loc="upper left", bbox_to_anchor=(0.005, 0.995), ncol=3, frameon=False, fontsize=8, labelcolor=TEXT, columnspacing=1.6)
    fig.tight_layout(rect=(0, 0, 1, 0.94))
    for ext in ("pdf", "png"):
        fig.savefig(os.path.join(OUT, f"fig-runs.{ext}"), facecolor=SURFACE)
    plt.close(fig)


def intervals(all_data):
    out = ["# The head's cost with a 95 % interval (bench/figures.py, from the CSV files of the two campaigns)", "",
           f"Bootstrap of the median: {B} resamples of the 25 runs per configuration, generator seeded with {SEED}, warm-up run excluded.",
           "Cost of the head = median with the head minus median without it (sealed vault both times), the two groups resampled independently.", "",
           "| Vault | Command | Median without head | Median with head | Head adds | 95 % interval | Slowest run without | Fastest run with |",
           "|---|---|---|---|---|---|---|---|"]
    for title, folder in CAMPAIGNS:
        g, h = all_data[folder]["gcm"], all_data[folder]["head"]
        for name, col in (("store", "store_total_ns"), ("retrieve", "retrieve_total_ns")):
            a, b = ms(h, col), ms(g, col)
            lo, hi = boot_diff(a, b)
            out.append(f"| {title.split(' (')[0]} | {name} | {np.median(b):.1f} | {np.median(a):.1f} | +{np.median(a) - np.median(b):.1f} ms | {lo:.1f} to {hi:.1f} | {b.max():.1f} | {a.min():.1f} |")
    out += ["", "Each head request on its own, and the change at ten times the vault (1000 entries minus 100 entries):", "",
            "| Request | 100 entries, median (95 % interval) | 1000 entries, median (95 % interval) | Change | 95 % interval of the change |", "|---|---|---|---|---|"]
    (t100, f100), (t1000, f1000) = CAMPAIGNS
    for name, col in (("store: head fetch", "head_get_ns"), ("store: head update", "head_update_ns"), ("retrieve: head fetch", "r_head_get_ns")):
        a, b = ms(all_data[f100]["head"], col), ms(all_data[f1000]["head"], col)
        la, ha = boot_median(a); lb, hb = boot_median(b); lo, hi = boot_diff(b, a)
        out.append(f"| {name} | {np.median(a):.1f} ({la:.1f} to {ha:.1f}) | {np.median(b):.1f} ({lb:.1f} to {hb:.1f}) | {np.median(b) - np.median(a):+.1f} ms | {lo:+.1f} to {hi:+.1f} |")
    out += ["", "Reading: an interval of the change that contains zero means the data cannot tell the two sizes apart.", ""]
    # 6 Nov 2026: the E2SE paper reports the mean of 25 runs; Chapter 6 reports the median. The two side by side, so that
    # the sentence "the mean is within 3 ms of the median in every configuration" can be checked from this file.
    out += ["The mean and the standard deviation beside the median, whole commands (the E2SE paper reports means):", "",
            "| Vault | Configuration | Store: median | Store: mean (standard deviation) | Retrieve: median | Retrieve: mean (standard deviation) |", "|---|---|---|---|---|---|"]
    gap, from_means = 0.0, []
    for title, folder in CAMPAIGNS:
        for mode, name in (("plain", "plain storage"), ("gcm", "sealed vault, no head"), ("head", "sealed vault with the head")):
            s, r = ms(all_data[folder][mode], "store_total_ns"), ms(all_data[folder][mode], "retrieve_total_ns")
            gap = max(gap, abs(s.mean() - np.median(s)), abs(r.mean() - np.median(r)))
            out.append(f"| {title.split(' (')[0]} | {name} | {np.median(s):.1f} | {s.mean():.1f} ({s.std(ddof=1):.1f}) | {np.median(r):.1f} | {r.mean():.1f} ({r.std(ddof=1):.1f}) |")
        g, h = all_data[folder]["gcm"], all_data[folder]["head"]
        from_means.append(f"{title.split(' (')[0]}: store +{ms(h, 'store_total_ns').mean() - ms(g, 'store_total_ns').mean():.1f} ms, "
                          f"retrieve +{ms(h, 'retrieve_total_ns').mean() - ms(g, 'retrieve_total_ns').mean():.1f} ms")
    out += ["", f"Reading: the largest gap between a mean and its median is {gap:.1f} ms. The head's cost from the means: " + "; ".join(from_means) + ".", ""]
    text = "\n".join(out)
    with open(os.path.join(ROOT, "bench", "results", "intervals.md"), "w", encoding="utf-8") as f:
        f.write(text)
    print(text)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    data = {folder: {m: load(folder, m) for m in ("plain", "gcm", "head")} for _, folder in CAMPAIGNS}
    fig_phases(data)
    fig_runs(data)
    intervals(data)
    print("wrote thesis/figures/fig-phases.pdf, fig-runs.pdf (and .png) and bench/results/intervals.md")

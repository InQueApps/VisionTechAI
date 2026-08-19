#!/usr/bin/env python3
"""Run planar roundtrip at several IMU degradation levels and plot metrics vs degrade."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

_SCRIPT_DIR = Path(__file__).resolve().parent
_REPO_ROOT = _SCRIPT_DIR.parent
_ROUNDTRIP_ROOT = _REPO_ROOT / "scripts" / "images" / "roundtrip_output"
_STITCH_SCRIPT = _SCRIPT_DIR / "stitch_planar_roundtrip.py"

DEFAULT_IMU_LEVELS = [0.0, 5.0, 10.0, 15.0]
DEFAULT_SOURCE = "img_eq4096.jpg"


def subdir_for_level(source_stem: str, imu_deg: float) -> str:
    deg_label = int(imu_deg) if imu_deg == int(imu_deg) else imu_deg
    return f"{source_stem}_imu{deg_label}deg"


def load_metrics(folder: Path) -> dict | None:
    path = folder / "metrics.json"
    if not path.is_file():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def plot_sweep(
    rows: list[dict],
    out_png: Path,
    title: str,
) -> None:
    import matplotlib.pyplot as plt

    levels = [r["imu_degrade_deg"] for r in rows]
    corr = [r["metrics"]["correlation"] for r in rows]
    psnr = [r["metrics"]["psnr_db"] for r in rows]
    ghost = [r["metrics"].get("ghost_score", 0.0) for r in rows]
    sharp = [r["metrics"].get("sharpness_retention", 0.0) for r in rows]

    fig, axes = plt.subplots(2, 2, figsize=(10, 8), sharex=True)
    fig.suptitle(title, fontsize=13)

    panels = [
        (axes[0, 0], corr, "Correlation", "#2a6fdb"),
        (axes[0, 1], psnr, "PSNR (dB)", "#1a8f4a"),
        (axes[1, 0], ghost, "Ghost score", "#c44a2d"),
        (axes[1, 1], sharp, "Sharpness retention", "#7a5acd"),
    ]
    for ax, values, label, color in panels:
        ax.plot(levels, values, marker="o", linewidth=2, color=color)
        ax.set_ylabel(label)
        ax.set_xlabel("IMU degrade (deg)")
        ax.grid(True, alpha=0.3)
        for x, y in zip(levels, values):
            ax.annotate(f"{y:.3f}", (x, y), textcoords="offset points", xytext=(0, 6), ha="center", fontsize=8)

    fig.tight_layout()
    out_png.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out_png, dpi=150)
    plt.close(fig)


def run_roundtrip(
    source: str,
    device: str | None,
    imu_deg: float,
    out_subdir: str,
    no_build: bool,
    imu_seed: int,
) -> int:
    cmd = [
        sys.executable,
        str(_STITCH_SCRIPT),
        "--source",
        source,
        "--imu-degrade-deg",
        str(imu_deg),
        "--imu-degrade-seed",
        str(imu_seed),
        "--out-subdir",
        out_subdir,
        "--no-random-roll",
        "--no-random-heading",
        "--no-random-elevation",
    ]
    if device:
        cmd.extend(["--device", device])
    if no_build:
        cmd.append("--no-build")

    print()
    print("=" * 72)
    print(f"IMU degrade sweep: {imu_deg}° -> {out_subdir}")
    print("=" * 72)
    proc = subprocess.run(cmd, cwd=_REPO_ROOT)
    return proc.returncode


def main() -> None:
    p = argparse.ArgumentParser(description="IMU degradation sweep for planar roundtrip.")
    p.add_argument("--source", default=DEFAULT_SOURCE, help="Equirect source under scripts/images/")
    p.add_argument("--device", default=None, help="adb device serial")
    p.add_argument(
        "--imu-levels",
        type=float,
        nargs="+",
        default=DEFAULT_IMU_LEVELS,
        help="IMU degrade degrees (default: 0 5 10 15)",
    )
    p.add_argument("--imu-degrade-seed", type=int, default=42)
    p.add_argument(
        "--plot-only",
        action="store_true",
        help="Skip device runs; plot from existing metrics.json folders",
    )
    p.add_argument(
        "--out-plot",
        type=Path,
        default=None,
        help="Summary PNG path (default: roundtrip_output/imu_degrade_sweep_summary.png)",
    )
    args = p.parse_args()

    source_stem = Path(args.source).stem
    out_plot = args.out_plot or (_ROUNDTRIP_ROOT / "imu_degrade_sweep_summary.png")
    summary_json = _ROUNDTRIP_ROOT / "imu_degrade_sweep_summary.json"

    if not args.plot_only:
        for deg in args.imu_levels:
            subdir = subdir_for_level(source_stem, deg)
            code = run_roundtrip(
                args.source,
                args.device,
                deg,
                subdir,
                no_build=False,
                imu_seed=args.imu_degrade_seed,
            )
            if code != 0:
                print(f"Warning: roundtrip at {deg}° exited with code {code} (metrics may still exist)")

    rows: list[dict] = []
    for deg in args.imu_levels:
        subdir = subdir_for_level(source_stem, deg)
        folder = _ROUNDTRIP_ROOT / subdir
        metrics = load_metrics(folder)
        if metrics is None:
            print(f"Missing metrics.json in {folder}")
            continue
        rows.append(
            {
                "imu_degrade_deg": deg,
                "out_subdir": subdir,
                "folder": str(folder),
                "metrics": metrics,
            }
        )

    if not rows:
        raise SystemExit("No metrics collected — run device sweep first or check output folders.")

    summary_json.write_text(json.dumps(rows, indent=2), encoding="utf-8")
    plot_sweep(
        rows,
        out_plot,
        f"Planar roundtrip vs IMU degrade ({args.source}, production worker)",
    )

    print()
    print("=" * 72)
    print("IMU DEGRADE SWEEP SUMMARY")
    print("=" * 72)
    for row in rows:
        m = row["metrics"]
        print(
            f"  {row['imu_degrade_deg']:>5.1f}°  corr={m['correlation']:.4f}  "
            f"PSNR={m['psnr_db']:.2f} dB  ghost={m.get('ghost_score', 'n/a')}  "
            f"sharpRet={m.get('sharpness_retention', 'n/a')}"
        )
    print(f"  Summary JSON: {summary_json}")
    print(f"  Summary plot: {out_plot}")
    print("=" * 72)


if __name__ == "__main__":
    main()

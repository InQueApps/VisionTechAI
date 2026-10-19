#!/usr/bin/env python3
"""Aggregate timing_ms from replay_metrics.json folders into a summary table."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

_SCRIPT_DIR = Path(__file__).resolve().parent
_REPO_ROOT = _SCRIPT_DIR.parent


def step_ms(steps: list, name: str) -> int:
    for s in steps:
        if s.get("step") == name:
            return int(s.get("duration_ms", 0))
    return 0


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument(
        "--output-root",
        default="roundtrip_output_full",
        help="Folder under scripts/images/",
    )
    args = p.parse_args()
    root = _REPO_ROOT / "scripts" / "images" / args.output_root
    rows: list[dict] = []

    for folder in sorted(root.iterdir()):
        if not folder.is_dir():
            continue
        rp = folder / "replay_metrics.json"
        if not rp.is_file():
            rows.append({"folder": folder.name, "status": "no replay_metrics"})
            continue
        data = json.loads(rp.read_text(encoding="utf-8"))
        timing = data.get("timing_ms", {})
        wp = timing.get("worker_pipeline", {})
        steps_list = wp.get("steps", [])
        finish_passes = {s["step"]: s["duration_ms"] for s in wp.get("finish_export_passes", [])}
        seams_ms = max(
            finish_passes.get("finish:seams full", 0),
            finish_passes.get("finish:seams light", 0),
        )
        rows.append(
            {
                "folder": folder.name,
                "imu_deg": data.get("imu_degrade_deg"),
                "equirect_psnr_db": data.get("equirect_psnr_db"),
                "equirect_correlation_mapped": data.get("equirect_correlation_mapped"),
                "worker_wall_ms": wp.get("wall_total_ms"),
                "native_init_ms": step_ms(steps_list, "native_init"),
                "frame_ingest_all_ms": step_ms(steps_list, "frame_ingest_all"),
                "capture_refine_idle_wait_ms": step_ms(steps_list, "capture_refine_idle_wait"),
                "pre_finish_drain_ms": step_ms(steps_list, "pre_finish_drain"),
                "finish_export_foreground_ms": step_ms(steps_list, "finish_export_foreground"),
                "background_polish_ms": step_ms(steps_list, "background_polish"),
                "prepare_export_ms": finish_passes.get("finish:prepareExport", 0),
                "seams_ms": seams_ms,
                "equirect_metrics_ms": timing.get("equirect_metrics", {}).get("wall_total_ms"),
                "planar_roundtrip_ms": timing.get("planar_roundtrip", {}).get("wall_total_ms"),
                "frame_ingest_ms": wp.get("frame_ingest_ms"),
                "finish_export_passes": wp.get("finish_export_passes"),
            }
        )

    out = root / "timing_profile_summary.json"
    out.write_text(json.dumps(rows, indent=2), encoding="utf-8")
    print(f"Written {out}\n")
    header = (
        "IMU | worker | ingest | capture | pre_finish | finish_fg | export | seams | polish | metrics | planar | PSNR"
    )
    print(header)
    for r in rows:
        if "status" in r:
            print(f"{r['folder']}: {r['status']}")
            continue
        def s(ms: int | float | None) -> str:
            return f"{(ms or 0) / 1000:.1f}s"

        print(
            f"{r['imu_deg']:>4} | {s(r['worker_wall_ms']):>6} | {s(r['frame_ingest_all_ms']):>6} | "
            f"{s(r['capture_refine_idle_wait_ms']):>6} | {s(r['pre_finish_drain_ms']):>7} | "
            f"{s(r['finish_export_foreground_ms']):>6} | {s(r['prepare_export_ms']):>6} | "
            f"{s(r['seams_ms']):>5} | {s(r['background_polish_ms']):>5} | "
            f"{s(r['equirect_metrics_ms']):>6} | {s(r['planar_roundtrip_ms']):>5} | "
            f"{r['equirect_psnr_db']:.1f}dB"
        )


if __name__ == "__main__":
    main()

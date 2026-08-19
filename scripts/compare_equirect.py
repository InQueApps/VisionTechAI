#!/usr/bin/env python3
"""Compare stitched equirect to reference; write side-by-side, diff, and metrics.json."""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image

_SCRIPT_DIR = Path(__file__).resolve().parent
if str(_SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_DIR))

from equirect_band import extract_equirect_lat_band  # noqa: E402


def load_rgb(path: Path) -> np.ndarray:
    return np.asarray(Image.open(path).convert("RGB"), dtype=np.float64)


def content_mask(rgb: np.ndarray, bg: tuple[int, int, int] = (0x1A, 0x1A, 0x2E)) -> np.ndarray:
    """Non-background pixels (matches SphereImageMetrics dark-blue empty fill)."""
    bg_arr = np.array(bg, dtype=np.float64)
    diff = np.abs(rgb - bg_arr).sum(axis=2)
    return diff > 12.0


def correlation(a: np.ndarray, b: np.ndarray, mask: np.ndarray) -> float:
    av = a[mask].reshape(-1, 3)
    bv = b[mask].reshape(-1, 3)
    if av.size == 0:
        return 0.0
    am = av - av.mean(axis=0)
    bm = bv - bv.mean(axis=0)
    num = (am * bm).sum()
    den = math.sqrt((am * am).sum() * (bm * bm).sum())
    return float(num / den) if den > 1e-9 else 0.0


def rgb_to_luminance(rgb: np.ndarray) -> np.ndarray:
    return 0.299 * rgb[..., 0] + 0.587 * rgb[..., 1] + 0.114 * rgb[..., 2]


def laplacian_variance(rgb: np.ndarray, mask: np.ndarray) -> float:
    """Match SphereImageMetrics.laplacianVariance on content pixels."""
    lum = rgb_to_luminance(rgb)
    h, w = lum.shape
    if h < 3 or w < 3:
        return 0.0
    interior = mask[1:-1, 1:-1]
    center = lum[1:-1, 1:-1]
    lap = (
        4.0 * center
        - lum[0:-2, 1:-1]
        - lum[2:, 1:-1]
        - lum[1:-1, 0:-2]
        - lum[1:-1, 2:]
    )
    vals = lap[interior]
    if vals.size == 0:
        return 0.0
    mean = float(vals.mean())
    return float((vals * vals).mean() - mean * mean)


def sharpness_retention(ref_sharp: float, st_sharp: float) -> float:
    if ref_sharp <= 1e-6:
        return 1.0 if st_sharp <= 1e-6 else 0.0
    return st_sharp / ref_sharp


def laplacian_magnitude(rgb: np.ndarray) -> np.ndarray:
    lum = rgb_to_luminance(rgb)
    out = np.zeros_like(lum)
    out[1:-1, 1:-1] = np.abs(
        4.0 * lum[1:-1, 1:-1]
        - lum[0:-2, 1:-1]
        - lum[2:, 1:-1]
        - lum[1:-1, 0:-2]
        - lum[1:-1, 2:]
    )
    return out


def psnr(a: np.ndarray, b: np.ndarray, mask: np.ndarray) -> float:
    diff = a[mask] - b[mask]
    if diff.size == 0:
        return 0.0
    mse = float((diff * diff).mean())
    if mse <= 1e-6:
        return 99.0
    return 10.0 * math.log10((255.0 * 255.0) / mse)


def content_region(mask: np.ndarray) -> tuple[int, int, int, int]:
    ys, xs = np.where(mask)
    if xs.size == 0:
        h, w = mask.shape
        return 0, 0, w, h
    x0, x1 = int(xs.min()), int(xs.max())
    y0, y1 = int(ys.min()), int(ys.max())
    return x0, y0, x1 - x0 + 1, y1 - y0 + 1


def overlap_band_region(
    x0: int, y0: int, w: int, h: int, band_height_fraction: float = 0.35
) -> tuple[int, int, int, int]:
    band_h = max(8, int(h * band_height_fraction))
    band_y0 = y0 + (h - band_h) // 2
    return x0, band_y0, w, band_h


def ghosting_score(
    rgb: np.ndarray,
    x0: int,
    y0: int,
    w: int,
    h: int,
    min_shift: int = 3,
    max_shift: int = 12,
    edge_grad_threshold: float = 35.0,
) -> float:
    """Match SphereImageMetrics.ghostingScore on overlap-band region."""
    st = rgb.astype(np.uint8)
    mask = content_mask(st.astype(np.float64))
    lum = rgb_to_luminance(st.astype(np.float64))

    def horizontal_grad(x: int, y: int) -> float:
        return float(lum[y, x + 1] - lum[y, x - 1])

    x_start = max(3, x0)
    y_start = max(1, y0)
    x_end = min(st.shape[1] - max_shift - 3, x0 + w)
    y_end = min(st.shape[0] - 1, y0 + h)
    if x_end <= x_start + max_shift or y_end <= y_start + 1:
        return 0.0

    edge_count = 0
    ghost_count = 0
    for y in range(y_start, y_end):
        for x in range(x_start, x_end):
            if not mask[y, x]:
                continue
            g1 = horizontal_grad(x, y)
            if abs(g1) < edge_grad_threshold:
                continue
            edge_count += 1
            found_ghost = False
            for dx in range(min_shift, max_shift + 1):
                x2 = x + dx
                if not mask[y, x2]:
                    continue
                g2 = horizontal_grad(x2, y)
                if abs(g2) < edge_grad_threshold or g1 * g2 < 0:
                    continue
                l1_l = lum[y, x - 2]
                l1_r = lum[y, x + 2]
                l2_l = lum[y, x2 - 2]
                l2_r = lum[y, x2 + 2]
                if abs(l1_l - l2_l) < 32 and abs(l1_r - l2_r) < 32:
                    ghost_count += 1
                    found_ghost = True
                    break
            if not found_ghost:
                for dy in range(-2, 3):
                    if dy == 0:
                        continue
                    y2 = y + dy
                    if y2 < y_start or y2 >= y_end:
                        continue
                    for dx in range(min_shift, max_shift + 1):
                        x2 = x + dx
                        if not mask[y2, x2]:
                            continue
                        g2 = horizontal_grad(x2, y2)
                        if abs(g2) < edge_grad_threshold or g1 * g2 < 0:
                            continue
                        l1_l = lum[y, x - 2]
                        l1_r = lum[y, x + 2]
                        l2_l = lum[y2, x2 - 2]
                        l2_r = lum[y2, x2 + 2]
                        if abs(l1_l - l2_l) < 32 and abs(l1_r - l2_r) < 32:
                            ghost_count += 1
                            found_ghost = True
                            break
                    if found_ghost:
                        break
    return ghost_count / edge_count if edge_count > 0 else 0.0


def compare(
    reference: Path,
    stitched: Path,
    out_dir: Path,
    *,
    elevation_deg: float | None = None,
    vfov_deg: float | None = None,
    full_equirect: bool = False,
) -> dict:
    ref = load_rgb(reference)
    st = load_rgb(stitched)
    if ref.shape != st.shape:
        if (
            elevation_deg is not None
            and vfov_deg is not None
            and st.shape[1] == ref.shape[1]
            and st.shape[0] > ref.shape[0]
        ):
            st = extract_equirect_lat_band(
                st.astype(np.uint8), elevation_deg, vfov_deg
            ).astype(np.float64)
        elif full_equirect:
            st_img = Image.fromarray(st.astype(np.uint8))
            st = np.asarray(
                st_img.resize((ref.shape[1], ref.shape[0]), Image.Resampling.LANCZOS),
                dtype=np.float64,
            )
        else:
            raise SystemExit(
                f"Size mismatch: reference {ref.shape[:2]} vs stitched {st.shape[:2]}"
            )

    mask = content_mask(ref) | content_mask(st)
    corr = correlation(ref, st, mask)
    psnr_db = psnr(ref, st, mask)
    ref_sharp = laplacian_variance(ref, mask)
    st_sharp = laplacian_variance(st, mask)
    sharp_ret = sharpness_retention(ref_sharp, st_sharp)

    st_mask = content_mask(st)
    cx0, cy0, cw, ch = content_region(st_mask)
    ox0, oy0, ow, oh = overlap_band_region(cx0, cy0, cw, ch)
    ghost = ghosting_score(st, ox0, oy0, ow, oh)

    h, w, _ = ref.shape
    diff = np.abs(ref - st)
    diff_vis = np.clip(diff * 4.0, 0, 255).astype(np.uint8)

    ref_lap = laplacian_magnitude(ref)
    st_lap = laplacian_magnitude(st)
    lap_max = max(float(ref_lap.max()), float(st_lap.max()), 1.0)
    ref_lap_vis = (np.clip(ref_lap / lap_max, 0, 1) * 255).astype(np.uint8)
    st_lap_vis = (np.clip(st_lap / lap_max, 0, 1) * 255).astype(np.uint8)
    lap_side = np.zeros((h, w * 2), dtype=np.uint8)
    lap_side[:, :w] = ref_lap_vis
    lap_side[:, w:] = st_lap_vis

    side = np.zeros((h, w * 2, 3), dtype=np.uint8)
    side[:, :w] = ref.astype(np.uint8)
    side[:, w:] = st.astype(np.uint8)

    out_dir.mkdir(parents=True, exist_ok=True)
    Image.fromarray(side).save(out_dir / "comparison.jpg", quality=92)
    Image.fromarray(diff_vis).save(out_dir / "diff.jpg", quality=92)
    Image.fromarray(lap_side).save(out_dir / "sharpness.jpg", quality=92)

    metrics = {
        "reference": str(reference.resolve()),
        "stitched": str(stitched.resolve()),
        "width": w,
        "height": h,
        "content_pixels": int(mask.sum()),
        "correlation": round(corr, 4),
        "psnr_db": round(psnr_db, 2),
        "reference_laplacian_var": round(ref_sharp, 2),
        "stitched_laplacian_var": round(st_sharp, 2),
        "sharpness_retention": round(sharp_ret, 4),
        "ghost_score": round(ghost, 4),
    }
    (out_dir / "metrics.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")
    return metrics


def main() -> None:
    p = argparse.ArgumentParser(description="Compare equirect images on PC.")
    p.add_argument("reference", type=Path)
    p.add_argument("stitched", type=Path)
    p.add_argument(
        "-o",
        "--out-dir",
        type=Path,
        default=None,
        help="Output directory (default: same as stitched)",
    )
    p.add_argument("--elevation", type=float, default=None, help="Band center (deg)")
    p.add_argument("--vfov", type=float, default=None, help="Band vertical FOV (deg)")
    p.add_argument(
        "--full",
        action="store_true",
        help="Compare full equirect images (resize stitched to reference if needed)",
    )
    args = p.parse_args()
    out_dir = args.out_dir or args.stitched.parent
    m = compare(
        args.reference,
        args.stitched,
        out_dir,
        elevation_deg=args.elevation,
        vfov_deg=args.vfov,
        full_equirect=args.full,
    )
    print(json.dumps(m, indent=2))


if __name__ == "__main__":
    main()

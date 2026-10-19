#!/usr/bin/env python3
"""PC-only resampling floor: equirect -> planar -> equirect (no JNI, no feather, no seams)."""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image

_SCRIPT_DIR = Path(__file__).resolve().parent
if str(_SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_DIR))

from compare_equirect import compare  # noqa: E402
from equirect_band import extract_equirect_lat_band, lat_band_row_range  # noqa: E402
from extract_rectilinear_views import (  # noqa: E402
    DEG2RAD,
    PI,
    extract_rectilinear,
    quat_from_axis_angle,
    quat_mul,
    quat_rotate,
    sample_equirect_bilinear,
)


def splat_planar_to_equirect(
    planar: np.ndarray,
    eq_w: int,
    eq_h: int,
    azimuth_deg: float,
    elevation_deg: float,
    fov_deg: float,
) -> tuple[np.ndarray, np.ndarray]:
    """Inverse splat planar into full equirect (bilinear sample, no feather)."""
    out_h, out_w = planar.shape[:2]
    az = azimuth_deg * DEG2RAD
    el = elevation_deg * DEG2RAD
    q_az = quat_from_axis_angle(0.0, 0.0, 1.0, -az)
    q_el = quat_from_axis_angle(1.0, 0.0, 0.0, el)
    q_look = quat_mul(q_az, q_el)

    half_fov = fov_deg * 0.5 * DEG2RAD
    tan_fov = math.tan(half_fov)
    aspect = out_w / out_h

    eq = np.zeros((eq_h, eq_w, 3), dtype=np.float64)
    weight = np.zeros((eq_h, eq_w), dtype=np.float64)

    for ey in range(eq_h):
        eq_yf = ey + 0.5
        for ex in range(eq_w):
            eq_xf = ex + 0.5
            lon = (eq_xf / eq_w - 0.5) * 2.0 * PI
            lat = (0.5 - eq_yf / eq_h) * PI
            wx = math.cos(lat) * math.sin(lon)
            wy = math.cos(lat) * math.cos(lon)
            wz = math.sin(lat)

            # Inverse of camera look: world ray -> camera ray
            q_inv = np.array([-q_look[0], -q_look[1], -q_look[2], q_look[3]])
            rx, ry, rz = quat_rotate(q_inv, np.array(wx), np.array(wy), np.array(wz))
            rx, ry, rz = float(rx), float(ry), float(rz)
            if ry <= 1e-9:
                continue
            right_off = rx / ry
            up_off = rz / ry
            if abs(right_off) > tan_fov * aspect or abs(up_off) > tan_fov:
                continue
            fx = (right_off / (tan_fov * aspect) * 0.5 + 0.5) * (out_w - 1)
            fy = (0.5 - up_off / tan_fov * 0.5) * (out_h - 1)
            x0 = int(math.floor(fx))
            y0 = int(math.floor(fy))
            if x0 < 0 or x0 >= out_w - 1 or y0 < 0 or y0 >= out_h - 1:
                continue
            fxw = fx - x0
            fyw = fy - y0
            p00 = planar[y0, x0].astype(np.float64)
            p10 = planar[y0, x0 + 1].astype(np.float64)
            p01 = planar[y0 + 1, x0].astype(np.float64)
            p11 = planar[y0 + 1, x0 + 1].astype(np.float64)
            sample = (
                p00 * (1 - fxw) * (1 - fyw)
                + p10 * fxw * (1 - fyw)
                + p01 * (1 - fxw) * fyw
                + p11 * fxw * fyw
            )
            eq[ey, ex] += sample
            weight[ey, ex] += 1.0

    mask = weight > 0
    eq[mask] /= weight[mask, None]
    return np.clip(eq, 0, 255).astype(np.uint8), weight


def main() -> None:
    images = _SCRIPT_DIR / "images"
    source = images / "img1.jpg"
    ref_band_path = images / "reference_img1_h360_v090.jpg"
    out_root = images / "roundtrip_output" / "pc_floor"
    out_root.mkdir(parents=True, exist_ok=True)

    equirect = np.asarray(Image.open(source).convert("RGB"))
    eq_h, eq_w = equirect.shape[:2]
    vfov = 90.0
    elevation = 0.0
    fov = 90.0
    azimuth = 0.0

    planar = extract_rectilinear(equirect, 1024, 1024, azimuth, elevation, fov)
    # JPEG round-trip like device assets
    planar_jpeg = out_root / "planar_az000.jpg"
    Image.fromarray(planar).save(planar_jpeg, quality=98)
    planar = np.asarray(Image.open(planar_jpeg).convert("RGB"))

    splat_full, _ = splat_planar_to_equirect(planar, eq_w, eq_h, azimuth, elevation, fov)
    band = extract_equirect_lat_band(splat_full, elevation, vfov)
    ref_band = np.asarray(Image.open(ref_band_path).convert("RGB"))

    stitched_path = out_root / "stitched_equirect.jpg"
    ref_path = out_root / "reference_equirect.jpg"
    Image.fromarray(band).save(stitched_path, quality=92)
    Image.fromarray(ref_band).save(ref_path, quality=92)

    metrics = compare(ref_path, stitched_path, out_root, elevation_deg=elevation, vfov_deg=vfov)
    print(json.dumps(metrics, indent=2))


if __name__ == "__main__":
    main()

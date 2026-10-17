#!/usr/bin/env python3
"""
Extract overlapping rectilinear (planar) views from a 360° equirectangular panorama.

Projection matches VisionTechAI nativeGetRectilinear (sphere_jni.cpp):
  earth frame X=East, Y=North, Z=Up; camera looks along +Y before azimuth/elevation.

Example:
  python scripts/extract_rectilinear_views.py scripts/images/img1.jpg
  python scripts/extract_rectilinear_views.py scripts/images/img.jpg --full-sphere --random-roll

See README_test.md (planar roundtrip — Step 1).
"""

from __future__ import annotations

import argparse
import json
import math
import random
import sys
from pathlib import Path

import numpy as np
from PIL import Image

_SCRIPT_DIR = Path(__file__).resolve().parent
if str(_SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_DIR))

from equirect_band import (  # noqa: E402
    extract_equirect_lat_band,
    lat_band_row_range,
    reference_band_filename,
)

PI = math.pi
DEG2RAD = PI / 180.0


def quat_from_axis_angle(ax: float, ay: float, az: float, angle_rad: float) -> np.ndarray:
    half = angle_rad * 0.5
    s = math.sin(half)
    return np.array([ax * s, ay * s, az * s, math.cos(half)], dtype=np.float64)


def quat_mul(q1: np.ndarray, q2: np.ndarray) -> np.ndarray:
    x1, y1, z1, w1 = q1
    x2, y2, z2, w2 = q2
    return np.array(
        [
            w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2,
            w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2,
            w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2,
            w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2,
        ],
        dtype=np.float64,
    )


def quat_rotate(q: np.ndarray, vx: np.ndarray, vy: np.ndarray, vz: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Rotate vectors (vx, vy, vz) by unit quaternion q. Arrays broadcast."""
    x, y, z, w = q
    ix = w * vx + y * vz - z * vy
    iy = w * vy + z * vx - x * vz
    iz = w * vz + x * vy - y * vx
    iw = -x * vx - y * vy - z * vz
    return (
        ix * w + iw * -x + iy * -z - iz * -y,
        iy * w + iw * -y + iz * -x - ix * -z,
        iz * w + iw * -z + ix * -y - iy * -x,
    )


def sample_equirect_bilinear(
    rgb: np.ndarray, eq_xf: np.ndarray, eq_yf: np.ndarray
) -> np.ndarray:
    """Bilinear sample RGB equirect; eq_xf wraps horizontally."""
    eq_h, eq_w, _ = rgb.shape
    x0 = np.floor(eq_xf).astype(np.int32)
    y0 = np.floor(eq_yf).astype(np.int32)
    fx = eq_xf - x0
    fy = eq_yf - y0

    x0 = np.mod(x0, eq_w)
    x1 = (x0 + 1) % eq_w
    y0 = np.clip(y0, 0, eq_h - 1)
    y1 = np.clip(y0 + 1, 0, eq_h - 1)

    c00 = rgb[y0, x0]
    c10 = rgb[y0, x1]
    c01 = rgb[y1, x0]
    c11 = rgb[y1, x1]

    w00 = (1.0 - fx) * (1.0 - fy)
    w10 = fx * (1.0 - fy)
    w01 = (1.0 - fx) * fy
    w11 = fx * fy
    out = (
        c00 * w00[..., None]
        + c10 * w10[..., None]
        + c01 * w01[..., None]
        + c11 * w11[..., None]
    )
    return np.clip(out, 0, 255).astype(np.uint8)


def extract_rectilinear(
    equirect: np.ndarray,
    out_w: int,
    out_h: int,
    azimuth_deg: float,
    elevation_deg: float,
    fov_deg: float,
    roll_deg: float = 0.0,
) -> np.ndarray:
    """
    Render a rectilinear pinhole view from an equirect RGB array (H, W, 3).
    fov_deg is the horizontal field of view; vertical FOV follows output aspect.
    """
    eq_h, eq_w = equirect.shape[:2]
    az = azimuth_deg * DEG2RAD
    el = elevation_deg * DEG2RAD
    q_az = quat_from_axis_angle(0.0, 0.0, 1.0, -az)
    q_el = quat_from_axis_angle(1.0, 0.0, 0.0, el)
    q_look = quat_mul(q_az, q_el)

    half_fov = fov_deg * 0.5 * DEG2RAD
    tan_fov = math.tan(half_fov)
    aspect = out_w / out_h

    ys = np.arange(out_h, dtype=np.float64)
    xs = np.arange(out_w, dtype=np.float64)
    x_grid, y_grid = np.meshgrid(xs, ys)

    up_off = (0.5 - y_grid / max(out_h - 1, 1)) * 2.0 * tan_fov
    right_off = (x_grid / max(out_w - 1, 1) - 0.5) * 2.0 * tan_fov * aspect

    rx = right_off
    ry = np.ones_like(right_off)
    rz = up_off
    length = np.sqrt(rx * rx + ry * ry + rz * rz)
    rx /= length
    ry /= length
    rz /= length

    roll = roll_deg * DEG2RAD
    if abs(roll) > 1e-12:
        cr = math.cos(roll)
        sr = math.sin(roll)
        rx2 = cr * rx + sr * rz
        rz2 = -sr * rx + cr * rz
        rx, rz = rx2, rz2

    wx, wy, wz = quat_rotate(q_look, rx, ry, rz)

    lon = np.arctan2(wx, wy)
    lat = np.arcsin(np.clip(wz, -1.0, 1.0))
    eq_xf = (lon / PI + 1.0) * 0.5 * eq_w
    eq_yf = (0.5 - lat / PI) * eq_h

    return sample_equirect_bilinear(equirect, eq_xf, eq_yf)


def azimuths_evenly_spaced(count: int, fov_deg: float) -> list[float]:
    """Place `count` view centers around the horizon with equal overlap."""
    if count <= 0:
        return []
    step = 360.0 / count
    return [i * step for i in range(count)]


def full_sphere_view_layout(ring_elevation_deg: float = 45.0) -> list[tuple[float, float, str]]:
    """
    Six views on the horizon, four on an upper ring + zenith, four on a lower
    ring + nadir (16 total). Ring elevation defaults to 45° for 90° hFOV overlap
    with the equator and poles.
    """
    views: list[tuple[float, float, str]] = []
    for i, az in enumerate(azimuths_evenly_spaced(6, 90.0)):
        views.append((az, 0.0, f"eq{i:02d}"))
    for i, az in enumerate((45.0, 135.0, 225.0, 315.0)):
        views.append((az, ring_elevation_deg, f"up{i:02d}"))
    views.append((0.0, 90.0, "zenith"))
    for i, az in enumerate((45.0, 135.0, 225.0, 315.0)):
        views.append((az, -ring_elevation_deg, f"lo{i:02d}"))
    views.append((0.0, -90.0, "nadir"))
    return views


def elevation_tag(elevation_deg: float) -> str:
    """Filename-safe signed elevation, e.g. el+045, el-090."""
    return f"el{elevation_deg:+.0f}".replace("+", "p").replace("-", "m")


def weighted_planar_luminance(
    view: np.ndarray, fov_deg: float, width: int, height: int
) -> float:
    """
    Solid-angle-weighted mean luminance of a rectilinear view.
    Weights by forward ray component (cos off-axis) so edge pixels stretched
    from more equirect area contribute less — matches splat cos(lat) weighting.
    """
    half_fov = fov_deg * 0.5 * DEG2RAD
    tan_fov = math.tan(half_fov)
    aspect = width / max(height, 1)
    ys = np.arange(height, dtype=np.float64)
    xs = np.arange(width, dtype=np.float64)
    xg, yg = np.meshgrid(xs, ys)
    up_off = (0.5 - yg / max(height - 1, 1)) * 2.0 * tan_fov
    right_off = (xg / max(width - 1, 1) - 0.5) * 2.0 * tan_fov * aspect
    length = np.sqrt(right_off * right_off + 1.0 + up_off * up_off)
    cos_axis = 1.0 / length
    lum = (
        0.299 * view[:, :, 0]
        + 0.587 * view[:, :, 1]
        + 0.114 * view[:, :, 2]
    )
    wgt = np.maximum(cos_axis, 0.05)
    return float(np.sum(lum * wgt) / max(np.sum(wgt), 1e-6))


def equalize_views_luminance(
    views: list[np.ndarray],
    fov_deg: float,
    width: int,
    height: int,
) -> tuple[list[np.ndarray], float, list[float]]:
    """Scale each view so solid-angle-weighted luminance matches the median."""
    lums = [weighted_planar_luminance(v, fov_deg, width, height) for v in views]
    target = float(np.median(lums))
    out: list[np.ndarray] = []
    for view, lum in zip(views, lums):
        if lum < 2.0:
            out.append(view)
            continue
        scale = float(np.clip(target / lum, 0.55, 2.0))
        out.append(np.clip(view * scale, 0, 255).astype(np.uint8))
    return out, target, lums


def jitter_view_pose(
    azimuth_deg: float,
    elevation_deg: float,
    rng: random.Random,
    heading_range_deg: float,
    elevation_range_deg: float,
) -> tuple[float, float]:
    """Apply uniform jitter to nominal look direction; keep views on the sphere."""
    az = (azimuth_deg + rng.uniform(-heading_range_deg, heading_range_deg)) % 360.0
    el_range = elevation_range_deg
    if abs(elevation_deg) >= 89.0:
        el_range = min(elevation_range_deg, 3.0)
    elif abs(elevation_deg) >= 40.0:
        el_range = elevation_range_deg * 0.75
    el = max(-90.0, min(90.0, elevation_deg + rng.uniform(-el_range, el_range)))
    return az, el


def parse_args() -> argparse.Namespace:
    script_dir = Path(__file__).resolve().parent
    default_input = script_dir / "images" / "img1.jpg"

    p = argparse.ArgumentParser(
        description="Extract overlapping rectilinear views from a 360° equirect image."
    )
    p.add_argument(
        "input",
        nargs="?",
        type=Path,
        default=default_input,
        help=f"Input equirect JPEG/PNG (default: {default_input})",
    )
    p.add_argument(
        "-o",
        "--output-dir",
        type=Path,
        default=None,
        help="Output directory (default: same as input)",
    )
    p.add_argument(
        "-n",
        "--count",
        type=int,
        default=6,
        help="Number of views along the horizontal ring (default: 6)",
    )
    p.add_argument(
        "--fov",
        type=float,
        default=90.0,
        help="Horizontal field of view per planar view in degrees (default: 90)",
    )
    p.add_argument(
        "--vfov",
        type=float,
        default=None,
        help="Vertical FOV for the 360° reference band (default: same as --fov)",
    )
    p.add_argument(
        "--elevation",
        type=float,
        default=0.0,
        help="Camera elevation in degrees (default: 0 = horizon)",
    )
    p.add_argument(
        "--width",
        type=int,
        default=1024,
        help="Output rectilinear width in pixels (default: 1024)",
    )
    p.add_argument(
        "--height",
        type=int,
        default=1024,
        help="Output rectilinear height in pixels (default: 1024)",
    )
    p.add_argument(
        "--jpeg-quality",
        type=int,
        default=98,
        help="JPEG quality for planar outputs (default: 98); ignored with --png",
    )
    p.add_argument(
        "--png",
        action="store_true",
        help="Write lossless PNG planars instead of JPEG",
    )
    p.add_argument(
        "--prefix",
        type=str,
        default="planar",
        help="Output filename prefix (default: planar)",
    )
    p.add_argument(
        "--full-sphere",
        action="store_true",
        help=(
            "Extract 16 views covering the sphere: 6 on horizon, 4 upper ring + "
            "zenith, 4 lower ring + nadir"
        ),
    )
    p.add_argument(
        "--ring-elevation",
        type=float,
        default=45.0,
        help="Elevation (deg) for upper/lower rings with --full-sphere (default: 45)",
    )
    p.add_argument(
        "--random-roll",
        action="store_true",
        help="Apply random roll about the view axis per image (see --roll-range)",
    )
    p.add_argument(
        "--roll-range",
        type=float,
        nargs=2,
        metavar=("MIN", "MAX"),
        default=(-10.0, 10.0),
        help="Roll range in degrees when using --random-roll (default: -10 10)",
    )
    p.add_argument(
        "--roll",
        type=float,
        default=0.0,
        help="Fixed roll in degrees for every view (ignored with --random-roll)",
    )
    p.add_argument(
        "--roll-seed",
        type=int,
        default=None,
        help="RNG seed for roll (default: --layout-seed)",
    )
    p.add_argument(
        "--random-heading",
        action="store_true",
        help="Jitter azimuth (heading) around each nominal view direction",
    )
    p.add_argument(
        "--heading-range",
        type=float,
        default=15.0,
        help="±degrees of azimuth jitter with --random-heading (default: 15)",
    )
    p.add_argument(
        "--random-elevation",
        action="store_true",
        help="Jitter elevation around each nominal view direction",
    )
    p.add_argument(
        "--elevation-range",
        type=float,
        default=8.0,
        help="±degrees of elevation jitter with --random-elevation (default: 8)",
    )
    p.add_argument(
        "--layout-seed",
        type=int,
        default=42,
        help="RNG seed for roll/heading/elevation jitter (default: 42)",
    )
    p.add_argument(
        "--equalize-luminance",
        action="store_true",
        help=(
            "Match solid-angle-weighted luminance across views (compensates source "
            "region brightness and rectilinear stretch)"
        ),
    )
    return p.parse_args()


def main() -> None:
    args = parse_args()
    input_path = args.input.resolve()
    if not input_path.is_file():
        raise SystemExit(f"Input not found: {input_path}")

    # Allow large equirect sources (multi-hundred-megapixel panoramas).
    Image.MAX_IMAGE_PIXELS = max(int(Image.MAX_IMAGE_PIXELS or 0), 500_000_000)

    out_dir = (args.output_dir or input_path.parent).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    img = Image.open(input_path).convert("RGB")
    equirect = np.asarray(img)
    eq_w, eq_h = img.size
    if eq_w < 2 * eq_h * 0.9:
        print(
            f"Warning: input is {eq_w}x{eq_h} — expected ~2:1 equirectangular panorama."
        )

    azimuths = azimuths_evenly_spaced(args.count, args.fov)
    vfov = args.vfov if args.vfov is not None else args.fov

    if args.full_sphere:
        view_specs = [
            (az, el, label)
            for az, el, label in full_sphere_view_layout(args.ring_elevation)
        ]
    else:
        view_specs = [(az, args.elevation, f"ring{i:02d}") for i, az in enumerate(azimuths)]

    roll_min, roll_max = args.roll_range
    if roll_min > roll_max:
        roll_min, roll_max = roll_max, roll_min
    layout_seed = args.layout_seed
    roll_seed = args.roll_seed if args.roll_seed is not None else layout_seed
    rng = random.Random(roll_seed)

    overlap = (
        max(0.0, args.fov - (360.0 / 6))
        if args.full_sphere
        else max(0.0, args.fov - (360.0 / args.count))
    )
    y0, y1 = lat_band_row_range(eq_h, args.elevation if not args.full_sphere else 0.0, vfov)
    layout = "full sphere (16 views)" if args.full_sphere else f"{len(view_specs)} horizon views"
    roll_note = (
        f"random roll in [{roll_min:g}, {roll_max:g}]°"
        if args.random_roll
        else f"roll {args.roll:g}°"
    )
    pose_notes: list[str] = [roll_note]
    if args.random_heading:
        pose_notes.append(f"heading ±{args.heading_range:g}°")
    if args.random_elevation:
        pose_notes.append(f"elevation ±{args.elevation_range:g}°")
    print(
        f"Input: {input_path.name} ({eq_w}x{eq_h})\n"
        f"Extracting {layout} @ {args.fov}° hFOV, {', '.join(pose_notes)}\n"
        f"Horizon overlap (6-ring): ~{overlap:.1f}° between neighbors\n"
        f"Reference band: 360° × {vfov}° vFOV @ el=0° -> rows [{y0}:{y1}] ({y1 - y0}px tall)"
    )

    stem = input_path.stem
    saved: list[Path] = []
    manifest_frames: list[dict] = []
    extracted_views: list[np.ndarray] = []
    pending_meta: list[dict] = []
    for i, (nom_az, nom_el, label) in enumerate(view_specs):
        az, el = nom_az, nom_el
        if args.random_heading or args.random_elevation:
            az, el = jitter_view_pose(
                nom_az,
                nom_el,
                rng,
                args.heading_range if args.random_heading else 0.0,
                args.elevation_range if args.random_elevation else 0.0,
            )
        roll_deg = (
            rng.uniform(roll_min, roll_max)
            if args.random_roll
            else args.roll
        )
        view = extract_rectilinear(
            equirect,
            args.width,
            args.height,
            azimuth_deg=az,
            elevation_deg=el,
            fov_deg=args.fov,
            roll_deg=roll_deg,
        )
        extracted_views.append(view)
        pending_meta.append(
            {
                "i": i,
                "nom_az": nom_az,
                "nom_el": nom_el,
                "label": label,
                "az": az,
                "el": el,
                "roll_deg": roll_deg,
            }
        )

    if args.equalize_luminance and extracted_views:
        extracted_views, lum_target, lum_before = equalize_views_luminance(
            extracted_views, args.fov, args.width, args.height
        )
        print(
            f"Luminance equalize: target={lum_target:.1f} "
            f"(per-view before: {', '.join(f'{x:.0f}' for x in lum_before)})"
        )

    for view, meta in zip(extracted_views, pending_meta):
        i = meta["i"]
        nom_az = meta["nom_az"]
        nom_el = meta["nom_el"]
        label = meta["label"]
        az = meta["az"]
        el = meta["el"]
        roll_deg = meta["roll_deg"]
        el_tag = elevation_tag(el)
        out_path = (
            out_dir
            / f"{args.prefix}_{stem}_{i:02d}_az{az:03.0f}_{el_tag}.{'png' if args.png else 'jpg'}"
        )
        if args.png:
            Image.fromarray(view).save(out_path)
        else:
            Image.fromarray(view).save(out_path, quality=args.jpeg_quality)
        saved.append(out_path)
        manifest_frames.append(
            {
                "file": out_path.name,
                "index": i,
                "label": label,
                "nominal_azimuth": round(nom_az, 3),
                "nominal_elevation": round(nom_el, 3),
                "azimuth": round(az, 3),
                "elevation": round(el, 3),
                "roll": round(roll_deg, 3),
            }
        )
        jitter_note = ""
        if args.random_heading or args.random_elevation:
            jitter_note = f" (nom az={nom_az:.1f}° el={nom_el:+.1f}°)"
        print(
            f"  [{i + 1}/{len(view_specs)}] {label} az={az:6.1f}° el={el:+6.1f}° "
            f"roll={roll_deg:+5.1f}°{jitter_note} -> {out_path.name}"
        )

    band = extract_equirect_lat_band(equirect, 0.0 if args.full_sphere else args.elevation, vfov)
    ref_path = out_dir / reference_band_filename(stem, vfov)
    Image.fromarray(band).save(ref_path, quality=args.jpeg_quality)
    saved.append(ref_path)
    print(
        f"  [ref] 360°×{vfov}° band ({band.shape[1]}x{band.shape[0]}) -> {ref_path.name}"
    )

    manifest_path = out_dir / f"{args.prefix}_{stem}_manifest.json"
    manifest = {
        "source": input_path.name,
        "fov": args.fov,
        "vfov": vfov,
        "full_sphere": args.full_sphere,
        "ring_elevation": args.ring_elevation if args.full_sphere else None,
        "random_roll": args.random_roll,
        "roll_range": [roll_min, roll_max] if args.random_roll else None,
        "roll_seed": roll_seed,
        "random_heading": args.random_heading,
        "heading_range": args.heading_range if args.random_heading else None,
        "random_elevation": args.random_elevation,
        "elevation_range": args.elevation_range if args.random_elevation else None,
        "layout_seed": layout_seed,
        "equalize_luminance": args.equalize_luminance,
        "frames": manifest_frames,
        "reference_band": ref_path.name,
    }
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    saved.append(manifest_path)
    print(f"  [manifest] -> {manifest_path.name}")

    print(f"Done. Wrote {len(saved)} files to {out_dir}")


if __name__ == "__main__":
    main()

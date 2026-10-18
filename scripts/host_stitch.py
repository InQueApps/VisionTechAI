#!/usr/bin/env python3
"""
Host-side replay of the production sphere stitcher (no phone needed).

Builds app/src/robotics/cpp/panorama/sphere_jni.cpp unchanged into a desktop
executable (zig c++), renders synthetic capture frames from an equirect source,
replays the same native call sequence as SphereProductionStitchPipeline, and
scores the result against ground truth.

Accuracy metric (primary): solid-angle-weighted luminance correlation between
the stitched panorama and the source after removing one global rotation (the
phone cannot know absolute heading, so a rigidly rotated but consistent
panorama is not a stitching error). Per-frame pose error vs ground truth is
reported alongside, also gauge-aligned.

Examples:
  python3 scripts/host_stitch.py run --imu random:8
  python3 scripts/host_stitch.py run --imu drift:20 --photo exposure=0.7,wb=0.12,vignette=0.35
  python3 scripts/host_stitch.py suite
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
from PIL import Image

_SCRIPT_DIR = Path(__file__).resolve().parent
_REPO = _SCRIPT_DIR.parent
if str(_SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_DIR))

import extract_rectilinear_views as _erv  # noqa: E402
from extract_rectilinear_views import (  # noqa: E402
    extract_rectilinear,
    full_sphere_view_layout,
    jitter_view_pose,
)

# The stitcher (and the reference metric) treat equirect pixel i as covering [i, i+1),
# centre i+0.5. extract_rectilinear samples with centres at integer i, which renders every
# view half a source pixel off its nominal pose. Render with the stitcher's convention.
_erv_sample = _erv.sample_equirect_bilinear
_erv.sample_equirect_bilinear = lambda rgb, xf, yf: _erv_sample(rgb, xf - 0.5, yf - 0.5)
RENDER_CONVENTION = "center+0.5"

NATIVE_DIR = _REPO / "app" / "src" / "robotics" / "cpp"
HARNESS_DIR = NATIVE_DIR / "test" / "host_stitch"
BUILD_DIR = _REPO / "build" / "host_stitch"
DEFAULT_SOURCE = _REPO / "scripts" / "images" / "input_images" / "img_eq4096.jpg"
ACCURACY_TARGET = 0.99

Image.MAX_IMAGE_PIXELS = 500_000_000


# ── build ────────────────────────────────────────────────────────────────


def find_jni_header() -> Path:
    roots: list[Path] = []
    for var in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"):
        if os.environ.get(var):
            roots.append(Path(os.environ[var]))
    sdk_dirs = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    if os.environ.get("LOCALAPPDATA"):
        sdk_dirs.append(str(Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk"))
    for sdk in filter(None, sdk_dirs):
        ndk_root = Path(sdk) / "ndk"
        if ndk_root.is_dir():
            roots.extend(sorted(ndk_root.iterdir(), reverse=True))
    for root in roots:
        for hdr in root.glob("toolchains/llvm/prebuilt/*/sysroot/usr/include/jni.h"):
            return hdr
    raise SystemExit("jni.h not found — install the Android NDK or set ANDROID_NDK_HOME")


def build(force: bool = False) -> Path:
    exe = BUILD_DIR / ("host_stitch.exe" if os.name == "nt" else "host_stitch")
    sources = [
        *(NATIVE_DIR / "panorama").glob("*.cpp"),
        *(NATIVE_DIR / "panorama").glob("*.h"),
        *(NATIVE_DIR / "panorama").glob("*.inl"),
        *HARNESS_DIR.rglob("*.*"),
    ]
    newest = max(p.stat().st_mtime for p in sources)
    if not force and exe.is_file() and exe.stat().st_mtime >= newest:
        return exe
    include_dir = BUILD_DIR / "include"
    include_dir.mkdir(parents=True, exist_ok=True)
    (include_dir / "jni.h").write_bytes(find_jni_header().read_bytes())
    cmd = [
        sys.executable, "-m", "ziglang", "c++",
        "-std=c++17", "-O2", "-fno-sanitize=undefined",
        "-I", str(include_dir),
        "-I", str(HARNESS_DIR),
        "-I", str(NATIVE_DIR / "panorama"),
        str(HARNESS_DIR / "host_stitch_main.cpp"),
        "-o", str(exe),
    ]
    if os.name == "nt":
        cmd[5:5] = ["-target", "x86_64-windows-gnu"]
    t0 = time.time()
    print("Building host stitcher …", flush=True)
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        sys.stderr.write(proc.stdout + proc.stderr)
        raise SystemExit("host stitcher build failed")
    print(f"Built {exe.name} in {time.time() - t0:.1f}s", flush=True)
    return exe


# ── quaternions (x, y, z, w), matching QuaternionMath.kt ────────────────


def q_axis_angle(ax: float, ay: float, az: float, angle: float) -> np.ndarray:
    n = math.sqrt(ax * ax + ay * ay + az * az)
    if n < 1e-12:
        return np.array([0.0, 0.0, 0.0, 1.0])
    s = math.sin(angle * 0.5) / n
    return np.array([ax * s, ay * s, az * s, math.cos(angle * 0.5)])


def q_mul(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    return np.array([
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    ])


def q_conj(q: np.ndarray) -> np.ndarray:
    return np.array([-q[0], -q[1], -q[2], q[3]])


def q_angle_deg(a: np.ndarray, b: np.ndarray) -> float:
    d = abs(float(np.dot(a / np.linalg.norm(a), b / np.linalg.norm(b))))
    return math.degrees(2.0 * math.acos(min(1.0, d)))


def q_to_matrix(q: np.ndarray) -> np.ndarray:
    x, y, z, w = q / np.linalg.norm(q)
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ])


def q_mean(qs: list[np.ndarray]) -> np.ndarray:
    """Chordal L2 mean (principal eigenvector of sum q q^T)."""
    m = np.zeros((4, 4))
    for q in qs:
        qn = q / np.linalg.norm(q)
        m += np.outer(qn, qn)
    _, vecs = np.linalg.eigh(m)
    return vecs[:, -1]


def truth_quat(az_deg: float, el_deg: float, roll_deg: float) -> np.ndarray:
    """SpherePlanarReplayE2ETest.quaternionForExtractedPlanarView."""
    q_az = q_axis_angle(0, 0, 1, math.radians(-az_deg))
    q_el = q_axis_angle(1, 0, 0, math.radians(el_deg))
    q_roll = q_axis_angle(0, 1, 0, math.radians(roll_deg))
    q_pitch = q_axis_angle(1, 0, 0, math.radians(90.0))
    return q_mul(q_mul(q_mul(q_az, q_el), q_roll), q_pitch)


# ── scenario generation ──────────────────────────────────────────────────


@dataclass
class ViewSpec:
    az: float
    el: float
    roll: float
    label: str
    corrupt: str = ""  # "", "blur", "garbage"


def layout_sphere16(rng: np.random.Generator, jitter: bool) -> list[ViewSpec]:
    import random as _random

    py_rng = _random.Random(int(rng.integers(1 << 30)))
    views: list[ViewSpec] = []
    for az, el, label in full_sphere_view_layout(45.0):
        roll = 0.0
        if jitter:
            az, el = jitter_view_pose(az, el, py_rng, 15.0, 8.0)
            roll = py_rng.uniform(-10.0, 10.0)
        views.append(ViewSpec(az, el, roll, label))
    return views


def layout_video(rng: np.random.Generator, step_deg: float, fov: float) -> list[ViewSpec]:
    """Continuous serpentine sweep like a handheld video: horizon, +45 ring, zenith,
    -45 ring, nadir. Small hand jitter on every frame."""
    views: list[ViewSpec] = []

    def hand() -> tuple[float, float, float]:
        return (float(rng.normal(0, 1.5)), float(rng.normal(0, 1.5)), float(rng.normal(0, 3.0)))

    def ring(el: float, start: float, reverse: bool, step: float) -> None:
        n = max(4, int(round(360.0 / step)))
        for k in range(n):
            a = start + (n - k if reverse else k) * 360.0 / n
            ja, je, jr = hand()
            views.append(ViewSpec((a + ja) % 360.0, el + je, jr, f"r{int(el):+d}_{k}"))

    ring(0.0, 0.0, False, step_deg)
    ring(45.0, 0.0, True, step_deg / math.cos(math.radians(45.0)) * 0.9)
    views.append(ViewSpec(0.0, 89.0, 0.0, "zenith"))
    ring(-45.0, 0.0, False, step_deg / math.cos(math.radians(45.0)) * 0.9)
    views.append(ViewSpec(0.0, -89.0, 0.0, "nadir"))
    return views


def parse_kv(spec: str) -> dict[str, float]:
    out: dict[str, float] = {}
    for part in filter(None, (spec or "").split(",")):
        k, _, v = part.partition("=")
        out[k.strip()] = float(v) if v else 1.0
    return out


def imu_seeds(truth: list[np.ndarray], spec: str, seed: int) -> list[np.ndarray]:
    """IMU error models.
    random:D   independent random-axis error up to D deg per frame (E2E degrade model)
    drift:D    heading drift ramping to D deg across the capture + 0.6 deg noise (gyro-like)
    jump:D@K   correct until frame K, then a constant D deg heading jump
    none
    """
    kind, _, arg = (spec or "none").partition(":")
    n = len(truth)
    out: list[np.ndarray] = []
    for i, qt in enumerate(truth):
        r = np.random.default_rng(seed * 7919 + i * 31)
        if kind == "none":
            q_err = np.array([0.0, 0.0, 0.0, 1.0])
        elif kind == "random":
            deg = float(arg)
            angle = math.radians(deg * r.random())
            z = 2.0 * r.random() - 1.0
            phi = 2.0 * math.pi * r.random()
            s = math.sqrt(max(0.0, 1.0 - z * z))
            q_err = q_axis_angle(s * math.cos(phi), s * math.sin(phi), z, angle)
        elif kind == "drift":
            deg = float(arg)
            heading = math.radians(deg * i / max(1, n - 1))
            noise = r.normal(0.0, math.radians(0.6), 3)
            q_err = q_mul(q_axis_angle(0, 0, 1, heading), q_axis_angle(*noise, float(np.linalg.norm(noise))))
        elif kind == "jump":
            deg_s, _, at_s = arg.partition("@")
            at = int(at_s) if at_s else n // 2
            q_err = q_axis_angle(0, 0, 1, math.radians(float(deg_s))) if i >= at else np.array([0.0, 0.0, 0.0, 1.0])
        else:
            raise SystemExit(f"unknown --imu model {spec}")
        out.append(q_mul(q_err, qt))
    return out


def photometric(
    rgb: np.ndarray,
    params: dict[str, float],
    r: np.random.Generator,
    frame_index: int = 0,
    frame_count: int = 1,
) -> tuple[np.ndarray, float]:
    """Per-frame camera effects. Returns (image, camera_ev) where camera_ev is
    linear ISO×t relative to the first frame, or 0 if unknown."""
    img = rgb.astype(np.float32)
    h, w, _ = img.shape
    camera_ev = 0.0
    if params.get("ae_linear"):
        # First frame is the look. Other frames hunt ±N stops in linear light.
        if frame_index == 0:
            camera_ev = 1.0
        else:
            camera_ev = float(2.0 ** r.uniform(-params["ae_linear"], params["ae_linear"]))
        u = np.clip(img / 255.0, 0.0, 1.0)
        lin = np.where(u <= 0.04045, u / 12.92, ((u + 0.055) / 1.055) ** 2.4)
        lin *= camera_ev
        enc = np.where(
            lin <= 0.0031308,
            12.92 * lin,
            1.055 * np.power(np.clip(lin, 0.0, None), 1.0 / 2.4) - 0.055,
        )
        img = np.clip(enc * 255.0, 0.0, 255.0)
    elif params.get("exposure"):
        img *= 2.0 ** r.uniform(-params["exposure"], params["exposure"])
    if params.get("wb"):
        img *= (1.0 + r.uniform(-params["wb"], params["wb"], 3)).astype(np.float32)
    if params.get("dc"):
        img += r.uniform(-params["dc"], params["dc"], 3).astype(np.float32)
    if params.get("vignette"):
        yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
        rr = ((xx - w / 2) / (w / 2)) ** 2 + ((yy - h / 2) / (h / 2)) ** 2
        img *= (1.0 - params["vignette"] * np.clip(rr / 2.0, 0, 1))[..., None]
    if params.get("noise"):
        img += r.normal(0, params["noise"], img.shape).astype(np.float32)
    return np.clip(img, 0, 255).astype(np.uint8), camera_ev


def corrupt_frame(rgb: np.ndarray, kind: str, r: np.random.Generator) -> np.ndarray:
    if kind == "blur":
        from PIL import ImageFilter

        img = Image.fromarray(rgb)
        k = max(9, rgb.shape[1] // 25)
        return np.asarray(img.filter(ImageFilter.BoxBlur(k)))
    if kind == "garbage":
        return r.integers(0, 255, rgb.shape, dtype=np.uint8)
    return rgb


@dataclass
class Scenario:
    name: str
    source: Path = DEFAULT_SOURCE
    layout: str = "sphere16"
    size: int = 1024
    fov: float = 90.0
    jitter: bool = True
    imu: str = "none"
    photo: str = ""
    corrupt: str = ""          # e.g. "blur@3,garbage@9"
    video_step: float = 20.0
    seed: int = 42
    gate: float = 0.10
    export_w: int = 4096
    export_h: int = 2048
    eq_w: int = 1024
    eq_h: int = 512
    extra: dict = field(default_factory=dict)


def render_views(sc: Scenario, views: list[ViewSpec], equirect: np.ndarray) -> list[np.ndarray]:
    key = hashlib.sha1(
        json.dumps([RENDER_CONVENTION, str(sc.source), sc.size, sc.fov,
                    [(v.az, v.el, v.roll) for v in views]]).encode()
    ).hexdigest()[:16]
    cache = BUILD_DIR / "cache" / key
    cache.mkdir(parents=True, exist_ok=True)
    frames: list[np.ndarray] = []
    for i, v in enumerate(views):
        p = cache / f"{i:03d}.png"
        if p.is_file():
            frames.append(np.asarray(Image.open(p).convert("RGB")))
            continue
        img = extract_rectilinear(equirect, sc.size, sc.size, v.az, v.el, sc.fov, v.roll)
        Image.fromarray(img).save(p)
        frames.append(img)
    return frames


_SOURCE_CACHE: dict[str, np.ndarray] = {}


def load_source(path: Path) -> np.ndarray:
    key = str(path.resolve())
    if key not in _SOURCE_CACHE:
        _SOURCE_CACHE[key] = np.asarray(Image.open(path).convert("RGB"))
    return _SOURCE_CACHE[key]


def write_argb(path: Path, rgb: np.ndarray) -> None:
    r = rgb[..., 0].astype(np.uint32)
    g = rgb[..., 1].astype(np.uint32)
    b = rgb[..., 2].astype(np.uint32)
    argb = (np.uint32(0xFF) << 24) | (r << 16) | (g << 8) | b
    argb.astype("<u4").tofile(path)


def read_argb(path: Path, w: int, h: int) -> tuple[np.ndarray, np.ndarray]:
    raw = np.fromfile(path, dtype="<u4").reshape(h, w)
    rgb = np.stack([(raw >> 16) & 0xFF, (raw >> 8) & 0xFF, raw & 0xFF], axis=-1).astype(np.uint8)
    return rgb, (raw >> 24) > 0


# ── evaluation ───────────────────────────────────────────────────────────


def equirect_dirs(w: int, h: int) -> tuple[np.ndarray, np.ndarray]:
    """World unit vectors per pixel center (X=East, Y=North, Z=Up) and cos(lat) weights."""
    xs = (np.arange(w) + 0.5) / w
    ys = (np.arange(h) + 0.5) / h
    lon = (xs * 2.0 - 1.0) * math.pi
    lat = (0.5 - ys) * math.pi
    lon_g, lat_g = np.meshgrid(lon, lat)
    d = np.stack([np.cos(lat_g) * np.sin(lon_g), np.cos(lat_g) * np.cos(lon_g), np.sin(lat_g)], axis=-1)
    return d, np.cos(lat_g)


def sample_equirect(img: np.ndarray, dirs: np.ndarray) -> np.ndarray:
    h, w = img.shape[:2]
    lon = np.arctan2(dirs[..., 0], dirs[..., 1])
    lat = np.arcsin(np.clip(dirs[..., 2], -1, 1))
    x = ((lon / math.pi + 1.0) * 0.5 * w).astype(np.int64) % w
    y = np.clip(((0.5 - lat / math.pi) * h).astype(np.int64), 0, h - 1)
    return img[y, x]


def rotate_equirect_bilinear(
    rgb: np.ndarray, valid: np.ndarray, rot: np.ndarray, rows: int = 256
) -> tuple[np.ndarray, np.ndarray]:
    """out(d) = rgb(rot · d), bilinear, processed in row chunks (float32)."""
    h, w = rgb.shape[:2]
    out = np.empty_like(rgb)
    out_valid = np.empty((h, w), dtype=np.uint8)
    src = rgb.astype(np.float32)
    rot32 = rot.astype(np.float32)
    lon = ((np.arange(w, dtype=np.float32) + 0.5) / w * 2.0 - 1.0) * np.float32(math.pi)
    for y0 in range(0, h, rows):
        y1 = min(h, y0 + rows)
        lat = (0.5 - (np.arange(y0, y1, dtype=np.float32) + 0.5) / h) * np.float32(math.pi)
        lon_g, lat_g = np.meshgrid(lon, lat)
        cl = np.cos(lat_g)
        d = np.stack([cl * np.sin(lon_g), cl * np.cos(lon_g), np.sin(lat_g)], axis=-1) @ rot32.T
        slon = np.arctan2(d[..., 0], d[..., 1])
        slat = np.arcsin(np.clip(d[..., 2], -1, 1))
        xf = (slon / math.pi + 1.0) * 0.5 * w - 0.5
        yf = np.clip((0.5 - slat / math.pi) * h - 0.5, 0, h - 1.001)
        x0 = np.floor(xf).astype(np.int64)
        y0i = np.floor(yf).astype(np.int64)
        ax = (xf - x0)[..., None]
        ay = (yf - y0i)[..., None]
        x0 %= w
        x1 = (x0 + 1) % w
        y1i = np.minimum(y0i + 1, h - 1)
        val = ((1 - ax) * (1 - ay) * src[y0i, x0] + ax * (1 - ay) * src[y0i, x1] +
               (1 - ax) * ay * src[y1i, x0] + ax * ay * src[y1i, x1])
        out[y0:y1] = np.clip(val + 0.5, 0, 255).astype(np.uint8)
        out_valid[y0:y1] = (valid[y0i, x0] & valid[y1i, x1]).astype(np.uint8) * 255
    return out, out_valid


def weighted_corr(a: np.ndarray, b: np.ndarray, wts: np.ndarray) -> float:
    wsum = wts.sum()
    if wsum <= 0:
        return 0.0
    ma = (a * wts).sum() / wsum
    mb = (b * wts).sum() / wsum
    da, db = a - ma, b - mb
    va = (wts * da * da).sum()
    vb = (wts * db * db).sum()
    if va <= 1e-9 or vb <= 1e-9:
        return 0.0
    return float((wts * da * db).sum() / math.sqrt(va * vb))


def luminance(rgb: np.ndarray) -> np.ndarray:
    rgb = rgb.astype(np.float64)
    return 0.299 * rgb[..., 0] + 0.587 * rgb[..., 1] + 0.114 * rgb[..., 2]


def evaluate(
    out_dir: Path,
    sc: Scenario,
    truth: list[np.ndarray],
    stored_index: list[int],
    eval_w: int = 2048,
) -> dict:
    st_rgb, st_valid = read_argb(out_dir / "equirect.argb", sc.export_w, sc.export_h)
    poses = np.loadtxt(out_dir / "poses.txt", ndmin=2)
    refined = [row[1:5] for row in poses]

    # Gauge: G maps stitched world → true world. q_truth ≈ G ⊗ q_refined.
    # Untrusted (discarded / unaligned) frames are not rendered, so they must not bias the gauge.
    n_pairs = min(len(refined), len(stored_index))
    trusted_idx = [i for i in range(n_pairs) if int(poses[i][13])] or list(range(n_pairs))
    pairs = [(truth[stored_index[i]], refined[i]) for i in trusted_idx]
    rel = [q_mul(qt, q_conj(qr)) for qt, qr in pairs]
    gauge = q_mean(rel) if rel else np.array([0.0, 0.0, 0.0, 1.0])
    pose_err = [q_angle_deg(q_mul(gauge, qr), qt) for qt, qr in pairs]
    raw_err = [q_angle_deg(qr, qt) for qt, qr in pairs]

    eval_h = eval_w // 2
    src = load_source(sc.source)
    ref = np.asarray(Image.fromarray(src).resize((eval_w, eval_h), Image.Resampling.LANCZOS))
    dirs, cos_lat = equirect_dirs(eval_w, eval_h)
    st_small = np.asarray(Image.fromarray(st_rgb).resize((eval_w, eval_h), Image.Resampling.LANCZOS))
    valid_small = np.asarray(Image.fromarray(st_valid.astype(np.uint8) * 255).resize((eval_w, eval_h))) > 127

    # Remove the gauge at export resolution, then downscale like the reference
    # (rotating the already-downscaled image would blur it a second time).
    g_inv = q_to_matrix(q_conj(gauge))
    aligned_full, aligned_valid_full = rotate_equirect_bilinear(st_rgb, st_valid, g_inv)
    aligned = np.asarray(Image.fromarray(aligned_full).resize((eval_w, eval_h), Image.Resampling.LANCZOS))
    aligned_valid = np.asarray(Image.fromarray(aligned_valid_full).resize((eval_w, eval_h))) > 127

    lum_ref = luminance(ref)
    lum_al = luminance(aligned)
    wts_raw = cos_lat * valid_small
    wts_al = cos_lat * aligned_valid
    acc_raw = weighted_corr(luminance(st_small), lum_ref, wts_raw)
    acc = weighted_corr(lum_al, lum_ref, wts_al)
    coverage = float((cos_lat * aligned_valid).sum() / cos_lat.sum())
    valid_mask = aligned_valid > 0
    med_st = float(np.median(lum_al[valid_mask])) if np.any(valid_mask) else 0.0
    med_ref = float(np.median(lum_ref[valid_mask])) if np.any(valid_mask) else 0.0
    diff = aligned.astype(np.float64) - ref.astype(np.float64)
    mse = float((wts_al[..., None] * diff * diff).sum() / max(1e-9, 3.0 * wts_al.sum()))
    psnr = 99.0 if mse < 1e-6 else 10.0 * math.log10(255.0 ** 2 / mse)

    side = np.concatenate([ref, aligned, np.clip(np.abs(diff) * 3, 0, 255).astype(np.uint8)], axis=0)
    Image.fromarray(side).resize((eval_w // 2, eval_h * 3 // 2)).save(out_dir / "comparison.jpg", quality=88)
    Image.fromarray(st_rgb).save(out_dir / "stitched_equirect.jpg", quality=92)

    timing = json.loads((out_dir / "timing.json").read_text())
    # Hole-filled pixels export as covered; count them as uncovered here.
    coverage = min(coverage, 1.0 - float(timing.get("hole_fraction", 0.0)))
    pe = np.array(pose_err) if pose_err else np.array([float("nan")])
    return {
        "scenario": sc.name,
        "accuracy": round(acc, 4),
        "accuracy_no_gauge": round(acc_raw, 4),
        "coverage": round(coverage, 4),
        "median_luma": round(med_st, 1),
        "median_luma_ref": round(med_ref, 1),
        "psnr_db": round(psnr, 2),
        "pose_err_mean_deg": round(float(np.nanmean(pe)), 3),
        "pose_err_p90_deg": round(float(np.nanpercentile(pe, 90)), 3),
        "pose_err_max_deg": round(float(np.nanmax(pe)), 3),
        "gauge_deg": round(q_angle_deg(gauge, np.array([0.0, 0.0, 0.0, 1.0])), 3),
        "frames_in": len(truth),
        "frames_stored": len(refined),
        "frames_trusted": int(sum(int(row[13]) for row in poses)),
        "pose_err_deg": [round(e, 3) for e in pose_err],
        "raw_pose_err_deg": [round(e, 3) for e in raw_err],
        "timing_ms": timing["steps"],
        "frame_ms_mean": round(float(np.mean(timing["frame_ms"])), 1),
        "frame_ms_max": round(float(np.max(timing["frame_ms"])), 1),
        "frame_status": timing["frame_status"],
        "discarded": timing.get("discarded", []),
        "total_ms": round(sum(timing["steps"].values()), 1),
    }


# ── run ──────────────────────────────────────────────────────────────────


def run_scenario(sc: Scenario, exe: Path, out_root: Path, log: bool = False) -> dict:
    rng = np.random.default_rng(sc.seed)
    if sc.layout == "sphere16":
        views = layout_sphere16(rng, sc.jitter)
    elif sc.layout == "video":
        views = layout_video(rng, sc.video_step, sc.fov)
    else:
        raise SystemExit(f"unknown layout {sc.layout}")
    for part in filter(None, sc.corrupt.split(",")):
        kind, _, idx = part.partition("@")
        views[int(idx)].corrupt = kind

    equirect = load_source(sc.source)
    frames = render_views(sc, views, equirect)
    truth = [truth_quat(v.az, v.el, v.roll) for v in views]
    seeds = imu_seeds(truth, sc.imu, sc.seed)
    photo = parse_kv(sc.photo)

    out_dir = out_root / sc.name
    out_dir.mkdir(parents=True, exist_ok=True)
    frame_dir = out_dir / "frames"
    frame_dir.mkdir(exist_ok=True)
    lines = [
        f"eq {sc.eq_w} {sc.eq_h}",
        f"fov {sc.fov} {sc.fov}",
        f"export {sc.export_w} {sc.export_h}",
        "pipeline production",
    ]
    pass_ev = photo.get("pass_ev", 0.0) > 0.5
    for i, (v, img, q) in enumerate(zip(views, frames, seeds)):
        r = np.random.default_rng(sc.seed * 104729 + i)
        camera_ev = 0.0
        if photo:
            img, camera_ev = photometric(img, photo, r, frame_index=i, frame_count=len(views))
        img = corrupt_frame(img, v.corrupt, r)
        p = frame_dir / f"{i:03d}.argb"
        write_argb(p, img)
        gate = 0.0 if i == 0 else sc.gate
        line = (
            f"frame {p} {img.shape[1]} {img.shape[0]} "
            f"{q[0]:.9f} {q[1]:.9f} {q[2]:.9f} {q[3]:.9f} {gate}"
        )
        if pass_ev and camera_ev > 0:
            line += f" {camera_ev:.9f}"
        lines.append(line)
    (out_dir / "job.txt").write_text("\n".join(lines) + "\n")

    env = dict(os.environ)
    if log:
        env["SPHERE_HOST_LOG"] = "1"
    t0 = time.time()
    with open(out_dir / "native.log", "w", encoding="utf-8", errors="replace") as lf:
        proc = subprocess.run([str(exe), str(out_dir / "job.txt"), str(out_dir)], stderr=lf, env=env)
    wall = time.time() - t0
    if proc.returncode != 0:
        raise SystemExit(f"host stitcher failed ({proc.returncode}); see {out_dir / 'native.log'}")

    # Map stored frames back to input indices (rejected / gated frames are not stored).
    status = json.loads((out_dir / "timing.json").read_text())["frame_status"]
    stored_index = [i for i, s in enumerate(status) if s == 1]
    metrics = evaluate(out_dir, sc, truth, stored_index)
    metrics["wall_s"] = round(wall, 2)
    metrics["config"] = {k: (str(v) if isinstance(v, Path) else v) for k, v in sc.__dict__.items()}
    (out_dir / "metrics.json").write_text(json.dumps(metrics, indent=2))
    for p in frame_dir.glob("*.argb"):
        p.unlink()
    return metrics


def print_row(m: dict) -> None:
    ok = "PASS" if m["accuracy"] >= ACCURACY_TARGET and m["coverage"] >= ACCURACY_TARGET else "FAIL"
    print(
        f"{ok} {m['scenario']:<28} acc={m['accuracy']:.4f} (raw {m['accuracy_no_gauge']:.4f}) "
        f"cov={m['coverage']:.3f} luma={m.get('median_luma', 0):.0f}/{m.get('median_luma_ref', 0):.0f} "
        f"psnr={m['psnr_db']:5.2f} "
        f"pose mean/p90/max={m['pose_err_mean_deg']:.2f}/{m['pose_err_p90_deg']:.2f}/{m['pose_err_max_deg']:.2f}deg "
        f"stored={m['frames_stored']}/{m['frames_in']} trusted={m['frames_trusted']} "
        f"frame={m['frame_ms_mean']:.0f}ms(max {m['frame_ms_max']:.0f}) total={m['total_ms'] / 1000:.1f}s",
        flush=True,
    )


SUITE: list[dict] = [
    dict(name="s16_imu0", imu="none"),
    dict(name="s16_rand4", imu="random:4"),
    dict(name="s16_rand8", imu="random:8"),
    dict(name="s16_rand16", imu="random:16"),
    dict(name="s16_drift30", imu="drift:30"),
    dict(name="s16_jump60", imu="jump:60@7"),
    dict(name="s16_light", imu="random:4", photo="exposure=0.7,wb=0.12,dc=12,vignette=0.35,noise=3"),
    dict(name="s16_ae_ev", imu="none", photo="ae_linear=1.5,pass_ev=1", jitter=False, size=1024,
         export_w=4096, export_h=2048),
    dict(name="s16_ae_noev", imu="none", photo="ae_linear=1.5", jitter=False, size=1024,
         export_w=4096, export_h=2048),
    dict(name="s16_bad_frames", imu="random:4", corrupt="blur@3,garbage@9"),
    dict(name="video_drift", layout="video", imu="drift:20", photo="exposure=0.4,wb=0.06,vignette=0.25,noise=2",
         size=640, corrupt="blur@5,blur@14"),
]


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    pr = sub.add_parser("run")
    pr.add_argument("--name", default=None)
    pr.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    pr.add_argument("--layout", default="sphere16", choices=["sphere16", "video"])
    pr.add_argument("--size", type=int, default=1024)
    pr.add_argument("--imu", default="none")
    pr.add_argument("--photo", default="")
    pr.add_argument("--corrupt", default="")
    pr.add_argument("--no-jitter", action="store_true")
    pr.add_argument("--seed", type=int, default=42)
    pr.add_argument("--export", type=int, default=4096)
    pr.add_argument("--log", action="store_true", help="capture native LOGI output to native.log")
    ps = sub.add_parser("suite")
    ps.add_argument("--only", default="", help="comma-separated scenario names")
    ps.add_argument("--seed", type=int, default=42)
    ps.add_argument("--log", action="store_true")
    pae = sub.add_parser("test-ae", help="AE hunt: camera EV vs overlap-only equalization")
    for sp in (pr, ps, pae):
        sp.add_argument("--out", type=Path, default=BUILD_DIR / "runs")
        sp.add_argument("--rebuild", action="store_true")
    args = p.parse_args()

    exe = build(force=args.rebuild)
    args.out.mkdir(parents=True, exist_ok=True)
    if args.cmd == "run":
        sc = Scenario(
            name=args.name or f"{args.layout}_{args.imu.replace(':', '').replace('@', 'at')}",
            source=args.source, layout=args.layout, size=args.size, imu=args.imu, photo=args.photo,
            corrupt=args.corrupt, jitter=not args.no_jitter, seed=args.seed,
            export_w=args.export, export_h=args.export // 2,
        )
        m = run_scenario(sc, exe, args.out, log=args.log)
        print_row(m)
        print("per-frame pose err (deg):", m["pose_err_deg"])
        return
    if args.cmd == "test-ae":
        rows = []
        for name in ("s16_ae_ev", "s16_ae_noev"):
            cfg = next(c for c in SUITE if c["name"] == name)
            sc = Scenario(seed=42, **cfg)
            m = run_scenario(sc, exe, args.out, log=name == "s16_ae_ev")
            print_row(m)
            rows.append(m)
        ev = next(m for m in rows if m["scenario"] == "s16_ae_ev")
        noev = next(m for m in rows if m["scenario"] == "s16_ae_noev")
        luma_ratio = ev["median_luma"] / max(1.0, ev["median_luma_ref"])
        native_log = (args.out / "s16_ae_ev" / "native.log").read_text(encoding="utf-8", errors="replace")
        ev_gains = [ln for ln in native_log.splitlines() if "Camera EV gain=" in ln]
        print(f"\ncamera-EV gain logs: {len(ev_gains)}")
        for ln in ev_gains[:4]:
            print(" ", ln)
        if ev_gains:
            print(f"  … last: {ev_gains[-1]}")
        print(
            f"luma stitch/ref: {ev['median_luma']:.1f}/{ev['median_luma_ref']:.1f} "
            f"(ratio {luma_ratio:.3f}); overlap-only acc={noev['accuracy']:.4f} "
            f"luma={noev['median_luma']:.1f}"
        )
        failed = []
        if ev["accuracy"] < 0.99 or ev["coverage"] < 0.98:
            failed.append(f"camera-EV accuracy/coverage {ev['accuracy']:.4f}/{ev['coverage']:.3f}")
        if luma_ratio > 1.15:
            failed.append(f"stitched too bright vs source (ratio {luma_ratio:.3f})")
        if luma_ratio < 0.85:
            failed.append(f"stitched too dark vs source (ratio {luma_ratio:.3f})")
        if len(ev_gains) < 8:
            failed.append(f"expected camera EV gains on most frames, got {len(ev_gains)}")
        if ev["accuracy"] + 0.005 < noev["accuracy"]:
            failed.append(
                f"camera-EV acc {ev['accuracy']:.4f} lost accuracy vs overlap-only {noev['accuracy']:.4f}"
            )
        if failed:
            print("FAIL:", "; ".join(failed))
            sys.exit(1)
        print("PASS camera-EV AE equalization")
        return
    only = {s for s in args.only.split(",") if s}
    rows = []
    for cfg in SUITE:
        if only and cfg["name"] not in only:
            continue
        sc = Scenario(seed=args.seed, **cfg)
        m = run_scenario(sc, exe, args.out, log=args.log)
        print_row(m)
        rows.append(m)
    (args.out / "suite_summary.json").write_text(json.dumps(rows, indent=2))
    failed = [m["scenario"] for m in rows if m["accuracy"] < ACCURACY_TARGET or m["coverage"] < ACCURACY_TARGET]
    print(f"\n{len(rows) - len(failed)}/{len(rows)} scenarios >= {ACCURACY_TARGET:.0%} accuracy+coverage")
    if failed:
        print("failing:", ", ".join(failed))
        sys.exit(1)


if __name__ == "__main__":
    main()

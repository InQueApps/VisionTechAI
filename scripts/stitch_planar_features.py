#!/usr/bin/env python3
"""
Stitch planar replay frames using OpenCV feature matching (ORB/AKAZE) instead of
photometric SSD / IMU-only alignment.

PC prototype to validate feature-based pose recovery before porting to native.
Matches VisionTechAI quaternion conventions (x,y,z,w) and replay manifest layout.
"""

from __future__ import annotations

import argparse
import json
import math
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np
from PIL import Image
from scipy.spatial.transform import Rotation

_SCRIPT_DIR = Path(__file__).resolve().parent
_REPO_ROOT = _SCRIPT_DIR.parent
if str(_SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_DIR))

PI = math.pi
DEG2RAD = PI / 180.0
BG_RGB = (0x1A, 0x1A, 0x2E)


@dataclass
class FrameSpec:
    file: str
    azimuth: float
    elevation: float
    roll: float
    index: int


def quat_mul(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    x1, y1, z1, w1 = a
    x2, y2, z2, w2 = b
    return np.array(
        [
            w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2,
            w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2,
            w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2,
            w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2,
        ],
        dtype=np.float64,
    )


def quat_from_axis_angle(ax: float, ay: float, az: float, angle_rad: float) -> np.ndarray:
    length = math.sqrt(ax * ax + ay * ay + az * az)
    if length < 1e-12:
        return np.array([0.0, 0.0, 0.0, 1.0], dtype=np.float64)
    ux, uy, uz = ax / length, ay / length, az / length
    half = angle_rad * 0.5
    s = math.sin(half)
    return np.array([ux * s, uy * s, uz * s, math.cos(half)], dtype=np.float64)


def quat_rotate(q: np.ndarray, vx: float, vy: float, vz: float) -> tuple[float, float, float]:
    x, y, z, w = q
    tx = 2.0 * (y * vz - z * vy)
    ty = 2.0 * (z * vx - x * vz)
    tz = 2.0 * (x * vy - y * vx)
    return (
        vx + w * tx + (y * tz - z * ty),
        vy + w * ty + (z * tx - x * tz),
        vz + w * tz + (x * ty - y * tx),
    )


def quat_to_rot(q: np.ndarray) -> np.ndarray:
    return Rotation.from_quat([q[0], q[1], q[2], q[3]]).as_matrix()


def rot_to_quat(r: np.ndarray) -> np.ndarray:
    return Rotation.from_matrix(r).as_quat()


def truth_quat_for_view(azimuth_deg: float, elevation_deg: float, roll_deg: float = 0.0) -> np.ndarray:
    """Match SpherePlanarReplayE2ETest.quaternionForExtractedPlanarView."""
    q_az = quat_from_axis_angle(0.0, 0.0, 1.0, -azimuth_deg * DEG2RAD)
    q_el = quat_from_axis_angle(1.0, 0.0, 0.0, elevation_deg * DEG2RAD)
    q_look = quat_mul(q_az, q_el)
    q_roll = quat_from_axis_angle(0.0, 1.0, 0.0, roll_deg * DEG2RAD)
    q_with_roll = quat_mul(q_look, q_roll)
    q_pitch = quat_from_axis_angle(1.0, 0.0, 0.0, 90.0 * DEG2RAD)
    return quat_mul(q_with_roll, q_pitch)


def degrade_imu_quat(truth: np.ndarray, frame_index: int, degrade_deg: float, seed: int) -> np.ndarray:
    """Match SpherePlanarReplayE2ETest.degradeImuQuaternion (Kotlin Random)."""
    rng = np.random.default_rng(seed + frame_index * 31)
    u1, u2, u3 = rng.random(3)
    angle_rad = degrade_deg * DEG2RAD * u1
    z = 2.0 * u2 - 1.0
    phi = 2.0 * PI * u3
    sin_theta = math.sqrt(max(0.0, 1.0 - z * z))
    ax = sin_theta * math.cos(phi)
    ay = sin_theta * math.sin(phi)
    az = z
    q_err = quat_from_axis_angle(ax, ay, az, angle_rad)
    return quat_mul(q_err, truth)


def load_manifest(path: Path) -> tuple[dict, list[FrameSpec]]:
    obj = json.loads(path.read_text(encoding="utf-8"))
    frames = []
    for i, f in enumerate(obj["frames"]):
        frames.append(
            FrameSpec(
                file=f["file"],
                azimuth=float(f["azimuth"]),
                elevation=float(f.get("elevation", obj.get("elevation", 0.0))),
                roll=float(f.get("roll", 0.0)),
                index=int(f.get("index", i)),
            )
        )
    frames.sort(key=lambda s: s.index)
    return obj, frames


def tan_half_fov(fov_deg: float) -> float:
    return math.tan(fov_deg * 0.5 * DEG2RAD)


def pixel_to_ray_native(px: float, py: float, w: int, h: int, tan_h: float, tan_v: float) -> np.ndarray:
    """Match sphere_jni.cpp pixelToRayFloat (camera looks along −Z)."""
    nx = (px / max(w - 1, 1) - 0.5) * 2.0 * tan_h
    ny = (0.5 - py / max(h - 1, 1)) * 2.0 * tan_v
    length = math.sqrt(nx * nx + ny * ny + 1.0)
    return np.array([nx / length, ny / length, -1.0 / length], dtype=np.float64)


def world_ray_to_pixel_native(
    wx: float, wy: float, wz: float,
    q: np.ndarray, w: int, h: int, tan_h: float, tan_v: float,
) -> tuple[float, float, bool]:
    """Match sphere_jni.cpp worldRayToFramePixel."""
    crx, cry, crz = quat_rotate(np.array([-q[0], -q[1], -q[2], q[3]]), wx, wy, wz)
    if crz >= -1e-4:
        return 0.0, 0.0, False
    nx = crx / (-crz)
    ny = cry / (-crz)
    fx = (nx / (2.0 * tan_h) + 0.5) * (w - 1)
    fy = (0.5 - ny / (2.0 * tan_v)) * (h - 1)
    if fx < 0.0 or fx > w - 1 or fy < 0.0 or fy > h - 1:
        return 0.0, 0.0, False
    return fx, fy, True


def frame_pixel_to_equirect(
    px: float, py: float, w: int, h: int,
    tan_h: float, tan_v: float, q: np.ndarray,
    eq_w: int, eq_h: int,
) -> tuple[float, float]:
    rx, ry, rz = pixel_to_ray_native(px, py, w, h, tan_h, tan_v)
    wx, wy, wz = quat_rotate(q, rx, ry, rz)
    lon = math.atan2(wx, wy)
    lat = math.asin(max(-1.0, min(1.0, wz)))
    eq_xf = (lon / PI + 1.0) * 0.5 * eq_w
    eq_yf = (0.5 - lat / PI) * eq_h
    return eq_xf, eq_yf


def rays_from_keypoints(kps: list[cv2.KeyPoint], w: int, h: int, tan_h: float, tan_v: float) -> np.ndarray:
    out = np.zeros((len(kps), 3), dtype=np.float64)
    for i, kp in enumerate(kps):
        out[i] = pixel_to_ray_native(kp.pt[0], kp.pt[1], w, h, tan_h, tan_v)
    return out


def estimate_rotation_between_rays(rays_a: np.ndarray, rays_b: np.ndarray) -> tuple[np.ndarray, int]:
    """Rotation mapping camera-b rays into camera-a: r_a ≈ R @ r_b."""
    if len(rays_a) < 8:
        return np.eye(3), 0
    rot, _ = Rotation.align_vectors(rays_a, rays_b)
    r = rot.as_matrix()
    pred = (r @ rays_b.T).T
    ang_err = np.arccos(np.clip(np.sum(pred * rays_a, axis=1), -1.0, 1.0))
    inliers = int(np.sum(ang_err < 3.0 * DEG2RAD))
    if inliers >= 12:
        idx = ang_err < 3.0 * DEG2RAD
        rot, _ = Rotation.align_vectors(rays_a[idx], rays_b[idx])
        r = rot.as_matrix()
        pred = (r @ rays_b[idx].T).T
        ang_err = np.arccos(np.clip(np.sum(pred * rays_a[idx], axis=1), -1.0, 1.0))
        inliers = int(np.sum(ang_err < 2.5 * DEG2RAD))
    return r, inliers


def ransac_rotation(rays_a: np.ndarray, rays_b: np.ndarray, iterations: int = 128) -> tuple[np.ndarray, int]:
    r, inliers = estimate_rotation_between_rays(rays_a, rays_b)
    if len(rays_a) < 16 or inliers >= len(rays_a) * 0.6:
        return r, inliers
    best_r, best_inl = r, inliers
    n = len(rays_a)
    rng = np.random.default_rng(42)
    for _ in range(iterations):
        idx = rng.choice(n, size=8, replace=False)
        cand, inl = estimate_rotation_between_rays(rays_a[idx], rays_b[idx])
        if inl <= best_inl:
            continue
        pred = (cand @ rays_b.T).T
        ang_err = np.arccos(np.clip(np.sum(pred * rays_a, axis=1), -1.0, 1.0))
        inl = int(np.sum(ang_err < 2.5 * DEG2RAD))
        if inl > best_inl:
            best_inl = inl
            best_r = cand
    return best_r, best_inl


def match_features(
    img_a: np.ndarray,
    img_b: np.ndarray,
    w: int,
    h: int,
    tan_h: float,
    tan_v: float,
    detector: str,
) -> tuple[np.ndarray, np.ndarray, int]:
    gray_a = cv2.cvtColor(img_a, cv2.COLOR_RGB2GRAY)
    gray_b = cv2.cvtColor(img_b, cv2.COLOR_RGB2GRAY)
    if detector == "akaze":
        det = cv2.AKAZE_create()
        norm = cv2.NORM_HAMMING
    else:
        det = cv2.ORB_create(nfeatures=4000, fastThreshold=12)
        norm = cv2.NORM_HAMMING
    kpa, des_a = det.detectAndCompute(gray_a, None)
    kpb, des_b = det.detectAndCompute(gray_b, None)
    if des_a is None or des_b is None or len(kpa) < 12 or len(kpb) < 12:
        return np.zeros((0, 3)), np.zeros((0, 3)), 0
    matcher = cv2.BFMatcher(norm, crossCheck=False)
    pairs = matcher.knnMatch(des_a, des_b, k=2)
    good = []
    for pair in pairs:
        if len(pair) < 2:
            continue
        m, n = pair
        if m.distance < 0.8 * n.distance:
            good.append(m)
    if len(good) < 12:
        return np.zeros((0, 3)), np.zeros((0, 3)), 0
    rays_a = rays_from_keypoints([kpa[m.queryIdx] for m in good], w, h, tan_h, tan_v)
    rays_b = rays_from_keypoints([kpb[m.trainIdx] for m in good], w, h, tan_h, tan_v)
    return rays_a, rays_b, len(good)


def pose_graph_optimize(
    rots_init: list[np.ndarray],
    imu_rots: list[np.ndarray],
    edge_rot: dict[tuple[int, int], np.ndarray],
    edge_weight: dict[tuple[int, int], float],
    imu_lambda: float = 60.0,
    iterations: int = 15,
) -> list[np.ndarray]:
    """Fuse feature edges with soft IMU prior (rotation averaging in tangent space)."""
    n = len(rots_init)
    rots = [r.copy() for r in rots_init]
    for _ in range(iterations):
        new_rots: list[np.ndarray] = []
        for i in range(n):
            rotvecs: list[np.ndarray] = []
            weights: list[float] = []
            rotvecs.append(Rotation.from_matrix(imu_rots[i]).as_rotvec())
            weights.append(imu_lambda)
            for (a, b), r_rel in edge_rot.items():
                w = edge_weight.get((a, b), 1.0) * 120.0
                if a == i:
                    target = rots[b] @ r_rel.T
                    rotvecs.append(Rotation.from_matrix(target).as_rotvec())
                    weights.append(w)
                elif b == i:
                    target = rots[a] @ r_rel
                    rotvecs.append(Rotation.from_matrix(target).as_rotvec())
                    weights.append(w)
            tw = sum(weights)
            avg = sum(w * rv for w, rv in zip(weights, rotvecs)) / tw
            new_rots.append(Rotation.from_rotvec(avg).as_matrix())
        rots = new_rots
    return rots


def light_ray_polish(
    images: list[np.ndarray],
    rots: list[np.ndarray],
    frame_idx: int,
    tan_h: float,
    tan_v: float,
    w: int,
    h: int,
    inlier_sum: int,
) -> np.ndarray:
    """Small rotation search when feature support is strong (placeholder for native GN)."""
    if inlier_sum < 80:
        return rots[frame_idx]
    best_r = rots[frame_idx]
    best_cost = 1e18
    for dy in np.linspace(-2.0, 2.0, 5) * DEG2RAD:
        for dp in np.linspace(-2.0, 2.0, 5) * DEG2RAD:
            qy = quat_from_axis_angle(0, 0, 1, dy)
            qp = quat_from_axis_angle(1, 0, 0, dp)
            q = rot_to_quat(best_r)
            q = quat_mul(qy, quat_mul(qp, q))
            r = quat_to_rot(q)
            # neighbor photometric proxy: SSD vs prev frame rays
            if frame_idx <= 0:
                return r
            cost = 0.0
            cnt = 0
            ra_prev = rots[frame_idx - 1]
            q_prev = rot_to_quat(ra_prev)
            step = 16
            for fy in range(0, h, step):
                for fx in range(0, w, step):
                    rx, ry, rz = pixel_to_ray_native(float(fx), float(fy), w, h, tan_h, tan_v)
                    wx, wy, wz = quat_rotate(rot_to_quat(r), rx, ry, rz)
                    crx, cry, crz = quat_rotate(
                        np.array([-q_prev[0], -q_prev[1], -q_prev[2], q_prev[3]]),
                        wx, wy, wz,
                    )
                    if crz >= -0.1:
                        continue
                    nx, ny = crx / (-crz), cry / (-crz)
                    pfx = (nx / (2 * tan_h) + 0.5) * (w - 1)
                    pfy = (0.5 - ny / (2 * tan_v)) * (h - 1)
                    if pfx < 0 or pfx > w - 1 or pfy < 0 or pfy > h - 1:
                        continue
                    c1 = images[frame_idx][fy, fx]
                    x0, y0 = int(pfx), int(pfy)
                    c2 = images[frame_idx - 1][y0, x0]
                    cost += float(np.sum((c1 - c2) ** 2))
                    cnt += 1
            if cnt > 8 and cost < best_cost:
                best_cost = cost
                best_r = r
    return best_r


def neighbor_pairs(n: int) -> list[tuple[int, int]]:
    pairs: list[tuple[int, int]] = []
    for i in range(n):
        for j in range(max(0, i - 3), i):
            pairs.append((i, j))
    if n >= 4:
        pairs.append((0, n - 1))
    return pairs


def chain_poses_from_edges(
    n: int,
    edge_rot: dict[tuple[int, int], np.ndarray],
    anchor_rot: np.ndarray,
) -> list[np.ndarray]:
    """edge_rot[(a,b)] maps camera-b ray into camera-a frame: r_a ≈ R @ r_b."""
    rots = [anchor_rot.copy()]
    for i in range(1, n):
        key = (i, i - 1)
        if key not in edge_rot:
            rots.append(rots[-1].copy())
            continue
        r_rel = edge_rot[key]
        rots.append(rots[-1] @ r_rel)
    return rots


def apply_loop_closure(
    rots: list[np.ndarray],
    edge_rot: dict[tuple[int, int], np.ndarray],
) -> list[np.ndarray]:
    n = len(rots)
    if n < 4 or (0, n - 1) not in edge_rot:
        return rots
    r_loop = edge_rot[(0, n - 1)]
    r_last_chain = rots[-1]
    r_last_loop = rots[0] @ r_loop
    delta = r_last_loop @ r_last_chain.T
    angle = Rotation.from_matrix(delta).magnitude()
    if angle < 0.5 * DEG2RAD:
        return rots
    # Distribute small closure correction along the chain.
    frac = Rotation.from_matrix(delta)
    out = []
    for i, r in enumerate(rots):
        t = i / max(n - 1, 1)
        corr = Rotation.from_rotvec(frac.as_rotvec() * t)
        out.append(corr.as_matrix() @ r)
    return out


def splat_to_equirect(
    images: list[np.ndarray],
    quats: list[np.ndarray],
    eq_w: int,
    eq_h: int,
    fov_deg: float,
    vfov_deg: float | None = None,
) -> np.ndarray:
    """Forward splat: frame pixels → equirect (matches native framePixelToEquirect)."""
    h, w = images[0].shape[:2]
    tan_h = tan_half_fov(fov_deg)
    tan_v = tan_half_fov(vfov_deg if vfov_deg is not None else fov_deg)
    out = np.zeros((eq_h, eq_w, 3), dtype=np.float64)
    weight = np.zeros((eq_h, eq_w), dtype=np.float64)
    step = 2  # stride-2 like native export downscale path
    for img, q in zip(images, quats):
        for fy in range(0, h, step):
            for fx in range(0, w, step):
                eq_xf, eq_yf = frame_pixel_to_equirect(
                    float(fx), float(fy), w, h, tan_h, tan_v, q, eq_w, eq_h
                )
                x0 = int(math.floor(eq_xf)) % eq_w
                y0 = int(max(0, min(eq_h - 1, math.floor(eq_yf))))
                col = img[fy, fx].astype(np.float64)
                out[y0, x0] += col
                weight[y0, x0] += 1.0
    mask = weight > 0
    out[mask] /= weight[mask, None]
    out[~mask] = np.array(BG_RGB, dtype=np.float64)
    return np.clip(out, 0, 255).astype(np.uint8)


def run_compare(reference: Path, stitched: Path, out_dir: Path) -> dict:
    cmd = [
        sys.executable,
        str(_SCRIPT_DIR / "compare_equirect.py"),
        str(reference),
        str(stitched),
        "-o",
        str(out_dir),
        "--full",
    ]
    subprocess.run(cmd, check=True, cwd=_REPO_ROOT)
    return json.loads((out_dir / "metrics.json").read_text(encoding="utf-8"))


def main() -> None:
    p = argparse.ArgumentParser(description="Feature-matching planar stitch (PC prototype).")
    p.add_argument(
        "--planar-dir",
        type=Path,
        default=_REPO_ROOT / "app" / "src" / "androidTest" / "assets" / "planar_replay",
        help="Directory with manifest.json and planar JPEGs",
    )
    p.add_argument(
        "--reference",
        type=Path,
        default=None,
        help="Reference equirect (default: planar-dir/reference from manifest)",
    )
    p.add_argument("--imu-degrade-deg", type=float, default=16.0)
    p.add_argument("--imu-degrade-seed", type=int, default=42)
    p.add_argument("--detector", choices=["orb", "akaze"], default="orb")
    p.add_argument(
        "-o",
        "--out-dir",
        type=Path,
        default=_REPO_ROOT / "scripts" / "images" / "roundtrip_output" / "feature_stitch_imu16deg",
    )
    p.add_argument(
        "--no-anchor-truth",
        action="store_true",
        help="Chain from degraded frame-0 seed instead of truth anchor",
    )
    p.add_argument("--min-inliers", type=int, default=25, help="Min inlier count to accept a pairwise edge")
    p.add_argument("--eq-w", type=int, default=1024)
    p.add_argument("--eq-h", type=int, default=512)
    args = p.parse_args()

    manifest_obj, frames = load_manifest(args.planar_dir / "manifest.json")
    fov = float(manifest_obj.get("fov", 90.0))
    vfov = float(manifest_obj.get("vfov", fov))
    tan_h = tan_half_fov(fov)
    tan_v = tan_half_fov(vfov)
    ref_path = args.reference or (args.planar_dir / manifest_obj.get("referenceEquirect", "img_eq4096_eq1024.jpg"))
    eq_w = args.eq_w or int(manifest_obj.get("exportEqW", 1024))
    eq_h = args.eq_h or int(manifest_obj.get("exportEqH", 512))

    images: list[np.ndarray] = []
    truth_quats: list[np.ndarray] = []
    seed_quats: list[np.ndarray] = []
    for spec in frames:
        img = np.asarray(Image.open(args.planar_dir / spec.file).convert("RGB"))
        images.append(img)
        tq = truth_quat_for_view(spec.azimuth, spec.elevation, spec.roll)
        truth_quats.append(tq)
        if args.imu_degrade_deg > 0:
            seed_quats.append(
                degrade_imu_quat(tq, spec.index, args.imu_degrade_deg, args.imu_degrade_seed)
            )
        else:
            seed_quats.append(tq)

    truth_rots = [quat_to_rot(q) for q in truth_quats]
    seed_rots = [quat_to_rot(q) for q in seed_quats]

    args.out_dir.mkdir(parents=True, exist_ok=True)

    # Baseline: stitch with degraded IMU seeds (no feature refine).
    baseline = splat_to_equirect(images, seed_quats, eq_w, eq_h, fov, vfov)
    baseline_path = args.out_dir / "stitched_imu_seed.jpg"
    Image.fromarray(baseline).save(baseline_path, quality=95)

    # Feature matching on neighbor pairs.
    edge_rot: dict[tuple[int, int], np.ndarray] = {}
    edge_weight: dict[tuple[int, int], float] = {}
    pair_stats: list[dict] = []
    n = len(frames)
    h, w = images[0].shape[:2]
    for a, b in neighbor_pairs(n):
        rays_a, rays_b, count = match_features(images[a], images[b], w, h, tan_h, tan_v, args.detector)
        if count < 12:
            pair_stats.append({"a": a, "b": b, "matches": count, "inliers": 0})
            continue
        r_rel, inliers = ransac_rotation(rays_a, rays_b)
        accepted = inliers >= args.min_inliers
        if accepted:
            edge_rot[(a, b)] = r_rel
            edge_weight[(a, b)] = min(3.0, inliers / 40.0)
        pair_stats.append({"a": a, "b": b, "matches": count, "inliers": inliers, "accepted": accepted})

    graph_rots = pose_graph_optimize(seed_rots, seed_rots, edge_rot, edge_weight)
    inlier_by_frame = [0] * n
    for s in pair_stats:
        inlier_by_frame[s["a"]] += s.get("inliers", 0)
        inlier_by_frame[s["b"]] += s.get("inliers", 0)
    for i in range(1, n):
        if inlier_by_frame[i] >= 80:
            graph_rots[i] = light_ray_polish(
                images, graph_rots, i, tan_h, tan_v, w, h, inlier_by_frame[i]
            )

    feature_quats = [rot_to_quat(r) for r in graph_rots]

    feature = splat_to_equirect(images, feature_quats, eq_w, eq_h, fov, vfov)
    feature_path = args.out_dir / "stitched_feature.jpg"
    Image.fromarray(feature).save(feature_path, quality=95)

    truth_stitch = splat_to_equirect(images, truth_quats, eq_w, eq_h, fov, vfov)
    truth_path = args.out_dir / "stitched_truth_pose.jpg"
    Image.fromarray(truth_stitch).save(truth_path, quality=95)

    ref_copy = args.out_dir / "reference_equirect.jpg"
    if not ref_copy.exists():
        Image.open(ref_path).save(ref_copy)

    print("Pairwise feature stats:")
    for s in pair_stats:
        print(f"  ({s['a']},{s['b']}): matches={s['matches']} inliers={s['inliers']}")

    print("\nMetrics — IMU seed baseline:")
    m_seed = run_compare(ref_copy, baseline_path, args.out_dir / "baseline")
    print(json.dumps({k: m_seed[k] for k in ("correlation", "correlation_mapped", "psnr_db", "sharpness_retention")}, indent=2))

    print("\nMetrics — feature-matched poses:")
    m_feat = run_compare(ref_copy, feature_path, args.out_dir / "feature")
    print(json.dumps({k: m_feat[k] for k in ("correlation", "correlation_mapped", "psnr_db", "sharpness_retention")}, indent=2))

    print("\nMetrics — truth poses (upper bound):")
    m_truth = run_compare(ref_copy, truth_path, args.out_dir / "truth")
    print(json.dumps({k: m_truth[k] for k in ("correlation", "correlation_mapped", "psnr_db", "sharpness_retention")}, indent=2))

    summary = {
        "imu_degrade_deg": args.imu_degrade_deg,
        "detector": args.detector,
        "pair_stats": pair_stats,
        "metrics_imu_seed": m_seed,
        "metrics_feature": m_feat,
        "metrics_truth": m_truth,
    }
    (args.out_dir / "feature_stitch_summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(f"\nSummary: {args.out_dir / 'feature_stitch_summary.json'}")


if __name__ == "__main__":
    main()

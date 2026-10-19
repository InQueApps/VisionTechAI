#!/usr/bin/env python3
"""
Stitch planar views with SphereNative, pull result to PC, compare to source equirect.

Inputs (default): scripts/images/img1.jpg + scripts/images/planar_*.jpg
Outputs (always):  scripts/images/roundtrip_output/

See README_test.md (planar roundtrip section).

Example:
  python scripts/stitch_planar_roundtrip.py --device 328bea72
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

_SCRIPT_DIR = Path(__file__).resolve().parent
if str(_SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_DIR))

import numpy as np

from equirect_band import (  # noqa: E402
    extract_equirect_lat_band,
    find_reference_band,
    lat_band_row_range,
    reference_band_filename,
    scaled_equirect_filename,
)

PLANAR_RE = re.compile(
    r"^planar_.+_\d{2}_az(\d{3})(?:_el[pm]\d+)?\.(jpg|png)$",
    re.IGNORECASE,
)
PLANAR_INDEX_RE = re.compile(
    r"^planar_.+_(\d{2})_az\d{3}(?:_el[pm]\d+)?\.(jpg|png)$",
    re.IGNORECASE,
)
PLANAR_EL_RE = re.compile(r"_el([pm])(\d+)\.(?:jpg|png)$", re.IGNORECASE)
DEFAULT_FOV = 90.0
DEFAULT_ELEVATION = 0.0
TEST_CLASS = "ab.inque.visiontechai.SpherePlanarReplayE2ETest"
TEST_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
PACKAGE = "ab.inque.prismai"
TEST_PACKAGE = f"{PACKAGE}.test"
DEVICE_DOWNLOAD = "/sdcard/Download/visiontech_planar_roundtrip"
PUBLIC_OUTPUT = "/sdcard/visiontech_stitch_output"
PRODUCTION_EXPORT_W = 4096
PRODUCTION_EXPORT_H = 2048


def repo_root() -> Path:
    return Path(__file__).resolve().parent.parent


def asset_dir(root: Path) -> Path:
    return root / "app" / "src" / "androidTest" / "assets" / "planar_replay"


def output_dir(root: Path, subdir: str | None = None, output_root: str | None = None) -> Path:
    base = root / "scripts" / "images" / (output_root or "roundtrip_output")
    return base / subdir if subdir else base


def replot_imu_sweep_summary(output_root: str | None = None) -> None:
    """Refresh imu_degrade_sweep_summary.png/json from per-level metrics.json folders."""
    sweep_script = _SCRIPT_DIR / "run_imu_degrade_sweep.py"
    if not sweep_script.is_file():
        return
    levels = [0, 2, 4, 8, 16]
    print("Refreshing IMU sweep summary plot...")
    cmd = [
        sys.executable,
        str(sweep_script),
        "--plot-only",
        "--imu-levels",
        *[str(x) for x in levels],
        "--sweep-fast",
    ]
    if output_root:
        cmd.extend(["--output-root", output_root])
    subprocess.run(
        cmd,
        cwd=repo_root(),
        check=False,
    )


def load_extract_manifest(images_dir: Path, source_stem: str) -> dict | None:
    path = images_dir / f"planar_{source_stem}_manifest.json"
    if not path.is_file():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def elevation_from_planar_filename(name: str) -> float:
    """Parse signed elevation from planar_*_azDDD_elp45.jpg / elm90.jpg suffix."""
    m = PLANAR_EL_RE.search(name)
    if not m:
        return 0.0
    sign = 1.0 if m.group(1).lower() == "p" else -1.0
    return sign * float(m.group(2))


def infer_full_sphere_from_frames(frames: list[dict]) -> bool:
    """True when planars span multiple elevation rings (16-view full-sphere layout)."""
    if len(frames) < 10:
        return False
    elevations = {round(f["elevation"], 1) for f in frames}
    return len(elevations) > 1 and any(abs(e) >= 40.0 for e in elevations)


def discover_planar_frames(
    images_dir: Path, planar_prefix: str | None = None
) -> list[dict]:
    """Return frame dicts: file, azimuth, elevation, roll, index (sorted by index)."""
    extract_manifest = None
    if planar_prefix:
        stem = planar_prefix.removeprefix("planar_")
        extract_manifest = load_extract_manifest(images_dir, stem)
    frames: list[dict] = []
    if extract_manifest and extract_manifest.get("frames"):
        for fr in extract_manifest["frames"]:
            frames.append(
                {
                    "file": fr["file"],
                    "azimuth": float(fr["azimuth"]),
                    "elevation": float(fr.get("elevation", 0.0)),
                    "roll": float(fr.get("roll", 0.0)),
                    "index": int(fr.get("index", len(frames))),
                }
            )
        frames.sort(key=lambda f: f["index"])
        return frames

    globs = ["planar_*.jpg", "planar_*.png"]
    paths: list[Path] = []
    for pattern in globs:
        paths.extend(sorted(images_dir.glob(pattern)))
    if planar_prefix:
        paths = [p for p in paths if p.name.startswith(planar_prefix)]
    elif paths:
        prefixes: dict[str, list[Path]] = {}
        for p in paths:
            m = re.match(r"^(planar_.+?)_\d{2}_az\d{3}", p.name, re.IGNORECASE)
            key = m.group(1) if m else p.stem
            prefixes.setdefault(key, []).append(p)
        paths = max(prefixes.values(), key=len)
    indexed: list[tuple[int, str, float, float]] = []
    for path in paths:
        match = PLANAR_RE.match(path.name)
        if not match:
            continue
        idx_m = PLANAR_INDEX_RE.match(path.name)
        idx = int(idx_m.group(1)) if idx_m else len(indexed)
        el = elevation_from_planar_filename(path.name)
        indexed.append((idx, path.name, float(match.group(1)), el))
    indexed.sort(key=lambda t: t[0])
    return [
        {
            "file": name,
            "azimuth": az,
            "elevation": el,
            "roll": 0.0,
            "index": idx,
        }
        for idx, name, az, el in indexed
    ]


def downscale_equirect_if_needed(source_path: Path, out_path: Path, max_width: int) -> Path:
    from PIL import Image

    Image.MAX_IMAGE_PIXELS = max(int(Image.MAX_IMAGE_PIXELS or 0), 500_000_000)
    with Image.open(source_path) as im:
        w, h = im.size
        if w <= max_width:
            return source_path
        scale = max_width / w
        nw = max_width
        nh = max(1, int(round(h * scale)))
        if out_path.is_file():
            with Image.open(out_path) as existing:
                if existing.size == (nw, nh):
                    return out_path
        im = im.convert("RGB")
        im.resize((nw, nh), Image.Resampling.LANCZOS).save(out_path, quality=95)
        print(f"Downscaled {source_path.name} {w}x{h} -> {out_path.name} {nw}x{nh}")
        return out_path


def build_manifest(
    source_path: Path,
    reference_path: Path | None,
    frames: list[dict],
    fov: float,
    vfov: float,
    elevation: float,
    *,
    refine_passes: int = 3,
    refine_seams: str = "light",
    eq_scale: int = 1,
    full_sphere: bool = False,
    compare_full_equirect: bool = False,
    imu_degrade_deg: float = 0.0,
    imu_degrade_seed: int = 42,
    run_capture_refinement: bool = False,
    use_fast_path: bool = False,
    overlap_gate: float = 0.10,
    fast_e2e_test: bool = False,
    production_replay: bool = True,
    skip_planar_roundtrip: bool = False,
    use_production_worker: bool = False,
    export_eq_w: int | None = None,
    export_eq_h: int | None = None,
    sweep_fast: bool = False,
) -> dict:
    from PIL import Image

    Image.MAX_IMAGE_PIXELS = max(int(Image.MAX_IMAGE_PIXELS or 0), 500_000_000)
    with Image.open(source_path) as im:
        eq_w, eq_h = im.size
    ref_w, ref_h, band_y0 = eq_w, eq_h, 0
    reference_name = source_path.name
    if reference_path is not None and reference_path.is_file():
        with Image.open(reference_path) as ref_im:
            ref_w, ref_h = ref_im.size
        reference_name = reference_path.name
        y0, y1 = lat_band_row_range(eq_h, elevation, vfov)
        band_y0 = y0
    manifest = {
        "sourceEquirect": source_path.name,
        "referenceEquirect": reference_name,
        "eqW": eq_w,
        "eqH": eq_h,
        "refW": ref_w,
        "refH": ref_h,
        "bandY0": band_y0,
        "fov": fov,
        "vfov": vfov,
        "elevation": elevation,
        "refinePasses": refine_passes,
        "refineSeams": refine_seams,
        "eqScale": eq_scale,
        "fullSphere": full_sphere,
        "compareFullEquirect": compare_full_equirect,
        "imuDegradeDeg": imu_degrade_deg,
        "imuDegradeSeed": imu_degrade_seed,
        "runCaptureRefinement": run_capture_refinement,
        "useFastPath": use_fast_path,
        "overlapGate": overlap_gate,
        "fastE2eTest": fast_e2e_test,
        "productionReplay": production_replay,
        "skipPlanarRoundtrip": skip_planar_roundtrip,
        "useProductionWorker": use_production_worker,
        "exportEqW": export_eq_w,
        "exportEqH": export_eq_h,
        "sweepFast": sweep_fast,
        "frames": [
            {
                "file": fr["file"],
                "azimuth": fr["azimuth"],
                "elevation": fr.get("elevation", 0.0),
                "roll": fr.get("roll", 0.0),
                "index": fr.get("index", i),
            }
            for i, fr in enumerate(frames)
        ],
    }
    return manifest


def upscale_equirect(source_path: Path, out_path: Path, scale: int) -> Path:
    from PIL import Image

    if scale <= 1:
        return source_path
    if out_path.is_file():
        with Image.open(out_path) as im:
            with Image.open(source_path) as src:
                if im.size == (src.width * scale, src.height * scale):
                    return out_path
    with Image.open(source_path) as im:
        im = im.convert("RGB")
        w, h = im.size
        up = im.resize((w * scale, h * scale), Image.Resampling.LANCZOS)
        up.save(out_path, quality=98)
    print(f"Upscaled {source_path.name} {w}x{h} -> {out_path.name} {w * scale}x{h * scale}")
    return out_path


def planar_discover_prefix(work_stem: str) -> str:
    """Match extract output: planar_{stem}_NN_azDDD with --prefix planar."""
    return f"planar_{work_stem}"


def clear_planar_extract_artifacts(images_dir: Path, work_stem: str) -> None:
    prefix = planar_discover_prefix(work_stem)
    for path in images_dir.glob(f"{prefix}_*"):
        if path.is_file():
            path.unlink()
    manifest = images_dir / f"{prefix}_manifest.json"
    if manifest.is_file():
        manifest.unlink()


def run_planar_extract(
    work_source: Path,
    *,
    fov: float,
    vfov: float,
    elevation: float,
    planar_size: int,
    planar_count: int,
    full_sphere: bool,
    random_roll: bool = True,
    random_heading: bool = True,
    random_elevation: bool = True,
    heading_range: float = 15.0,
    elevation_range: float = 8.0,
    layout_seed: int = 42,
) -> None:
    extract_py = _SCRIPT_DIR / "extract_rectilinear_views.py"
    cmd = [
        sys.executable,
        str(extract_py),
        str(work_source),
        "--count",
        str(planar_count),
        "--fov",
        str(fov),
        "--vfov",
        str(vfov),
        "--elevation",
        str(elevation),
        "--width",
        str(planar_size),
        "--height",
        str(planar_size),
        "--prefix",
        "planar",
        "--layout-seed",
        str(layout_seed),
    ]
    if full_sphere:
        cmd.append("--full-sphere")
    if random_roll:
        cmd.append("--random-roll")
    if random_heading:
        cmd.extend(["--random-heading", "--heading-range", str(heading_range)])
    if random_elevation:
        cmd.extend(["--random-elevation", "--elevation-range", str(elevation_range)])
    print("Running:", " ".join(cmd))
    subprocess.check_call(cmd)


def extract_manifest_matches(
    manifest: dict | None,
    *,
    full_sphere: bool,
    random_roll: bool,
    random_heading: bool,
    random_elevation: bool,
    layout_seed: int,
) -> bool:
    if manifest is None:
        return False
    if bool(manifest.get("full_sphere")) != full_sphere:
        return False
    if bool(manifest.get("random_roll")) != random_roll:
        return False
    if bool(manifest.get("random_heading")) != random_heading:
        return False
    if bool(manifest.get("random_elevation")) != random_elevation:
        return False
    if int(manifest.get("layout_seed", manifest.get("roll_seed", -1))) != layout_seed:
        return False
    return bool(manifest.get("frames"))


def ensure_scaled_dataset(
    images_dir: Path,
    source_path: Path,
    *,
    eq_scale: int,
    fov: float,
    vfov: float,
    elevation: float,
    planar_size: int,
    count: int = 6,
) -> tuple[Path, Path, str]:
    """Upscale equirect, extract planars + reference band for eq_scale > 1."""
    stem = source_path.stem
    ext = source_path.suffix.lstrip(".") or "jpg"
    original_stem = stem.replace(f"_eq{eq_scale}x", "") if eq_scale > 1 else stem
    if eq_scale <= 1:
        work_source = source_path
        discover_prefix: str | None = None
    else:
        scaled_name = scaled_equirect_filename(original_stem, ext, eq_scale)
        work_source = images_dir / scaled_name
        upscale_equirect(source_path, work_source, eq_scale)
        discover_prefix = planar_discover_prefix(work_source.stem)

    # Extract planars if missing or wrong scale family.
    existing = discover_planar_frames(images_dir, discover_prefix)
    if not existing:
        extract_py = _SCRIPT_DIR / "extract_rectilinear_views.py"
        cmd = [
            sys.executable,
            str(extract_py),
            str(work_source),
            "--count",
            str(count),
            "--fov",
            str(fov),
            "--vfov",
            str(vfov),
            "--elevation",
            str(elevation),
            "--width",
            str(planar_size),
            "--height",
            str(planar_size),
            "--prefix",
            "planar",
        ]
        print("Running:", " ".join(cmd))
        subprocess.check_call(cmd)

    ref_path = find_reference_band(images_dir, original_stem, vfov, eq_scale)
    if ref_path is None:
        from PIL import Image

        equirect = np.asarray(Image.open(work_source).convert("RGB"))
        band = extract_equirect_lat_band(equirect, elevation, vfov)
        ref_path = images_dir / reference_band_filename(original_stem, vfov, eq_scale)
        Image.fromarray(band).save(ref_path, quality=98)
        print(f"Generated reference band -> {ref_path.name}")

    return work_source, ref_path, discover_prefix if eq_scale > 1 else None


def ensure_reference_band(
    images_dir: Path,
    source_path: Path,
    vfov: float,
    elevation: float,
    eq_scale: int = 1,
    original_stem: str | None = None,
) -> Path:
    stem = original_stem or source_path.stem
    ref_path = find_reference_band(images_dir, stem, vfov, eq_scale)
    if ref_path is not None:
        return ref_path
    from PIL import Image

    equirect = np.asarray(Image.open(source_path).convert("RGB"))
    band = extract_equirect_lat_band(equirect, elevation, vfov)
    ref_path = images_dir / reference_band_filename(stem, vfov, eq_scale)
    Image.fromarray(band).save(ref_path, quality=92)
    print(f"Generated reference band -> {ref_path.name}")
    return ref_path


def sync_assets(
    images_dir: Path,
    source_name: str,
    out_asset_dir: Path,
    fov: float,
    vfov: float,
    elevation: float,
    *,
    eq_scale: int = 1,
    planar_size: int = 1024,
    planar_count: int = 6,
    max_frames: int | None = None,
    refine_passes: int = 3,
    refine_seams: str = "light",
    max_eq_width: int | None = 4096,
    imu_degrade_deg: float = 0.0,
    imu_degrade_seed: int = 42,
    overlap_gate: float = 0.10,
    fast_e2e_test: bool = False,
    skip_planar_roundtrip: bool = False,
    run_capture_refinement: bool = False,
    reextract: bool = False,
    direct_finish_replay: bool = False,
    random_roll: bool = True,
    random_heading: bool = True,
    random_elevation: bool = True,
    heading_range: float = 15.0,
    elevation_range: float = 8.0,
    layout_seed: int = 42,
    sweep_fast: bool = False,
) -> tuple[Path, Path | None]:
    source_path = images_dir / source_name
    if not source_path.is_file():
        raise SystemExit(f"Source equirect not found: {source_path}")

    original_stem = source_path.stem
    extract_manifest = load_extract_manifest(images_dir, original_stem)
    full_sphere_extract = bool(extract_manifest and extract_manifest.get("full_sphere"))
    full_sphere = full_sphere_extract
    prefix: str | None = planar_discover_prefix(original_stem)

    if full_sphere and (
        reextract
        or not extract_manifest_matches(
            extract_manifest,
            full_sphere=True,
            random_roll=random_roll,
            random_heading=random_heading,
            random_elevation=random_elevation,
            layout_seed=layout_seed,
        )
    ):
        clear_planar_extract_artifacts(images_dir, original_stem)
        run_planar_extract(
            source_path,
            fov=fov,
            vfov=vfov,
            elevation=elevation,
            planar_size=planar_size,
            planar_count=planar_count,
            full_sphere=True,
            random_roll=random_roll,
            random_heading=random_heading,
            random_elevation=random_elevation,
            heading_range=heading_range,
            elevation_range=elevation_range,
            layout_seed=layout_seed,
        )
        extract_manifest = load_extract_manifest(images_dir, original_stem)

    if eq_scale > 1:
        work_source, reference_path, prefix = ensure_scaled_dataset(
            images_dir,
            source_path,
            eq_scale=eq_scale,
            fov=fov,
            vfov=vfov,
            elevation=elevation,
            planar_size=planar_size,
            count=planar_count,
        )
    else:
        work_source = source_path
        if max_eq_width is not None:
            work_path = images_dir / f"{original_stem}_eq{max_eq_width}.jpg"
            work_source = downscale_equirect_if_needed(source_path, work_path, max_eq_width)
        reference_path = None
        if not full_sphere:
            reference_path = ensure_reference_band(
                images_dir, work_source, vfov, elevation, eq_scale=1
            )

    frames = discover_planar_frames(images_dir, prefix)
    if not frames:
        raise SystemExit(
            f"No planars for prefix {prefix!r} in {images_dir}. "
            "Run extract_rectilinear_views or use --eq-scale to generate."
        )
    if not full_sphere and infer_full_sphere_from_frames(frames):
        full_sphere = True
        reference_path = None
        print(
            f"Inferred full-sphere layout from {len(frames)} planars "
            "(multiple elevation rings in filenames/manifest)"
        )
    if max_frames is not None and max_frames > 0:
        frames = frames[:max_frames]

    if out_asset_dir.exists():
        shutil.rmtree(out_asset_dir)
    out_asset_dir.mkdir(parents=True)

    shutil.copy2(work_source, out_asset_dir / work_source.name)
    if reference_path is not None:
        shutil.copy2(reference_path, out_asset_dir / reference_path.name)
    for fr in frames:
        shutil.copy2(images_dir / fr["file"], out_asset_dir / fr["file"])

    use_fast = True
    if fast_e2e_test:
        refine_seams = "none"
        run_capture = False
        production_replay = False
        skip_planar = True
    else:
        # Live capture refine is idle-triggered via worker when useProductionWorker.
        run_capture = run_capture_refinement and direct_finish_replay
        production_replay = True
        skip_planar = skip_planar_roundtrip
    use_production_worker = (
        production_replay and not fast_e2e_test and not direct_finish_replay
    )
    # Sweep-fast: direct seed ingest (no worker G1 drift) + global graph export.
    if sweep_fast:
        use_production_worker = False
    # Keep planar roundtrip enabled for production worker (primary alignment metric).
    # Sweep-fast degraded: skip G1-fast ingest (overlapGate=0); export chain+global solves poses.
    effective_overlap_gate = overlap_gate if imu_degrade_deg > 0 else 0.0
    if use_production_worker and sweep_fast and imu_degrade_deg > 0:
        effective_overlap_gate = 0.0
    if use_production_worker and sweep_fast:
        from PIL import Image

        with Image.open(work_source) as im:
            export_w, export_h = im.size
    else:
        export_w = PRODUCTION_EXPORT_W if use_production_worker else None
        export_h = PRODUCTION_EXPORT_H if use_production_worker else None
    manifest = build_manifest(
        work_source,
        reference_path,
        frames,
        fov,
        vfov,
        elevation,
        refine_passes=refine_passes,
        refine_seams=refine_seams,
        eq_scale=eq_scale,
        full_sphere=full_sphere,
        compare_full_equirect=full_sphere,
        imu_degrade_deg=imu_degrade_deg,
        imu_degrade_seed=imu_degrade_seed,
        run_capture_refinement=run_capture,
        use_fast_path=use_fast,
        overlap_gate=effective_overlap_gate,
        fast_e2e_test=fast_e2e_test,
        production_replay=production_replay,
        skip_planar_roundtrip=skip_planar,
        use_production_worker=use_production_worker,
        export_eq_w=export_w,
        export_eq_h=export_h,
        sweep_fast=sweep_fast,
    )
    (out_asset_dir / "manifest.json").write_text(
        json.dumps(manifest, indent=2), encoding="utf-8"
    )
    ref_note = reference_path.name if reference_path else "full equirect"
    fast_note = " sweepFast=True" if sweep_fast else ""
    print(
        f"Synced {len(frames)} planars + {work_source.name} + {ref_note} "
        f"(eqScale={eq_scale}, fullSphere={full_sphere}, imuDegrade={imu_degrade_deg}°, "
        f"production={production_replay}, worker={use_production_worker}, fastE2e={fast_e2e_test}"
        f"{fast_note}) "
        f"-> {out_asset_dir}"
    )
    return work_source, reference_path


def gradle_cmd(root: Path) -> list[str]:
    return [str(root / ("gradlew.bat" if sys.platform == "win32" else "gradlew"))]


def acquire_planar_replay_lock(root: Path, timeout_s: float = 7200.0) -> object:
    """Exclusive lock so concurrent sweeps cannot overwrite planar_replay/manifest.json."""
    lock_path = root / ".planar_replay_sync.lock"
    lock_file = open(lock_path, "w")
    lock_file.write("locked\n")
    lock_file.flush()
    start = time.monotonic()
    while True:
        try:
            if sys.platform == "win32":
                import msvcrt

                msvcrt.locking(lock_file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl

                fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            return lock_file
        except OSError:
            if time.monotonic() - start > timeout_s:
                raise SystemExit(
                    "Another planar replay / IMU sweep is running "
                    f"(lock: {lock_path}). Stop it before starting a new run."
                )
            print(f"Waiting for planar replay lock ({lock_path.name})...")
            time.sleep(3)


def run_instrumented_test(root: Path, device: str | None, no_build: bool) -> int:
    env = os.environ.copy()
    if device:
        env["ANDROID_SERIAL"] = device
    gw = gradle_cmd(root)

    if not no_build:
        for task in (
            [":app:assembleDebug", ":app:assembleDebugAndroidTest"],
            [":app:mergeDebugAndroidTestAssets"],
            [":app:installDebug", ":app:installDebugAndroidTest"],
        ):
            print("Running:", " ".join(gw + task))
            if subprocess.call(gw + task + ["--no-daemon"], cwd=root, env=env) != 0:
                return 1
    else:
        for task in (
            [":app:mergeDebugAndroidTestAssets", ":app:packageDebugAndroidTest"],
            [":app:installDebugAndroidTest"],
        ):
            print("Running:", " ".join(gw + task))
            if subprocess.call(gw + task + ["--no-daemon"], cwd=root, env=env) != 0:
                return 1
    grant_storage(device)
    clear_device_outputs(device)

    component = f"{TEST_PACKAGE}/{TEST_RUNNER}"
    cmd = adb_base(device) + [
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "class",
        TEST_CLASS,
        component,
    ]
    print("Running:", " ".join(cmd))
    instrument_timeout_s = 2_100.0
    try:
        proc = subprocess.run(
            cmd,
            cwd=root,
            env=env,
            text=True,
            capture_output=True,
            timeout=instrument_timeout_s,
        )
    except subprocess.TimeoutExpired as e:
        print(
            f"Instrumented test timed out after {instrument_timeout_s:.0f}s "
            "(increase E2E_JUNIT_TIMEOUT_MS or reduce IMU recovery cost)"
        )
        if e.stdout:
            print(e.stdout, end="" if e.stdout.endswith("\n") else "\n")
        if e.stderr:
            print(e.stderr, end="" if e.stderr.endswith("\n") else "\n", file=sys.stderr)
        return 1
    if proc.stdout:
        print(proc.stdout, end="" if proc.stdout.endswith("\n") else "\n")
    if proc.stderr:
        print(proc.stderr, end="" if proc.stderr.endswith("\n") else "\n", file=sys.stderr)
    if proc.returncode != 0:
        return proc.returncode
    out = proc.stdout or ""
    if (
        "Process crashed" in out
        or "INSTRUMENTATION_STATUS: Error" in out
        or "FAILURES!!!" in out
        or "TestTimedOutException" in out
    ):
        return 1
    if "INSTRUMENTATION_STATUS: code=-1" in out and "OK (" not in out:
        return 1
    return 0


def adb_base(device: str | None) -> list[str]:
    cmd = ["adb"]
    if device:
        cmd.extend(["-s", device])
    return cmd


def grant_storage(device: str | None) -> None:
    perms = (
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.READ_EXTERNAL_STORAGE",
    )
    for perm in perms:
        cmd = adb_base(device) + ["shell", "pm", "grant", PACKAGE, perm]
        subprocess.call(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def clear_device_outputs(device: str | None) -> None:
    """Remove stale stitched exports so a failed run cannot be pulled as success."""
    for remote_dir in (DEVICE_DOWNLOAD, PUBLIC_OUTPUT):
        cmd = adb_base(device) + ["shell", "rm", "-rf", remote_dir]
        subprocess.call(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    cmd = adb_base(device) + [
        "shell",
        "run-as",
        PACKAGE,
        "rm",
        "-f",
        "files/stitched_equirect.jpg",
        "files/reference_equirect.jpg",
    ]
    subprocess.call(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def pull_via_run_as(device: str | None, remote_name: str, dest: Path) -> bool:
    """Pull from app private storage using run-as (debug builds)."""
    cmd = adb_base(device) + [
        "shell",
        "run-as",
        PACKAGE,
        "cat",
        f"files/{remote_name}",
    ]
    try:
        proc = subprocess.run(
            cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False
        )
        if proc.returncode != 0 or not proc.stdout:
            return False
        dest.write_bytes(proc.stdout)
        return dest.stat().st_size > 1000
    except OSError:
        return False


def pull_json_artifact(
    device: str | None,
    remote_name: str,
    dest: Path,
) -> bool:
    """Pull a small JSON artifact from device download or run-as files dir."""
    if dest.is_file():
        dest.unlink()
    remotes = [
        f"{DEVICE_DOWNLOAD}/{remote_name}",
        f"/storage/emulated/0/Android/data/{PACKAGE}/files/{remote_name}",
        f"/sdcard/Android/data/{PACKAGE}/files/{remote_name}",
        f"{PUBLIC_OUTPUT}/{remote_name}",
    ]
    for remote in remotes:
        cmd = adb_base(device) + ["pull", remote, str(dest)]
        if subprocess.call(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL) == 0:
            if dest.stat().st_size > 10:
                print(f"Pulled {remote_name} from {remote}")
                return True
    if pull_via_run_as(device, remote_name, dest):
        print(f"Pulled {remote_name} via adb run-as")
        return True
    return False


def merge_device_replay_metrics(out: Path, metrics: dict, expected_imu_deg: float | None = None) -> dict:
    replay_path = out / "replay_metrics.json"
    if not replay_path.is_file():
        return metrics
    try:
        device_metrics = json.loads(replay_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return metrics
    if expected_imu_deg is not None:
        replay_imu = device_metrics.get("imu_degrade_deg")
        if replay_imu is not None and float(replay_imu) != float(expected_imu_deg):
            raise SystemExit(
                f"Stale device output: replay_metrics imu_degrade_deg={replay_imu} "
                f"!= expected {expected_imu_deg}. Re-run after clearing device exports."
            )
    merged = dict(metrics)
    merged["device_replay"] = device_metrics
    for key in (
        "planar_min_psnr_db",
        "planar_mean_psnr_db",
        "planar_min_correlation",
        "planar_mean_correlation",
        "planar_count",
        "equirect_correlation_mapped",
        "equirect_correlation_raw",
    ):
        if key in device_metrics:
            merged[key] = device_metrics[key]
    (out / "metrics.json").write_text(json.dumps(merged, indent=2), encoding="utf-8")
    return merged


def pull_outputs(
    root: Path,
    device: str | None,
    reference_band: Path | None,
    out_subdir: str | None,
    work_source: Path | None = None,
    expected_imu_deg: float | None = None,
    output_root: str | None = None,
) -> Path:
    out = output_dir(root, out_subdir, output_root=output_root)
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)

    if reference_band is not None:
        shutil.copy2(reference_band, out / "reference_equirect.jpg")
    elif work_source is not None:
        shutil.copy2(work_source, out / "reference_equirect.jpg")

    stitched_name = "stitched_equirect.jpg"
    dest = out / stitched_name
    pulled = False

    download_remote = f"{DEVICE_DOWNLOAD}/{stitched_name}"
    cmd = adb_base(device) + ["pull", download_remote, str(dest)]
    if subprocess.call(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL) == 0:
        if dest.stat().st_size > 1000:
            print(f"Pulled {stitched_name} from {download_remote}")
            pulled = True

    if not pulled and pull_via_run_as(device, stitched_name, dest):
        print(f"Pulled {stitched_name} via adb run-as")
        pulled = True

    if not pulled:
        remotes = [
            f"/storage/emulated/0/Android/data/{PACKAGE}/files/{stitched_name}",
            f"/sdcard/Android/data/{PACKAGE}/files/{stitched_name}",
        ]
        for remote in remotes:
            cmd = adb_base(device) + ["pull", remote, str(dest)]
            if subprocess.call(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL) == 0:
                if dest.stat().st_size > 1000:
                    print(f"Pulled {stitched_name} from {remote}")
                    pulled = True
                    break

    if not pulled:
        stale = f"{PUBLIC_OUTPUT}/{stitched_name}"
        cmd = adb_base(device) + ["pull", stale, str(dest)]
        if subprocess.call(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL) == 0:
            if dest.stat().st_size > 1000:
                print(
                    f"WARNING: pulled stale {stitched_name} from {stale} "
                    "(fresh output missing; metrics may not match device test)"
                )
                pulled = True

    if not pulled:
        raise SystemExit(
            f"Could not pull {stitched_name}. Check {PUBLIC_OUTPUT}/ on device."
        )

    pull_json_artifact(device, "replay_metrics.json", out / "replay_metrics.json")
    if expected_imu_deg is not None:
        replay_path = out / "replay_metrics.json"
        if replay_path.is_file():
            try:
                replay_imu = json.loads(replay_path.read_text(encoding="utf-8")).get(
                    "imu_degrade_deg"
                )
                if replay_imu is not None and float(replay_imu) != float(expected_imu_deg):
                    raise SystemExit(
                        f"Stale device output: replay_metrics imu_degrade_deg={replay_imu} "
                        f"!= expected {expected_imu_deg}. Clear device exports and re-run."
                    )
            except json.JSONDecodeError:
                pass

    return out


def run_local_compare(
    root: Path,
    out: Path,
    elevation: float,
    vfov: float,
    *,
    full_equirect: bool = False,
    expected_imu_deg: float | None = None,
) -> dict:
    compare_py = root / "scripts" / "compare_equirect.py"
    ref = out / "reference_equirect.jpg"
    stitched = out / "stitched_equirect.jpg"
    cmd = [
        sys.executable,
        str(compare_py),
        str(ref),
        str(stitched),
        "-o",
        str(out),
    ]
    if not full_equirect:
        cmd.extend(["--elevation", str(elevation), "--vfov", str(vfov)])
    else:
        cmd.append("--full")
    print("Running:", " ".join(cmd))
    subprocess.check_call(cmd)
    metrics = json.loads((out / "metrics.json").read_text(encoding="utf-8"))
    return merge_device_replay_metrics(out, metrics, expected_imu_deg=expected_imu_deg)


def parse_args() -> argparse.Namespace:
    root = repo_root()
    p = argparse.ArgumentParser(
        description="Stitch planar views, pull to PC, compare to 360° reference band."
    )
    p.add_argument(
        "--images",
        type=Path,
        default=root / "scripts" / "images",
        help="Folder with img1.jpg and planar_*.jpg",
    )
    p.add_argument("--source", default="img1.jpg", help="Reference equirect filename")
    p.add_argument("--fov", type=float, default=DEFAULT_FOV)
    p.add_argument(
        "--vfov",
        type=float,
        default=None,
        help="Reference band vertical FOV (default: same as --fov)",
    )
    p.add_argument("--elevation", type=float, default=DEFAULT_ELEVATION)
    p.add_argument("--device", type=str, default=None, help="ADB serial")
    p.add_argument("--sync-only", action="store_true")
    p.add_argument("--no-build", action="store_true")
    p.add_argument(
        "--skip-device",
        action="store_true",
        help="Only pull + compare (device must already have run the test)",
    )
    p.add_argument(
        "--max-frames",
        type=int,
        default=None,
        help="Use only the first N planars (ablation: 1 = single-frame)",
    )
    p.add_argument(
        "--refine-passes",
        type=int,
        default=3,
        help="refineStoredPoses + rebuildFullAlign iterations (default: 3)",
    )
    p.add_argument(
        "--refine-seams",
        choices=("light", "none", "full"),
        default="light",
        help="Seam refine mode written to manifest (default: light)",
    )
    p.add_argument(
        "--out-subdir",
        type=str,
        default=None,
        help="Subfolder under scripts/images/roundtrip_output/ for this run",
    )
    p.add_argument(
        "--output-root",
        type=str,
        default=None,
        help="Folder under scripts/images/ for outputs (default: roundtrip_output)",
    )
    p.add_argument(
        "--eq-scale",
        type=int,
        default=1,
        help="Upscale equirect N× before extract/stitch (S09: use 2 for 1300×650)",
    )
    p.add_argument(
        "--planar-size",
        type=int,
        default=None,
        help="Planar width/height (default: 1024 × eq-scale)",
    )
    p.add_argument(
        "--planar-count",
        type=int,
        default=6,
        help="Number of planars to extract when eq-scale > 1 (default: 6)",
    )
    p.add_argument(
        "--max-eq-width",
        type=int,
        default=4096,
        help="Downscale source equirect to this width for device stitch (0 = native size)",
    )
    p.add_argument(
        "--imu-degrade-deg",
        type=float,
        default=0.0,
        help=(
            "Deliberately corrupt per-frame orientation seed by up to this many degrees "
            "(uses addFrameFast + capture/finish refine, like live IMU error)"
        ),
    )
    p.add_argument(
        "--imu-degrade-seed",
        type=int,
        default=42,
        help="RNG seed for --imu-degrade-deg (reproducible per-frame errors)",
    )
    p.add_argument(
        "--overlap-gate",
        type=float,
        default=0.10,
        help="Overlap gate for addFrameFast when --imu-degrade-deg > 0",
    )
    p.add_argument(
        "--fast-e2e",
        action="store_true",
        help=(
            "Legacy fast smoke only (<30s, rotation-only global — not production). "
            "Default is production replay (full global + export, same as live DONE)."
        ),
    )
    p.add_argument(
        "--slow-e2e",
        action="store_true",
        help="Use 4096-wide equirect for device stitch (production refine, longer runtime)",
    )
    p.add_argument(
        "--reextract",
        action="store_true",
        help="Regenerate planar views before sync (e.g. after changing layout jitter)",
    )
    p.add_argument(
        "--run-capture-refine",
        action="store_true",
        help="Direct-finish replay only: run capture refine before finish export",
    )
    p.add_argument(
        "--direct-finish-replay",
        action="store_true",
        help=(
            "Skip production worker (no DONE/SAVE/polish); call runFinishExport directly "
            "like the legacy planar replay"
        ),
    )
    p.add_argument(
        "--sweep-fast",
        action="store_true",
        help=(
            "IMU sweep mode: 1024 export, skip long pre-finish/polish waits "
            "(metrics only; still uses production worker + G1-fast when imu>0)"
        ),
    )
    p.add_argument(
        "--random-roll",
        action="store_true",
        default=True,
        help="Random roll per planar on extract (default: on)",
    )
    p.add_argument(
        "--no-random-roll",
        action="store_false",
        dest="random_roll",
        help="Disable random roll on extract",
    )
    p.add_argument(
        "--random-heading",
        action="store_true",
        default=True,
        help="Jitter azimuth per view on extract (default: on)",
    )
    p.add_argument(
        "--no-random-heading",
        action="store_false",
        dest="random_heading",
        help="Disable heading jitter on extract",
    )
    p.add_argument(
        "--random-elevation",
        action="store_true",
        default=True,
        help="Jitter elevation per view on extract (default: on)",
    )
    p.add_argument(
        "--no-random-elevation",
        action="store_false",
        dest="random_elevation",
        help="Disable elevation jitter on extract",
    )
    p.add_argument(
        "--heading-range",
        type=float,
        default=15.0,
        help="±degrees azimuth jitter on extract (default: 15)",
    )
    p.add_argument(
        "--elevation-range",
        type=float,
        default=8.0,
        help="±degrees elevation jitter on extract (default: 8)",
    )
    p.add_argument(
        "--layout-seed",
        type=int,
        default=42,
        help="RNG seed for roll/heading/elevation jitter on extract (default: 42)",
    )
    return p.parse_args()


def main() -> None:
    args = parse_args()
    root = repo_root()
    images_dir = args.images.resolve()
    vfov = args.vfov if args.vfov is not None else args.fov
    eq_scale = max(1, int(args.eq_scale))
    planar_size = args.planar_size if args.planar_size else 1024 * eq_scale
    out_subdir = args.out_subdir
    if out_subdir is None and eq_scale > 1:
        out_subdir = f"exp_eq{eq_scale}x"

    max_eq_width = int(args.max_eq_width) if args.max_eq_width else None
    if max_eq_width == 0:
        max_eq_width = None
    fast_e2e = args.fast_e2e
    if args.sweep_fast:
        args.random_roll = False
        args.random_heading = False
        args.random_elevation = False
    if args.slow_e2e:
        if max_eq_width is None or max_eq_width < 4096:
            max_eq_width = 4096
    elif fast_e2e:
        if max_eq_width is None or max_eq_width > 1024:
            max_eq_width = 1024
    elif max_eq_width is None or max_eq_width > 1024:
        max_eq_width = 1024
    max_frames = args.max_frames
    if out_subdir is None and args.imu_degrade_deg > 0:
        if args.sweep_fast:
            deg_label = int(args.imu_degrade_deg) if args.imu_degrade_deg == int(args.imu_degrade_deg) else args.imu_degrade_deg
            out_subdir = f"{Path(args.source).stem}_imu{deg_label}deg"
        else:
            suffix = "4096" if args.slow_e2e else "prod"
            out_subdir = f"{Path(args.source).stem}_imu{int(args.imu_degrade_deg)}_{suffix}"
    elif out_subdir is None and args.source.startswith("img"):
        out_subdir = Path(args.source).stem + "_fullsphere"

    lock = acquire_planar_replay_lock(root)
    try:
        work_source, reference = sync_assets(
            images_dir,
            args.source,
            asset_dir(root),
            args.fov,
            vfov,
            args.elevation,
            eq_scale=eq_scale,
            planar_size=planar_size,
            planar_count=args.planar_count,
            max_frames=max_frames,
            refine_passes=args.refine_passes,
            refine_seams=args.refine_seams,
            max_eq_width=max_eq_width,
            imu_degrade_deg=args.imu_degrade_deg,
            imu_degrade_seed=args.imu_degrade_seed,
            overlap_gate=args.overlap_gate,
            fast_e2e_test=fast_e2e,
            run_capture_refinement=args.run_capture_refine,
            reextract=args.reextract,
            direct_finish_replay=args.direct_finish_replay,
            random_roll=args.random_roll,
            random_heading=args.random_heading,
            random_elevation=args.random_elevation,
            heading_range=args.heading_range,
            elevation_range=args.elevation_range,
            layout_seed=args.layout_seed,
            sweep_fast=args.sweep_fast,
        )
    finally:
        lock.close()

    extract_manifest = load_extract_manifest(images_dir, Path(args.source).stem)
    full_sphere = bool(extract_manifest and extract_manifest.get("full_sphere"))

    if args.sync_only:
        return

    test_exit_code = 0
    if not args.skip_device:
        test_exit_code = run_instrumented_test(root, args.device, args.no_build)
        if test_exit_code != 0:
            print(
                f"Instrumented test failed (code {test_exit_code}); "
                "pulling outputs anyway for metrics (stitch may still be on device)"
            )

    try:
        out = pull_outputs(
            root,
            args.device,
            reference,
            out_subdir,
            work_source=work_source,
            expected_imu_deg=args.imu_degrade_deg,
            output_root=args.output_root,
        )
    except SystemExit as e:
        if test_exit_code != 0:
            raise SystemExit(test_exit_code)
        raise e

    metrics = run_local_compare(
        root,
        out,
        args.elevation,
        vfov,
        full_equirect=full_sphere,
        expected_imu_deg=args.imu_degrade_deg,
    )

    if test_exit_code != 0:
        raise SystemExit(test_exit_code)

    print()
    print("=" * 60)
    print("ROUNDTRIP OUTPUT")
    print("=" * 60)
    print(f"  Folder:     {out}")
    print(f"  Stitched:   {out / 'stitched_equirect.jpg'}")
    ref_label = (
        f"full {work_source.name}"
        if full_sphere
        else f"360°×{vfov}° band from {reference.name if reference else work_source.name}"
    )
    print(f"  Reference:  {out / 'reference_equirect.jpg'}  ({ref_label})")
    print(f"  Comparison: {out / 'comparison.jpg'}")
    print(f"  Diff:       {out / 'diff.jpg'}")
    print(f"  Sharpness:  {out / 'sharpness.jpg'}")
    print(
        f"  Metrics:    correlation={metrics['correlation']} (mapped={metrics.get('correlation_mapped', 'n/a')})  "
        f"PSNR={metrics['psnr_db']} dB  "
        f"planarMeanPSNR={metrics.get('planar_mean_psnr_db', 'n/a')}  "
        f"planarMinPSNR={metrics.get('planar_min_psnr_db', 'n/a')}  "
        f"sharpRet={metrics.get('sharpness_retention', 'n/a')}"
    )
    print("=" * 60)

    if args.sweep_fast:
        replot_imu_sweep_summary(args.output_root)

    if test_exit_code != 0:
        raise SystemExit(test_exit_code)


if __name__ == "__main__":
    main()

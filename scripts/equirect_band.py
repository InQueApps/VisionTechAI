"""Equirectangular latitude-band crop (full 360° × vertical FOV)."""

from __future__ import annotations

import math
from typing import TYPE_CHECKING

import numpy as np

if TYPE_CHECKING:
    from pathlib import Path

PI = math.pi
DEG2RAD = PI / 180.0


def lat_band_row_range(
    eq_h: int, elevation_deg: float, vfov_deg: float
) -> tuple[int, int]:
    """
    Inclusive-exclusive row range [y0, y1) for a vertical band on an equirect.

    Matches extract_rectilinear_views / native equirect mapping:
      lat = 0 at horizon, +lat is up; y=0 is north pole.
    """
    el = elevation_deg * DEG2RAD
    half_v = vfov_deg * 0.5 * DEG2RAD
    lat_top = min(el + half_v, PI * 0.5)
    lat_bot = max(el - half_v, -PI * 0.5)

    y_top = int(math.floor((0.5 - lat_top / PI) * eq_h))
    y_bot = int(math.ceil((0.5 - lat_bot / PI) * eq_h))
    y0 = max(0, min(y_top, eq_h - 1))
    y1 = max(y0 + 1, min(y_bot, eq_h))
    return y0, y1


def extract_equirect_lat_band(
    equirect: np.ndarray, elevation_deg: float, vfov_deg: float
) -> np.ndarray:
    """Full-azimuth equirect crop with vertical extent vfov_deg centered at elevation_deg."""
    y0, y1 = lat_band_row_range(equirect.shape[0], elevation_deg, vfov_deg)
    return equirect[y0:y1, :, :].copy()


def reference_band_filename(stem: str, vfov_deg: float, eq_scale: int = 1) -> str:
    scale_tag = f"_eq{eq_scale}x" if eq_scale > 1 else ""
    return f"reference_{stem}{scale_tag}_h360_v{int(round(vfov_deg)):03d}.jpg"


def scaled_equirect_filename(stem: str, ext: str, eq_scale: int) -> str:
    if eq_scale <= 1:
        return f"{stem}.{ext}"
    return f"{stem}_eq{eq_scale}x.{ext}"


def find_reference_band(
    images_dir: Path, stem: str, vfov_deg: float, eq_scale: int = 1
) -> Path | None:
    expected = images_dir / reference_band_filename(stem, vfov_deg, eq_scale)
    if expected.is_file():
        return expected
    scale_tag = f"_eq{eq_scale}x" if eq_scale > 1 else ""
    matches = sorted(images_dir.glob(f"reference_{stem}{scale_tag}_h360_v*.jpg"))
    if matches:
        return matches[0]
    if eq_scale == 1:
        matches = sorted(images_dir.glob(f"reference_{stem}_h360_v*.jpg"))
        return matches[0] if matches else None
    return None

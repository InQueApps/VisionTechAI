# PRISM AI

Real-time on-device vision processing for Android. Live camera preview with selectable filters, robotics-oriented perception, text translation, and 360-degree panorama capture. All processing runs on the phone — no cloud required for core vision modes.

| | |
|---|---|
| Display name | PRISM AI |
| Application ID | `ab.inque.prismai` |
| Package | `ab.inque.visiontechai` |
| Version | 0.4.4 |
| Min SDK | 27 |
| Target SDK | 35 |

## Features

One vision mode is active at a time. Toolbar categories group related modes:

| Category | Modes |
|----------|-------|
| **NV** | Night vision — low-light enhancement with optional multi-frame stacking |
| **IMG** | Edge detection, color adjustment, HD resampling |
| **ROB** | Optical flow, color segmentation, monocular depth (MiDaS), object detection (YOLO11n) |
| **TXT** | Live text recognition and on-device translation overlay |
| **Sphere** | IMU-guided 360-degree panorama capture and interactive viewer |

Additional capabilities:

- Photo and video capture (raw camera or processed overlay when a filter is active)
- In-app gallery for saved images, videos, and translation exports
- RGB histogram and FPS HUD

## Stack

- **UI:** Android Views + ViewBinding
- **Camera:** CameraX (preview, capture, video, image analysis)
- **Native CV:** C++ JNI libraries (edge, flow, segmentation, night vision, sphere, etc.) — see `app/src/main/cpp/README.md`
- **ML:** TensorFlow Lite (YOLO11n, MiDaS), Google ML Kit (OCR, translation)
- **Analytics:** Firebase

## Build

**Requirements:** Android Studio, JDK 11, Android SDK 35, NDK 27, `app/google-services.json`

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

## Testing

See **[README_test.md](README_test.md)** for JVM unit tests, device E2E, robotics C++ host tests (`scripts/test-robotics-cpp.ps1`), and the planar roundtrip stitch workflow (`scripts/stitch_planar_roundtrip.py`).

### ML models

YOLO and MiDaS model files are not committed to git. Add them under `app/src/main/assets/` before using object detection or depth modes:

- `yolo11n_float16.tflite` and `class.txt` — object detection
- MiDaS TFLite (e.g. `midas_v2_small.tflite`) — depth field

The app runs without these assets; those modes simply produce no ML output until models are added.

## Permissions

Camera (required), microphone and media access for video recording and saving to the gallery.
# ParallaxWallpaper

An Android live wallpaper that uses **Depth Anything V2** (ONNX, quantized) to estimate per-pixel depth from any photo, then renders a real-time gyroscope-driven parallax effect via **OpenGL ES 2.0**.

## Architecture

```
Photo → DepthEstimator (ONNX Runtime) → depth map
                                              ↓
Gyroscope → GyroscopeSmoother → tilt offset → ParallaxEngine (OpenGL ES 2.0)
                                              ↓
                                    ParallaxWallpaperService (WallpaperService)
```

| Component | File | Description |
|---|---|---|
| Depth pipeline | `depth/DepthEstimator.kt` | ONNX Runtime, 518×518 input, NCHW float32, optional NNAPI |
| Sensor smoother | `sensor/GyroscopeSmoother.kt` | Integration + low-pass filter + centering decay |
| GL renderer | `wallpaper/ParallaxEngine.kt` | EGL14 context, 2-texture GLSL shader, per-pixel depth-based UV shift |
| Wallpaper service | `wallpaper/ParallaxWallpaperService.kt` | Dedicated render `HandlerThread`, coroutine-based asset loading |
| Setup UI | `ui/SetupScreen.kt` | Jetpack Compose – image picker, strength slider, fps selector, NNAPI toggle |

## Model

**depthanythingv2-vits-dynamic-quant.onnx** – Depth Anything V2 ViT-Small, INT8 quantized (~26 MB).  
Source: [combatwombat/tiefling](https://github.com/combatwombat/tiefling/tree/main/site/public/models)

## Quick start

### 1 — Clone and get the model

```powershell
# Windows
git clone <your-repo-url>
cd ParallaxWallpaper
powershell -ExecutionPolicy Bypass -File scripts/download_model.ps1
```

```bash
# Mac / Linux
git clone <your-repo-url> && cd ParallaxWallpaper
bash scripts/download_model.sh
```

The script downloads the model directly from the tiefling GitHub repo and places it in  
`app/src/main/assets/depthanythingv2-vits-dynamic-quant.onnx`.

### 2 — Build

Open in **Android Studio Ladybug** (or later) and click **Run**, or:

```bash
./gradlew installDebug
```

Requirements: Android SDK 35, JDK 17, device/emulator API 27+.

### 3 — Set the wallpaper

1. Launch the app.
2. Tap the image area to pick a photo.
3. Adjust parallax strength and frame rate.
4. Tap **Set as Live Wallpaper** — this opens the Android wallpaper picker.
5. Confirm. Tilt your phone to see the parallax effect.

> Depth estimation runs once (in the background) the first time the wallpaper becomes visible.  
> Subsequent visibility changes reuse the cached depth map.

## Dependencies

| Library | Version | Purpose |
|---|---|---|
| ONNX Runtime Android | 1.20.0 | Depth model inference |
| Jetpack Compose BOM | 2024.09.03 | Setup UI |
| DataStore Preferences | 1.1.1 | Persistent settings |
| Coil | 2.7.0 | Image preview |
| Kotlin Coroutines | 1.9.0 | Async depth estimation |

## How the parallax shader works

```glsl
float depth   = texture2D(uDepthTexture, vTexCoord).r;
float layer   = 1.0 - depth;          // near = 1, far = 0
vec2  shifted = vTexCoord + layer * uParallaxOffset * uParallaxStrength;
shifted       = abs(mod(shifted, 2.0) - 1.0);   // mirror-clamp
gl_FragColor  = texture2D(uImageTexture, shifted);
```

Near pixels (low depth value → high `layer`) shift most, far pixels barely move —
the classic parallax illusion without any 3-D geometry.

## Gyroscope smoother

1. Integrate raw angular velocity (rad/s × Δt) → angle (degrees)  
2. Clamp to ±12°  
3. Apply centering decay (`rawAngle *= 0.97`) each sample to prevent long-term drift  
4. Exponential low-pass filter (α = 0.18) to remove jitter  
5. Normalize to [−1, 1] for the shader uniform

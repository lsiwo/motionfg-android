# Motion FG for Android

[Русский](README.ru.md) · [Release notes](RELEASE_NOTES.md)

Motion FG adds frames and upscales games and videos on Android. Game mode draws the processed image over the selected app. Video mode saves an MP4. OpenGL ES runs frame generation, scaling, and color conversion on the device GPU.

## Install

Download `MotionFG-Android.apk` from the repository's **Releases** page. Motion FG requires Android 14 or newer and OpenGL ES 3.0. If Android hides the accessibility switch after sideloading, open **App info → ⋮ → Allow restricted settings**.

Use **Language** in the app to choose English, Russian, or the system language. **About** links to this project.

## Game mode

1. Open **Game**, tap **Enable overlay**, and enable the Motion FG service in Android accessibility settings.
2. Set the frame multiplier and scaling profile. Tap **Select game** and select **A single app** in the Android screen-sharing prompt. Then select the game.
3. Tap the draggable **FG** button to adjust the multiplier, V-Sync, low-latency mode, scaling profile, or input FPS limit during play. Use **Show original** to compare with the game directly.
4. Stop processing from the Android capture indicator, the notification, or the app.

The counter shows **game / output FPS**. The first number counts accepted unique captures. The second counts original and generated frames submitted for rendering. With V-Sync off, GPU output can exceed the panel's refresh rate. The panel displays at its supported refresh rate.

| Game profile | Capture scale per axis | Scaling filter |
| --- | --- | --- |
| Native (Родное) | 100% | Direct output or bilinear scaling when needed |
| Quality (Качество) | 67% | Edge-aware reconstruction with frame history |
| Balanced (Баланс) | 58% | Clamped bicubic |
| Performance (Производительность) | 50% | Five-tap bicubic |

The custom canvas accepts dimensions from 160 to 4096 pixels within GPU limits. Motion FG captures the selected game's finished image. At different source and screen aspect ratios, the image scales along each axis to keep all four edges visible.

**×1** disables frame generation. **×2**, **×3**, and **×4** set target multipliers. Low-latency mode presents a fresh original first and predicts motion between originals. Disabling it uses interpolation between two captured originals. During GPU overload, the renderer drops expired phases. The overlay can temporarily reveal the original game and recover automatically.

## Video and HDR

Open **Video**, choose a source, and set the multiplier, filter, output size, and color mode. The resolution presets specify the short edge. Export follows the source's timing and preserves its orientation and aspect ratio. You can copy the first audio track when MP4 supports its codec.

- Game output can use SDR, HDR10 PQ, or scRGB. The **FG** panel reports the negotiated format and lets you adjust SDR white in HDR mode.
- Video export uses hardware H.264 for SDR and HEVC Main10 for HDR10. **Auto** follows the input's dynamic range. The available resolution, frame rate, and HDR combinations depend on the device's codecs and GPU driver.

## Privacy

Game capture starts after approval in Android's MediaProjection dialog. The accessibility service hosts the overlay and checks system window bounds to give the notification shade space. Video input and export use Android's file picker. Game frames and chosen videos are processed locally. The app requests no internet permission.

## Build

Use Bash on Linux or WSL with JDK 17+, `zip`, and the Android SDK command-line tools. Set `ANDROID_HOME` to the SDK directory, then run:

```sh
sdkmanager "platforms;android-35" "build-tools;35.0.1"
bash build.sh
```

The script writes `MotionFG-Android.apk` beside `build.sh`. Pass a path as the first argument for a different output file. Without a supplied keystore, the script creates a local development key. A local build signed with that key needs a clean install when the device has a public build signed with a different key. Release maintainers can use `MOTION_FG_KEYSTORE`, `MOTION_FG_KEY_ALIAS`, `MOTION_FG_STORE_PASS`, and `MOTION_FG_KEY_PASS` to sign with their release credentials.

## Beta feedback

Use **Open test scene** to check frame generation without starting screen capture. For GitHub issues, include the Motion FG version, device and Android build, game or video format, capture profile, multiplier, V-Sync, HDR setting, and steps to reproduce.

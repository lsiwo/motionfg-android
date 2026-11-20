# SGSR 2 integration notes

## Vendored upstream files

The unmodified Qualcomm Snapdragon Game Super Resolution 2 two-pass fragment-shader variant is stored in this directory:

- `sgsr2_vertex.vs`
- `sgsr2_convert.fs`
- `sgsr2_upscale.fs`
- `README-upstream.md`
- `LICENSE`

Upstream: https://github.com/SnapdragonGameStudios/snapdragon-gsr  
Upstream revision (shader blobs):
- convert: `d104c97abb2fc69bf962e384c3910996800dcdad`
- upscale: `e0b492a955d6dba9f26f59505918cb0e1bdc75c7`
- vertex: `93382cf655e4a7a0f9a574ac988e0540195a1bf1`

License: BSD-3-Clause. Preserve the upstream copyright and license notice in source and binary distributions.

## Current Motion FG renderer constraints

Motion FG currently creates an OpenGL ES 3 context and uses GLES 3.00 shaders. The upstream SGSR 2 fragment variant declares GLSL ES 3.20 and Vulkan-style descriptor-set qualifiers (`set = 0`). It is not directly loadable by the current `GlProgram` path. A GLES port needs a controlled shader adaptation, verified on a real GLES 3.2 driver; blindly changing the version string is not sufficient.

The current capture path receives a composited application image through MediaProjection/VirtualDisplay. It does not receive the game's native depth buffer, camera matrices, per-object velocity buffer, pre-exposure, or jitter sequence. SGSR 2's two-pass path expects depth and clip-space velocity inputs plus a correctly maintained display-resolution history. Motion FG estimates optical flow from consecutive composited frames; that is not equivalent to native engine velocity/depth, especially around HUD, particles, transparency, disocclusion, and camera motion.

## Integration safety requirements

Do not expose SGSR 2 as a working selectable upscaler until all of these are implemented and validated:

1. GLES 3.2 shader adaptation and shader compile/link checks.
2. A velocity adapter with documented sign, coordinate-space, scale, and confidence handling.
3. A conservative depth strategy. A constant fake depth must not be represented as real scene depth.
4. Convert and Upscale render passes, with correct UBO offsets/alignment and sampler bindings.
5. Ping-pong history textures at display resolution; reset on scene cuts, source resize, profile changes, and capture restart.
6. GPU resource lifetime management on the GL render thread.
7. Fallback to the existing temporal/bicubic path if SGSR2 setup, framebuffer validation, or a frame pass fails.
8. Visual and thermal validation on Adreno 730 and at least one non-Adreno GLES 3.2 device.

## MFG-SUPER-MOTION

No source from NODIX-TECH/MFG-SUPER-MOTION is copied into this repository. Its current LICENSE is PolyForm Strict 1.0.0 and expressly restricts modification, redistribution, and derivative works. Only independently developed functionality based on general, non-proprietary concepts may be added without separate permission.

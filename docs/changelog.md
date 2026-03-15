## v2.4.0-3.0.0+vm.2

**Hotfix** — weather effects (rain, snow, particles) now render correctly in front of LODs on all GPUs. Previously, the Phase 2 composite was overwriting weather pixels with LOD colors on NVIDIA and potentially other non-MoltenVK drivers.


## v2.4.0-3.0.0+vm.1

**Clouds, weather, and DH 3.0 support.** Clouds now render correctly behind and in front of LOD terrain. Weather effects (rain, snow) are no longer hidden behind LODs. Compatible with both DH 2.4.x and 3.0.x (nightly) from a single jar.

### What's New

- **Cloud rendering** — clouds now render with correct depth against LOD terrain (on 1.21.11). Supports fast and fancy cloud modes.
- **Weather fixed** — rain, snow, and particles no longer render behind LODs. The rendering pipeline has been reordered so weather effects always appear in front of terrain as expected.
- **DH 3.0 support** — now works with both Distant Horizons 2.4.x and 3.0.x (nightly).
- **Smoother camera movement** — camera velocity now dynamically adjusts overdraw to prevent LOD pop-in when moving fast, matching base DH behavior.

### Known Issues

- Cloud rendering is only available on VulkanMod 0.6+ (MC 1.21.11). VM 0.4.2 uses MC's default cloud rendering.
- Rare LOD flicker when detail level changes during fast camera movement.

### Technical Details

- Custom Vulkan cloud renderer builds cloud geometry as VBO meshes and renders them with MC's projection matrix for accurate depth testing against LOD terrain.
- `uModelOffset` now uses Vulkan push constants on VM 0.6.1+ instead of per-draw UBO re-uploads, reducing CPU-side overhead.
- LOD compositing and post-processing (SSAO, fog) moved to `deferredComposite` to execute before MC's weather pass.
- In NONE fade mode, clouds render before the LOD composite so LODs draw on top (no depth buffer available for comparison).
- Added `DhFrameProfiler` — lightweight per-frame timing for all rendering phases (disabled by default, zero overhead when off).



## v2.4.0-2.4.6+vm.5

**Major VRAM fix** — GPU memory now properly cleans up during gameplay. Previously, VRAM would grow indefinitely (easily reaching 7GB+) as you explored or left and rejoined servers. With this fix, VRAM stays stable under normal use and reliably frees up between server sessions. Additionally, rendering at extreme altitudes and high render distances is now more stable, and SSAO performance is improved at long distances.

### Known Issues

- Rare LOD flicker: individual LOD sections may briefly disappear for a frame when their detail level changes (e.g. flying fast towards terrain). Will be further improved in future releases.

### Memory Management (Technical Details)

- **Fixed primary VRAM leak**: VBO destruction (`destroyAsync`/`close`) now schedules cached GPU `VertexBuffer`s for deferred freeing via a thread-safe pending queue. Previously, GPU buffers remained cached permanently — growing proportionally to total LOD churn.
- GPU buffer frees use an N+1 frame delay with compare-and-remove to prevent LOD transition flicker and identity hash collisions after GC.
- Fixed static Vulkan resource leak — `depthOnlyView` and `mcDepthCopyImage` were never freed during cleanup.
- Fixed VRAM leak in `DhDepthReaderPipeline` — cleanup and resize were completely unimplemented, leaking resources on every server join/leave cycle.
- Fixed native memory leak in `DhCompositePipeline` — four MappedBuffers were never freed during cleanup.
- Fixed stale resize callbacks — pipeline resize handlers now guard against firing after cleanup, preventing use-after-free across reinit cycles.
- Added `depthReaderPipeline` to the cleanup chain.
- Added `vkDeviceWaitIdle` at start of cleanup to ensure GPU is idle before freeing resources.
- Pre-allocated index buffer to 256K quads (~6MB) to avoid mid-frame grow+defer cycles.

### Rendering

- Switched terrain, SSAO, and fog rendering to use DH's projection matrix instead of MC's, preventing rendering artifacts and NaN propagation at extreme altitudes.
- Added depth remapping in the composite shader for correct occlusion against Minecraft terrain.
- SSAO now fades out beyond 1,600 blocks — the occlusion shader early-outs for distant fragments, improving performance at high render distances.



## v2.4.0-2.4.6+vm.4

First release as a standalone Fabric extension. Works alongside unmodified Distant Horizons 2.4.0+.

### Added

- Supports MC 1.20.6 (VulkanMod 0.4.2) and MC 1.21.11 (VulkanMod 0.6.1) from a single codebase
- Ported Vulkan rendering backend from a DH source fork to a mixin-based extension mod
- All DH interception via mixins, no DH source modifications required
- Standalone jar installable alongside any compatible DH release

### Included Features (from fork)

- Native Vulkan LOD rendering via VulkanMod
- DH-owned framebuffer with composite pipeline
- SSAO post-processing (2-pass, configurable)
- Fog post-processing (distance + height fog)
- Noise/dithering on LOD terrain
- Lightmap integration via VulkanMod's GL emulation
- Deterministic GPU memory management with buffer cache

### MC Depth Comparison

- Full per-pixel MC depth comparison: LODs are hidden wherever Minecraft terrain has rendered, using MC's actual depth buffer values instead of a fixed clip distance.
- Shader-based depth reader pipeline (`DhDepthReaderPipeline`) copies MC's swapchain depth into a sampleable R32F texture. This works on all platforms, including NVIDIA Windows where the swapchain depth can't be directly sampled as a texture.
- MC depth debug visualization (mode 6) shows the actual depth buffer values, useful for diagnosing depth-related rendering issues (debug modes can be enabled via /dh-debug num).

### Overdraw Prevention

- LOD overdraw prevention matches original DH behavior: LODs within MC's render distance are clipped based on the `overdrawPrevention` config setting.
- Hardware `gl_ClipDistance` clips LOD geometry in the vertex shader, less fragment shader cost for clipped geometry.
- Dithered fade transition in the fragment shader provides a smooth visual boundary between MC terrain and LODs when dithering is enabled.

### Fade Mode Support

- Respects DH's `vanillaFadeMode` setting (NONE / SINGLE_PASS / DOUBLE_PASS):
  - **NONE**: LODs composite immediately with optional MC depth for debug mode.
  - **SINGLE/DOUBLE**: LODs composite first without MC depth, then `deferredComposite` adds MC depth occlusion after terrain renders.
- Unified rendering path for both MC versions — no version-specific branching in the composite logic.

### Technical Details

- MC 1.20.6 compatibility required resolving mixin conflicts (`DhVulkanMixinPlugin`), preventing GL context poisoning (`MixinLightMapWrapper`), bypassing VM 0.4.2's hardcoded UINT16 index type, wiring custom uniform suppliers, and normalizing config value scaling.
- All version-specific code centralized in `Compat.java` using Manifold preprocessor directives.




## v2.4.6+vm.3

### Rendering Fixes

- Fixed LODs only rendering on half the screen (diagonal artifact) on NVIDIA GPUs. The fullscreen composite quad's vertex buffer data was read incorrectly by the NVIDIA driver, causing one of two triangles to never draw.
- Fixed composite depth test failing against uninitialized depth buffer data on NVIDIA. MoltenVK silently clears depth to 1.0, hiding the bug on macOS.
- Fixed MC terrain not rendering correctly in front of LODs in certain screen regions.

### Rendering Pipeline Changes

- LOD compositing now happens before MC terrain renders, not after. MC's opaque terrain overwrites LODs via depth test, and transparent terrain (water, leaves, glass) renders on top with alpha blending — matching vanilla MC behavior.
- Reduced LOD clip distance to a fixed 7 blocks. Since MC terrain now renders after LODs, MC's own depth test handles occlusion naturally. The small clip distance only prevents LOD bleed-through at leaf cutout edges and other transparent block boundaries.

### Composite, SSAO, and Fog Pipelines

- All fullscreen passes (composite, SSAO, fog) now use a single oversized triangle with vertex positions generated in the shader, bypassing vertex buffer data entirely. This avoids the NVIDIA issue and is the standard Vulkan best practice for post-processing.
- Added debug visualization modes to the composite pipeline: depth, SSAO, fog alpha, fog color, and normals.

### Config and UI

- Unsupported settings on the Vulkan path (wireframe, instanced rendering, vanilla fog, OpenGL debug options) are now automatically locked or hidden in the settings UI.

### Technical Details

- Fullscreen triangle vertices at NDC (-1,-1), (3,-1), (-1,3) cover the entire viewport after GPU clipping. Positions are generated from `gl_VertexIndex` in the vertex shader — no vertex buffer data is read.
- Composite depth function set to `GL_ALWAYS` because MC's depth buffer uses `VK_ATTACHMENT_LOAD_OP_DONT_CARE`, which produces undefined values on NVIDIA but implicit zeros on MoltenVK.
- Added deferredComposite() to `IVulkanRenderDelegate` for the new render ordering.


## v2.4.6+vm.2

**Added**
- SSAO (screen-space ambient occlusion) — 2-pass Vulkan post-process with configurable strength, bias, radius, and sample count. Respects all DH SSAO config settings.
- Fog rendering — distance fog and height fog with all three falloff types (linear, exponential, exponential squared) and all 10 mixing modes. Respects all DH fog config settings including underwater override.
- Noise/dithering — procedural per-block noise applied to LOD terrain. Controlled by the existing noise config (enable, steps, intensity, dropoff).
- Fade/clip distance transitions between MC terrain and LODs.

**Fixed**
- GPU memory leak where destroyed LOD sections were never freed from the Vulkan buffer cache.
- GPU memory leak where uploadVertexData() allocated new buffers every frame instead of reusing cached ones.
- LOD depth values now use MC's projection matrix, fixing LODs rendering in front of vanilla terrain.

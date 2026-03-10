## v2.4.0-2.4.6+vm.5

### Memory Management

- Fixed VRAM leak in `DhDepthReaderPipeline` — cleanup and resize were completely unimplemented (empty methods), leaking framebuffer, render pass, pipeline, and vertex buffer on every server join/leave cycle.
- Fixed native memory leak in `DhCompositePipeline` — four MappedBuffers (`invProjBuf`, `mcProjBuf`, `debugModeBuf`, `useMcDepthBuf`) were never freed during cleanup.
- Fixed stale resize callbacks — all pipeline resize handlers now guard against firing after cleanup, preventing use-after-free when callbacks stack across reinit cycles.
- Added `depthReaderPipeline` to the cleanup chain — it was created during init but never freed during cleanup.
- Added `vkDeviceWaitIdle` at start of cleanup to ensure the GPU is idle before scheduling resource destruction. Prevents deferred-free races during server transitions.
- Pre-allocated index buffer to 256K quads (~6MB) to avoid mid-frame grow+defer cycles that temporarily doubled index buffer VRAM.

### Rendering

- Switched terrain, SSAO, and fog rendering to use DH's projection matrix (`dhProjectionMatrix`) instead of MC's. DH's projection extends the near clip plane when the camera is far above max world height, preventing far-rendering artifacts and NaN propagation through shaders on some drivers.
- Added depth remapping in the composite shader (`remapDepthDhToMc`) to convert DH depth values back to MC-compatible depth for correct occlusion against Minecraft terrain.
- SSAO now fades out beyond 1,600 blocks using `smoothstep`, matching DH upstream behavior. The occlusion compute shader (`dh_ssao.frag`) early-outs for distant fragments, skipping the expensive spiral sampling entirely — measurable performance improvement at high render distances.

### Compatibility

- VulkanMod dependency now enforces minimum version per MC version: 0.4.2 for MC 1.20.x, 0.6.0 for MC 1.21.x. Previously accepted any version (`*`).


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

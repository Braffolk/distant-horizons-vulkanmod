## TODO TITLE

First release as a standalone Fabric extension mod (`dh-vulkanmod`). Works alongside unmodified Distant Horizons 2.4.5+.

### Architecture

- Ported Vulkan rendering backend from a DH source fork to a mixin-based extension mod
- All DH interception via mixins — no DH source modifications required
- Standalone jar installable alongside any compatible DH release

### Critical Fix: Missing LOD Faces

- **Fixed** east/west-facing LOD faces not rendering. The initial approach intercepted vertex data too deep in DH's GL call stack (`GLBuffer.uploadBuffer()`), which was fragile and version-dependent. Replaced with `MixinLodBufferContainer` that intercepts `uploadBuffersDirect()` at the same level the fork operated at — grabbing raw ByteBuffers directly from the quad builder and bypassing the entire GL upload pipeline.

### Included Features (from fork)

- Native Vulkan LOD rendering via VulkanMod
- DH-owned framebuffer with composite pipeline
- SSAO post-processing (2-pass, configurable)
- Fog post-processing (distance + height fog)
- Noise/dithering on LOD terrain
- Lightmap integration via VulkanMod's GL emulation
- Deterministic GPU memory management with buffer cache

---

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

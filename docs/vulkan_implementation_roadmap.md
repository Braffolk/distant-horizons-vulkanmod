# Distant Horizons → VulkanMod: Implementation Roadmap

Status as of 2026-03-07: **Phases 1-6 complete, SSAO ported.** LODs render with correct colors, depth, lightmap, transparency, compositing, and SSAO. Next: fog, noise, clouds.

## Architecture Overview

Files under `fabric/src/main/java/com/seibel/distanthorizons/fabric/vulkan/`:
- **`VulkanRenderContext.java`** — Pipeline creation, UBO management, shader conversion, draw calls
- **`VulkanRenderDelegate.java`** — `IVulkanRenderDelegate` impl, per-frame uniform fill, VBO upload
- **`DhVulkanFramebuffer.java`** — DH-owned Vulkan framebuffer with color+depth, auto-resize *(Phase 6)*
- **`DhCompositePipeline.java`** — Fullscreen quad composite with depth bias *(Phase 6)*
- **`IVulkanRenderDelegate.java`** — Interface in core (at `core/render/renderer/`)

Vulkan shaders under `coreSubProjects/core/src/main/resources/shaders/vulkan/`:
- **`dh_apply.vert`** — Composite vertex shader (fullscreen quad, Y-flip for Vulkan)
- **`dh_apply.frag`** — Composite fragment shader (depth bias, `gl_FragDepth`)

DH's `LodRenderer` detects VulkanMod via `GLProxy.isVulkanModActive()` and delegates to the Vulkan path.

---

## Phase 1: Fix Visual Rendering ✅ COMPLETE

**Root causes found and fixed:**
1. **White LODs + 180° culling**: Vertex and fragment shaders had different UBO layouts at `binding=0`. Fragment shader read matrix data as `uClipDistance` → garbage values → wrong discard
2. **`bool` compile error**: Changed `bool` → `int` in UBO but forgot `!var` → `var == 0` conversion
3. **Dark colors**: `VTextureSelector.getImage(0)` returns block atlas, not lightmap. Sampling atlas with lightmap UVs → garbage. Bypassed with full brightness constant
4. **Transparency**: MC's blend state inherited by DH pipeline. Disabled `PipelineState.blendInfo.enabled`
5. **No depth writes**: MC had `depthMask=false`. Overridden to `true`
6. **Lightmap always daytime**: Three bugs — (a) texelFetch coordinates swapped (blockLight,skyLight) vs original (skyLight,blockLight), (b) unnecessary `.bgr` swizzle, (c) replaced CPU `LightmapManager` with MC's framebuffer lightmap via `GlTexture.glId()` → `VkGlTexture` → `VulkanImage`

---

## Phase 2: Depth Integration ✅ COMPLETE
- [x] LODs render behind MC terrain via depth bias
- [x] Uses MC's projection matrix (not DH's) for compatible depth values
- [x] Lightmap: uses MC's framebuffer-rendered lightmap via VulkanMod's GL emulation layer

---

## Phase 3: Transparency / Blending ✅ COMPLETE
- [x] Added `setBlendState(boolean)` to `IVulkanRenderDelegate` — toggles `PipelineState.blendInfo`
- [x] Sets blend functions: `SRC_ALPHA/ONE_MINUS_SRC_ALPHA` (RGB), `ONE/ONE_MINUS_SRC_ALPHA` (alpha)
- [x] Re-binds pipeline after blend state change (VulkanMod bakes blend into pipeline objects)

---

## Phase 4: Buffer Management & Performance ✅ COMPLETE
- [x] VBO cache refactored with `CachedBuffer` (tracks Vulkan buffer + ByteBuffer identity)
- [x] Invalidation: detects when `vulkanBufferHandle` changes and frees old GPU buffer
- [x] Proper cleanup in `cleanup()` frees all cached Vulkan buffers
- [x] Vertex buffers use `GPU_MEM` (device-local VRAM) with automatic staging via VulkanMod

---

## Phase 5: State Management ✅ COMPLETE
- [x] Full save/restore in `beginFrame()`/`endFrame()`: `cull`, `depthMask`, `depthFun`, `topology`, `polygonMode`, `blendInfo` (all 6 fields)
- [x] Explicit `topology = TRIANGLE_LIST`, `polygonMode = FILL` set per frame

---

## Phase 6: Framebuffer / Render Pass Integration ✅ COMPLETE

### What was implemented
- [x] **DH-owned Vulkan framebuffer** (`DhVulkanFramebuffer.java`) — color (RGBA8) + depth attachments with `SAMPLED_BIT`
- [x] **Render pass** with `LOAD_OP_CLEAR/STORE_OP_STORE` on both attachments, `finalLayout = SHADER_READ_ONLY_OPTIMAL`
- [x] **Render pass switching** — flow: End MC pass → Begin DH pass → Render LODs → End DH pass → Rebind MC pass → Composite
- [x] **Composite pipeline** (`DhCompositePipeline.java`) — fullscreen quad with depth writes via `gl_FragDepth`
- [x] **Composite shaders** (`dh_apply.vert`, `dh_apply.frag`) — Vulkan GLSL 450, Y-flip for framebuffer coords
- [x] **Depth bias** (+0.0001 in composite shader) — LODs always slightly behind MC terrain, no z-fighting
- [x] **`uClipDistance`** — uses `RenderUtil.getNearClipPlaneInBlocks()` to prevent double-rendering of transparent blocks (water, leaves) in near zone
- [x] **Window resize** — auto-recreate framebuffer via `Renderer.addOnResizeCallback()`
- [x] **Back-face culling** enabled — ~50% fragment reduction, +50fps on M1 Max (170fps vs 120fps)
- [x] **Explicit depth test** — `depthTest=true`, `depthFun=GL_LEQUAL` ensures Early-Z hardware is active
- [x] **Removed `polygonOffset`** — no longer needed with own framebuffer + depth bias

### Caveats & future considerations
- **Transparent block overlap**: `uClipDistance` handles near-zone transparency (water, leaves, glass). Without it, LODs composite before MC's translucent pass, causing double-rendering. The ideal per-pixel MC depth check is not possible in Vulkan without copying MC's depth buffer to a separate texture first (can't sample an attachment while it's in the active render pass). This is a potential future improvement.
- **Vulkan constants inlined**: `DhVulkanFramebuffer.java` and `DhCompositePipeline.java` inline Vulkan VK10 constants as `static final int` because `org.lwjgl.vulkan` is not on the fabric module's compile classpath.
- **VRenderSystem uses GL constants**: All `depthFun` and similar values must use GL constants (e.g., `GL_LEQUAL=515`, `GL_ALWAYS=519`), not raw Vulkan values. VulkanMod converts them internally via `PipelineState.DepthState.glToVulkan()`.
- **Dummy UBO at binding 0**: VulkanMod's pipeline system requires at least one UBO. The composite pipeline uses a 4-byte dummy.

### What Phase 6 unlocks
- SSAO (can sample DH's depth texture)
- Fog (can compute distance from DH depth)
- Proper transparent LOD rendering via separate passes

---

## Phase 7: Shader Features
- [x] **SSAO** — Vulkan-native 2-pass post-process, see details below
- [x] **Noise texture** — integrated into terrain fragment shader (`flat_shaded.frag`): procedural per-block dithering via `uNoiseEnabled`, `uNoiseSteps`, `uNoiseIntensity`, `uNoiseDropoff` uniforms populated from config
- [x] **Fog** — Vulkan-native 2-pass post-process via `DhFogPipeline.java`, see details below
- [ ] **Earth curvature** — vertex shader curves terrain based on `uEarthRadius` — verify float precision with large world coordinates
- [ ] **Wireframe debug** — needs `VK_POLYGON_MODE_LINE` pipeline variant
- [ ] **Cloud rendering** — DH renders vanilla-style clouds to LOD distance (GL-only, needs investigation)

### SSAO Implementation Details

**Files:**
- `DhSsaoPipeline.java` — orchestrates both passes, manages resources
- `dh_ssao.vert` — shared fullscreen quad vertex shader (Vulkan Y-flip)
- `dh_ssao.frag` — pass 1: spiral depth-sampling occlusion computation
- `dh_ssao_apply.frag` — pass 2: optional bilateral blur + multiplicative apply

**Architecture:**
- **Pass 1** renders into an intermediate R16F `Framebuffer` (raw occlusion values 0.0–1.0)
- **Pass 2** reads the R16F texture + DH depth, applies blur (currently disabled for perf), and multiplicatively blends onto DH's color buffer using `LOAD_OP_LOAD`
- Inserted in `VulkanRenderDelegate.endFrame()` between ending DH's render pass and the composite pass
- Gated on `Config.Client.Advanced.Graphics.Ssao.enableSsao`

**Current Parameters (tuned for Vulkan path):**
- `uSampleCount=4` (GL uses 6, reduced for perf)
- `uRadius=4.0`, `uStrength=0.18`, `uMinLight=0.30`, `uBias=0.025`
- `gBlurRadius=0` (blur disabled for performance, bilinear filtering on R16F suffices)
- Pass 2 render pass is cached (created once, reused every frame)

> [!IMPORTANT]
> **Critical: Do NOT apply Y-flip to depth reconstruction in `dh_ssao.frag`.**
> The Vulkan vertex shader flips TexCoord.y for framebuffer sampling, and the natural instinct is to negate Y when converting TexCoord→NDC for the inverse projection matrix (since MC's projection uses GL convention with Y=+1 at top). However, testing showed this produces overly aggressive, unnatural occlusion with harsh dark halos. The non-flipped version produces visually correct, MC-like ambient occlusion. This is likely because VulkanMod's viewport flip already handles the Y convention, making the depth values in DH's framebuffer consistent with the TexCoord mapping.

> [!WARNING]
> **Performance: SSAO at retina resolution is expensive.**
> On M1 Max at retina fullscreen (~5M pixels), SSAO costs ~35% FPS (155→99). Half-resolution rendering was attempted but caused horizontal banding artifacts — likely needs better bilinear upscale handling or a dedicated upscale pass. Current mitigations: sample count reduced to 4, blur disabled. Future options: compute shader single-pass, temporal accumulation, or quarter-res with smart upscale.

> [!NOTE]
> **Projection matrix:** The Vulkan SSAO uses `mcProjectionMatrix` (not `dhProjectionMatrix`) because DH's LODs are rendered with MC's projection in the Vulkan path (for depth compatibility with MC terrain compositing). This differs from the GL path which uses `dhProjectionMatrix`. The parameters were tuned accordingly.

### Fog Implementation Details

**Files:**
- `DhFogPipeline.java` — orchestrates both passes, manages resources (~25 config-driven uniforms)
- `dh_fog.frag` — pass 1: depth → world pos reconstruction, far fog + height fog (3 falloff types, 10 mixing modes)
- `dh_fog_apply.frag` — pass 2: depth-gated fog texture passthrough with SRC_ALPHA blend
- Reuses `dh_ssao.vert` for fullscreen quad vertex shader

**Architecture:**
- **Pass 1** renders into intermediate RGBA16F `Framebuffer` (fog color + alpha 0.0–1.0)
- **Pass 2** reads fog texture + DH depth, applies depth-gated fog with `SRC_ALPHA / ONE_MINUS_SRC_ALPHA` blend onto DH's color buffer
- Inserted in `VulkanRenderDelegate.endFrame()` after SSAO, before composite
- Gated on `Config.Client.Advanced.Graphics.Fog.enableDhFog`
- Pass 2 render pass is cached (same pattern as SSAO)

> [!IMPORTANT]
> **Projection matrix for fog:** Like SSAO, the fog pipeline uses `mcProjectionMatrix * dhModelViewMatrix` for world position reconstruction. Initially `dhProjectionMatrix` was used (matching the GL path), but this caused all LODs to render as 100% fog because the depth values were produced with `mcProjectionMatrix`. The inverse matrix must always match the matrix that produced the depth buffer.

---

## Phase 8: Iris/Shader Pack Compatibility — N/A
- VulkanMod does not support shader packs — this phase is blocked until it does
- DH's Iris integration (`IRIS_ACCESSOR`) is skipped for Vulkan path
- Custom shader overrides via `IDhApiShaderProgram` remain GL-only

---

## Phase 9: Edge Cases & Robustness
- [ ] Handle renderer reset (e.g., resource pack reload, window resize)
- [ ] Handle VulkanMod not being loaded (graceful fallback to GL)
- [ ] Thread safety: DH runs LOD generation on worker threads — Vulkan buffer uploads must happen on the render thread
- [ ] Config hot-reload: DH allows changing settings without restart

---

## Key Files Reference

| File | Purpose |
|------|---------|
| `LodRenderer.java` | Main render entry point, Vulkan path branching |
| `VulkanRenderContext.java` | Pipeline, UBOs, shader conversion, draw API |
| `VulkanRenderDelegate.java` | Per-frame uniforms, VBO cache, draw dispatch, render pass switching |
| `DhVulkanFramebuffer.java` | DH-owned Vulkan framebuffer (color + depth) |
| `DhCompositePipeline.java` | Fullscreen quad composite pipeline |
| `dh_apply.vert` / `dh_apply.frag` | Vulkan composite shaders |
| `GLVertexBuffer.java` | VBO with `vulkanBufferHandle` ByteBuffer field |
| `LodBufferContainer.java` | Uploads vertex data, stores `vulkanBufferHandle` |
| `DhTerrainShaderProgram.java` | GL shader/VAO setup (reference for vertex format) |
| `VertexFormats.java` | DH's custom `LodVertexFormat` definition |
| `GraphicsPipeline.java` | VulkanMod pipeline creation, vertex input mapping |
| `PipelineState.java` | VulkanMod render state (cull, blend, depth) |
| `VRenderSystem.java` | VulkanMod's GL state tracker |

## Vertex Format (16 bytes per vertex)

```
Offset  Size  GL Attr  Vulkan Format              Shader Input
0       8B    attr 0   R16G16B16A16_SINT          ivec4 vPosition (x,y,z + meta)
8       4B    attr 1   R8G8B8A8_UNORM             vec4 color (RGBA)
12      4B    attr 2   R32_SINT                   (material/normal/padding, unused by shader currently)
```

DH packs position (3 unsigned shorts) + light/micro-offset metadata into `vPosition.a`.
The shader casts `ivec4 → uvec4` for bitwise operations on the metadata.

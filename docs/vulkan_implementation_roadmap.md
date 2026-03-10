# Distant Horizons → VulkanMod: Implementation Roadmap

Status as of 2026-03-09: **Phases 1-7, extension mod port, and multi-version support complete.** LODs render with correct colors, depth, lightmap, transparency, compositing, SSAO, fog, noise, and earth curvature via an extension mod (mixin-based, no DH source modifications). Supports MC 1.20.6 (VulkanMod 0.4.2) and MC 1.21.11 (VulkanMod 0.6.1) from a single codebase.

## Architecture Overview

The Vulkan backend is implemented as a **Fabric extension mod** (`dh-vulkanmod`) in the `vulkan/` subproject. It requires Distant Horizons to be installed and uses **mixins** to intercept DH's rendering pipeline without modifying DH's source code. All version-specific API differences are centralized in `Compat.java` using Manifold preprocessor directives.

Core files under `vulkan/src/main/java/com/braffolk/dhvulkan/`:
- **`VulkanRenderContext.java`** — Pipeline creation, UBO management, shader conversion, draw calls
- **`VulkanRenderDelegate.java`** — `IVulkanRenderDelegate` impl, per-frame uniform fill, VBO cache
- **`DhVulkanFramebuffer.java`** — DH-owned Vulkan framebuffer with color+depth, auto-resize
- **`DhCompositePipeline.java`** — Fullscreen triangle composite with depth bias
- **`DhSsaoPipeline.java`** — 2-pass SSAO post-process
- **`DhFogPipeline.java`** — 2-pass fog post-process (distance + height)
- **`IVulkanRenderDelegate.java`** — Interface for the Vulkan delegate
- **`DhVulkanMixinPlugin.java`** — IMixinConfigPlugin for resolving mixin conflicts between DH and VulkanMod
- **`compat/Compat.java`** — Single source of truth for all version-specific API differences

Mixins under `vulkan/src/main/java/com/braffolk/dhvulkan/mixin/`:
- **`MixinLodBufferContainer`** — Intercepts `uploadBuffersDirect()` to store raw vertex data directly
- **`MixinLodRenderer`** — Redirects `LodRenderer.renderLodPass()` to the Vulkan delegate
- **`MixinLevelRenderer`** — Triggers composite after MC terrain renders
- **`MixinGLProxy`** — Creates dummy GLProxy, bypasses GL thread assertions
- **`MixinGLBuffer`** — Cancels GL buffer operations (bind, create, upload, destroy)
- **`MixinGLVertexBuffer`** — Duck interface for storing Vulkan buffer handles on VBOs
- **`MixinGLState`** — Cancels GL state machine operations
- **`MixinLightMapWrapper`** — Cancels raw GL calls in DH's LightMapWrapper (prevents crashes in Vulkan context)

Vulkan shaders under `vulkan/src/main/resources/shaders/vulkan/`:
- **`dh_apply.vert`** / **`dh_apply.frag`** — Composite shaders
- **`dh_ssao.vert`** / **`dh_ssao.frag`** / **`dh_ssao_apply.frag`** — SSAO shaders
- **`dh_fog.frag`** / **`dh_fog_apply.frag`** — Fog shaders

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
- [x] **Composite pipeline** (`DhCompositePipeline.java`) — fullscreen triangle (via `gl_VertexIndex`) with depth writes via `gl_FragDepth`
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
- [x] **Earth curvature** — vertex shader curves terrain based on `uEarthRadius`
- [ ] **Wireframe debug** — needs `VK_POLYGON_MODE_LINE` pipeline variant
- [ ] **Cloud rendering** — DH renders vanilla-style clouds to LOD distance (GL-only, needs investigation)

### SSAO Implementation Details

**Files:**
- `DhSsaoPipeline.java` — orchestrates both passes, manages resources
- `dh_ssao.vert` — shared fullscreen triangle vertex shader (`gl_VertexIndex`, Y-flip)
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
- Reuses `dh_ssao.vert` for fullscreen triangle vertex shader

**Architecture:**
- **Pass 1** renders into intermediate RGBA16F `Framebuffer` (fog color + alpha 0.0–1.0)
- **Pass 2** reads fog texture + DH depth, applies depth-gated fog with `SRC_ALPHA / ONE_MINUS_SRC_ALPHA` blend onto DH's color buffer
- Inserted in `VulkanRenderDelegate.endFrame()` after SSAO, before composite
- Gated on `Config.Client.Advanced.Graphics.Fog.enableDhFog`
- Pass 2 render pass is cached (same pattern as SSAO)

> [!IMPORTANT]
> **Projection matrix for fog:** Like SSAO, the fog pipeline uses `mcProjectionMatrix * dhModelViewMatrix` for world position reconstruction. Initially `dhProjectionMatrix` was used (matching the GL path), but this caused all LODs to render as 100% fog because the depth values were produced with `mcProjectionMatrix`. The inverse matrix must always match the matrix that produced the depth buffer.

---

## Known Quirks & Platform Differences

> [!CAUTION]
> **NVIDIA: VulkanMod's `GPU_MEM` vertex buffers produce incorrect data in fullscreen quad geometry.**
> When using a 4-vertex quad with index buffer (`drawIndexed(vbo, ibo, 6)`), NVIDIA only rasterized one of the two triangles. The vertex buffer data was read incorrectly, likely caused by VulkanMod's VMA sub-allocation offsets or async staging buffer transfers.
>
> **Fix:** All fullscreen passes (composite, SSAO, fog) generate positions from `gl_VertexIndex` in the vertex shader, bypassing vertex buffer data entirely. A single oversized triangle at NDC `(-1,-1), (3,-1), (-1,3)` covers the entire viewport — the GPU clips the excess. This is also a Vulkan best practice (fewer vertices, no shared diagonal edge, no helper lane overhead).
>
> The `vPosition` input is declared but unused (required by VulkanMod's pipeline vertex format binding). A dummy 24-byte vertex buffer is still bound.

> [!WARNING]
> **Composite depth test must use `GL_ALWAYS` (519).**
> The composite runs before MC terrain, so MC's depth buffer contains `DONT_CARE` garbage. `GL_LEQUAL` fails against random values. MoltenVK implicitly clears to 1.0, hiding the bug on macOS.

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

## Phase 11: Multi-Version Support ✅ COMPLETE

**Goal:** Support MC 1.20.6 (VulkanMod 0.4.2) alongside MC 1.21.11 (VulkanMod 0.6.1) from one codebase.

### Key Differences Between VM 0.4.2 and VM 0.6.1

| Area | VM 0.6.1 (MC 1.21.11) | VM 0.4.2 (MC 1.20.6) |
|------|----------------------|----------------------|
| Buffer imports | `net.vulkanmod.vulkan.memory.buffer.*` | `net.vulkanmod.vulkan.memory.*` |
| IndexBuffer | Constructor takes `IndexType.UINT32` | No IndexType param |
| Drawer.drawIndexed | Supports UINT32 | Hardcodes UINT16 |
| Buffer.scheduleFree | `scheduleFree()` | `freeBuffer()` |
| Uniform suppliers | `Info.setBufferSupplier()` at construction | Only resolves built-in MC names; custom uniforms need manual wiring |
| VertexFormatElement | `new VFE(id, index, Type, Usage, count)` | `new VFE(index, Type, Usage, count)` |
| VertexFormat | `VertexFormat.builder().add().build()` | `new VertexFormat(ImmutableMap)` |
| SwapChain access | `Renderer.getInstance().getSwapChain()` | `Vulkan.getSwapChain()` |
| beginRenderPass | `Renderer.getInstance().beginRenderPass()` | Requires reflection to access `currentCmdBuffer` |
| GlTexture lightmap | `GlTexture` → `VkGlTexture` bridge exists | No GL texture bridge (lightmap unavailable) |
| Noise intensity config | Pre-scaled 0–1 float | Unscaled integer (needs `* 0.01`) |
| Mixin conflicts | None | DH's `MixinTextureUtil` conflicts with VM's `MTextureUtil` |
| GL context | Partial GL emulation | No GL context — raw GL calls crash |

### Implementation
- All version differences handled in `Compat.java` via `#if MC_VER >= MC_1_21_1` / `#else` Manifold directives
- `DhVulkanMixinPlugin` (IMixinConfigPlugin) strips conflicting DH mixins on MC 1.20.6
- `MixinLightMapWrapper` cancels raw GL calls that would crash in VM 0.4.2's Vulkan-only context
- `Compat.drawIndexed()` bypasses VM 0.4.2's Drawer to issue raw Vulkan commands with `VK_INDEX_TYPE_UINT32`
- `Compat.setUniformSuppliers()` manually wires DH's `MappedBuffer` suppliers into VM 0.4.2's `Uniform` subclasses

---

## Phase 10: Extension Mod Port ✅ COMPLETE

**Goal:** Port the Vulkan backend from a DH source fork to a standalone Fabric extension mod that works alongside unmodified DH releases.

### Key Design Decision: Clean Override vs Monkeypatch

The fork modified `LodBufferContainer.uploadBuffersDirect()` directly to add a Vulkan code path that bypasses `vbo.uploadBuffer()` and stores raw ByteBuffer data on the VBO. The initial extension mod approach used a low-level mixin on `GLBuffer.uploadBuffer()` to intercept data — this caused **missing east/west faces** because the interception was too deep in the call stack and version-dependent.

The fix: **`MixinLodBufferContainer`** intercepts `uploadBuffersDirect()` at HEAD, replicating the fork's approach. This grabs raw ByteBuffers from the quad builder *before* any GL operations, stores them via a duck interface, and cancels the entire GL upload path.

### Files Created/Modified
- **[NEW]** `MixinLodBufferContainer.java` — Clean buffer upload interception
- **[NEW]** `MixinLodRenderer.java` — Redirects rendering to VulkanRenderDelegate
- **[NEW]** `MixinLevelRenderer.java` — Composite trigger after MC terrain
- **[NEW]** `MixinGLProxy.java` — Dummy GLProxy, thread assertion bypass
- **[NEW]** `MixinGLBuffer.java` — GL call cancellation
- **[NEW]** `MixinGLVertexBuffer.java` — Duck interface (IVulkanVertexBuffer)
- **[NEW]** `DhVulkanModEntrypoint.java` — Fabric entrypoint
- **[NEW]** `DhVulkanConfig.java` — Mod config

### Lessons Learned
- Intercept data at the **highest stable level** (LodBufferContainer), not deep in the GL stack
- Duck interfaces (`IVulkanVertexBuffer`) are the clean way to add fields to DH classes via mixin
- DH's `GLProxy.queueRunningOnRenderThread()` works correctly even with a dummy GLProxy
- Terrain shaders (`standard.vert`, `flat_shaded.frag`) load from DH's jar at runtime — no need to bundle

---

## Key Files Reference

| File | Purpose |
|------|---------|
| `compat/Compat.java` | Single source of truth for all version-specific API differences |
| `VulkanRenderContext.java` | Pipeline, UBOs, shader conversion, draw API |
| `VulkanRenderDelegate.java` | Per-frame uniforms, VBO cache, draw dispatch, render pass switching |
| `DhVulkanFramebuffer.java` | DH-owned Vulkan framebuffer (color + depth) |
| `DhCompositePipeline.java` | Fullscreen triangle composite pipeline |
| `DhSsaoPipeline.java` | 2-pass SSAO post-process |
| `DhFogPipeline.java` | 2-pass fog post-process |
| `DhVulkanMixinPlugin.java` | IMixinConfigPlugin for mixin conflict resolution |
| `MixinLodBufferContainer.java` | Intercepts buffer upload, stores raw vertex data |
| `MixinLodRenderer.java` | Redirects LodRenderer to Vulkan delegate |
| `MixinLightMapWrapper.java` | Cancels raw GL calls in DH's lightmap code |
| `dh_apply.vert` / `dh_apply.frag` | Vulkan composite shaders |
| `VertexFormats.java` (DH) | DH's custom vertex format definition (16 bytes) |
| `PipelineState.java` (VulkanMod) | Render state (cull, blend, depth) |

## Vertex Format (16 bytes per vertex)

```
Offset  Size  GL Attr  Vulkan Format              Shader Input
0       8B    attr 0   R16G16B16A16_SINT          ivec4 vPosition (x,y,z + meta)
8       4B    attr 1   R8G8B8A8_UNORM             vec4 color (RGBA)
12      4B    attr 2   R32_SINT                   (material/normal/padding, unused by shader currently)
```

DH packs position (3 unsigned shorts) + light/micro-offset metadata into `vPosition.a`.
The shader casts `ivec4 → uvec4` for bitwise operations on the metadata.

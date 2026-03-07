# Distant Horizons → VulkanMod: Implementation Roadmap

Status as of 2026-03-07: LODs render with **correct colors, depth, lightmap, and transparency** ✅ Phases 1-5 complete. Next: framebuffer integration.

## Architecture Overview

Three new files under `fabric/src/main/java/com/seibel/distanthorizons/fabric/vulkan/`:
- **`VulkanRenderContext.java`** — Pipeline creation, UBO management, shader conversion, draw calls
- **`VulkanRenderDelegate.java`** — `IVulkanRenderDelegate` impl, per-frame uniform fill, VBO upload
- **`IVulkanRenderDelegate.java`** — Interface in core (at `core/render/renderer/`)

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

## Remaining Implementation Tasks

### Phase 2: Depth Integration ✅ COMPLETE
- [x] LODs render behind MC terrain via `polygonOffset(8.0f, 256.0f)` depth bias
- [x] Uses MC's projection matrix (not DH's) for compatible depth values
- [x] Lightmap: uses MC's framebuffer-rendered lightmap via VulkanMod's GL emulation layer

### Phase 3: Transparency / Blending ✅ COMPLETE
- [x] Added `setBlendState(boolean)` to `IVulkanRenderDelegate` — toggles `PipelineState.blendInfo`
- [x] Sets blend functions: `SRC_ALPHA/ONE_MINUS_SRC_ALPHA` (RGB), `ONE/ONE_MINUS_SRC_ALPHA` (alpha)
- [x] Re-binds pipeline after blend state change (VulkanMod bakes blend into pipeline objects)
- **Known limitation**: LOD/MC water overlap causes double-transparency at transition zone
  - Proper fix requires separate framebuffer + composite step (Phase 6)

### Phase 4: Buffer Management & Performance ✅ COMPLETE
- [x] VBO cache refactored with `CachedBuffer` (tracks Vulkan buffer + ByteBuffer identity)
- [x] Invalidation: detects when `vulkanBufferHandle` changes and frees old GPU buffer
- [x] Proper cleanup in `cleanup()` frees all cached Vulkan buffers
- [x] Vertex buffers use `GPU_MEM` (device-local VRAM) with automatic staging via VulkanMod

### Phase 5: State Management ✅ COMPLETE
- [x] Full save/restore in `beginFrame()`/`endFrame()`: `cull`, `depthMask`, `depthFun`, `topology`, `polygonMode`, `blendInfo` (all 6 fields)
- [x] Explicit `topology = TRIANGLE_LIST`, `polygonMode = FILL` set per frame

### Phase 6: Framebuffer / Render Pass Integration
- [ ] DH currently renders into MC's active render pass — verify this is correct
- [ ] If DH needs its own FBO (for SSAO, fog, etc.), create a Vulkan framebuffer
- [ ] Handle resolve/apply shader step (currently GL-only, skipped for Vulkan)
- [ ] Fog rendering (currently GL-only) — implement Vulkan fog pass or skip

### Phase 7: Shader Features
- [ ] Noise texture support — the fragment shader applies procedural noise
  - Currently uses UBO booleans (`uNoiseEnabled`, `uNoiseSteps`, etc.) — verify these work
- [ ] Earth curvature — vertex shader curves terrain based on `uEarthRadius`
  - Verify float precision with large world coordinates
- [ ] Wireframe debug rendering — needs `VK_POLYGON_MODE_LINE` pipeline variant
- [ ] SSAO — currently GL-only, would need a separate Vulkan compute/render pass
- [ ] Cloud rendering — DH renders vanilla-style clouds to LOD distance (GL-only, needs investigation)

### Phase 8: Iris/Shader Pack Compatibility — N/A
- VulkanMod does not support shader packs — this phase is blocked until it does
- DH's Iris integration (`IRIS_ACCESSOR`) is skipped for Vulkan path
- Custom shader overrides via `IDhApiShaderProgram` remain GL-only

### Phase 9: Edge Cases & Robustness
- [ ] Handle renderer reset (e.g., resource pack reload, window resize)
- [ ] Handle VulkanMod not being loaded (graceful fallback to GL)
- [ ] Thread safety: DH runs LOD generation on worker threads
  - Vulkan buffer uploads must happen on the render thread
- [ ] Config hot-reload: DH allows changing settings without restart

---

## Key Files Reference

| File | Purpose |
|------|---------|
| `LodRenderer.java` | Main render entry point, Vulkan path branching |
| `VulkanRenderContext.java` | Pipeline, UBOs, shader conversion, draw API |
| `VulkanRenderDelegate.java` | Per-frame uniforms, VBO cache, draw dispatch |
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

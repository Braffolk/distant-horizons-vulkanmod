# Distant Horizons → VulkanMod: Implementation Roadmap

Status as of 2026-03-06: LODs are **rendering with correct colors** ✅ Phase 1 complete. Next: depth, lightmap, transparency.

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

---

## Remaining Implementation Tasks

### Phase 2: Depth Integration (Current)
- [ ] Fix LODs rendering over MC terrain — add `vkCmdSetDepthBias` or adjust DH projection for Vulkan [0,1] depth range
- [ ] LODs should always be behind MC terrain (currently only at < 0.25 blocks)
- [ ] Handle LOD-only mode (no MC terrain) vs mixed mode

### Phase 3: Transparency / Blending
- [ ] Transparent LOD pass (water, glass) — needs alpha blending state
  - GL path: `GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA`
  - Vulkan: Set `PipelineState.blendInfo` before binding pipeline
- [ ] Deferred transparent rendering (`deferTransparentRendering` flag)
- [ ] Ensure transparent LODs render in correct order (back-to-front)

### Phase 4: Buffer Management & Performance
- [ ] VBO caching: current impl creates new `VertexBuffer` per VBO per frame
  - Need dirty-tracking: only re-upload when `vulkanBufferHandle` changes
  - Use `identityHashCode` as cache key (current approach) but add proper invalidation
- [ ] Memory management: schedule Vulkan buffer frees when DH VBOs are destroyed
  - Hook into `GLVertexBuffer.destroy()` or `LodBufferContainer` cleanup
- [ ] IBO optimization: current quad IBO is generated CPU-side, could be static
- [ ] Consider using `MemoryTypes.GPU_MEM` for vertex buffers (faster, requires staging)

### Phase 5: State Management
- [ ] Save/restore ALL VulkanMod state in beginFrame/endFrame:
  - `VRenderSystem.cull`, `depthTest`, `depthMask`, `depthFun`
  - `PipelineState.blendInfo`
  - `VRenderSystem.topology` (should be TRIANGLE_LIST)
  - `VRenderSystem.polygonMode` (for wireframe debug mode)
- [ ] Handle DH's GL state calls properly under VulkanMod:
  - `LodRenderer.setGLState()` is skipped — equivalent state must be set via VRenderSystem
  - Face culling per LOD direction (FRONT/BACK) if DH adds it back

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

### Phase 8: Iris/Shader Pack Compatibility
- [ ] DH has extensive Iris integration (`IRIS_ACCESSOR`) — all skipped for Vulkan
- [ ] If VulkanMod adds shader pack support, DH would need equivalent hooks
- [ ] Custom shader overrides via `IDhApiShaderProgram` — currently only works for GL

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

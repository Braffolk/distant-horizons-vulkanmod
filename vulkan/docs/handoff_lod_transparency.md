# Handoff: LOD vs MC Terrain Transparency / Depth Issue

## The Problem

LOD geometry from Distant Horizons renders **on top of** Minecraft's own terrain within vanilla render distance. This causes:

1. **Double-rendered transparent blocks** — Water, leaves, glass, underwater plants show both the MC version AND the LOD version stacked. Water appears darker/more opaque than it should. Leaves show white LOD outlines bleeding through.
2. **White outlines at block edges** — LOD fragments survive at subpixel boundaries where MC terrain depth doesn't perfectly cover them.
3. **LODs visible within vanilla render distance** — Even opaque LOD terrain sometimes shows through where it shouldn't.

The issue is most obvious at low render distances (e.g., 2 chunks) where LODs and MC terrain overlap heavily.

## Architecture Overview

This is a **Vulkan** port of the Distant Horizons mod running on **VulkanMod** (replaces MC's OpenGL with Vulkan). The mod targets **MC 1.21.11** only. There is no OpenGL path.

### Render Pipeline (Current State)

The render flow happens within `LevelRenderer.renderLevel()`:

```
1. LevelRenderer.renderLevel() HEAD
   └── sets mcProjectionMatrix, mcModelViewMatrix

2. LevelRenderer.prepareChunkRenders() HEAD  [MixinLevelRenderer.java:176-190]
   └── ClientApi.INSTANCE.renderLods()
       └── LodRenderer renders LODs into DH's own framebuffer (off-screen)
       └── VulkanRenderDelegate.endFrame():
           - Ends DH's render pass
           - Runs SSAO post-process (into DH framebuffer)
           - Runs Fog post-process (into DH framebuffer)
           - Restores VulkanMod render state
           - Calls rebindMainTarget() so MC can continue rendering
       └── Stores renderParams for deferred composite

3. VulkanMod renders MC terrain (opaque, translucent, tripwire)
   - This populates MC's depth buffer with actual terrain depth values
   - Transparent blocks (water, leaves) are rendered with alpha blending

4. LevelRenderer.renderLevel() RETURN  [MixinLevelRenderer.java:192-202]
   └── LodRenderer.INSTANCE.compositeVulkanFrame()
       └── VulkanRenderDelegate.deferredComposite():
           - rebindMainTarget() (starts auxiliary render pass, LOAD_OP_LOAD)
           - Runs composite pipeline (DhCompositePipeline)
           - Draws fullscreen quad with DH color+depth textures
           - Uses GL_LEQUAL depth test against MC's depth buffer
           - Writes gl_FragDepth = dhDepth + 0.0001 (small bias)
```

### Why the Deferred Composite Was Added

Originally, `endFrame()` did everything — including the composite. This meant DH composited onto MC's framebuffer during step 2, **before MC rendered any terrain**. MC's depth buffer was still cleared to 1.0, so every LOD fragment passed the depth test. Then:
- MC opaque terrain would overwrite LODs (via its own depth test)
- MC transparent terrain would **blend with** already-visible LODs → double transparency

The deferred composite (step 4) was added to delay compositing until after MC finishes all terrain rendering, so the depth test at composite time has MC's populated depth buffer. **This partially helps** — water no longer double-renders as badly — but leaves, underwater plants, and other transparent blocks still show through.

## Key Files

### Rendering Pipeline

| File | Role |
|------|------|
| [MixinLevelRenderer.java](../fabric/src/main/java/com/seibel/distanthorizons/fabric/mixins/client/MixinLevelRenderer.java) | Hooks into MC's render flow. HEAD of `renderLevel` sets matrices. HEAD of `prepareChunkRenders` calls `renderLods()`. RETURN of `renderLevel` calls `compositeVulkanFrame()`. |
| [MixinChunkSectionsToRender.java](../fabric/src/main/java/com/seibel/distanthorizons/fabric/mixins/client/MixinChunkSectionsToRender.java) | Hooks into `ChunkSectionsToRender.renderGroup()` for fade rendering. **NOTE: These hooks do NOT fire in VulkanMod** — VulkanMod bypasses vanilla chunk rendering. |
| [LodRenderer.java](../coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/render/renderer/LodRenderer.java) | Core LOD rendering orchestrator. Manages the Vulkan delegate. Has `compositeVulkanFrame()` method and stores `lastVulkanRenderParams`. |
| [IVulkanRenderDelegate.java](../coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/render/renderer/IVulkanRenderDelegate.java) | Interface for Vulkan rendering. Key methods: `beginFrame()`, `fillUniformData()`, `drawBuffer()`, `endFrame()`, `deferredComposite()`. |
| [VulkanRenderDelegate.java](../fabric/src/main/java/com/seibel/distanthorizons/fabric/vulkan/VulkanRenderDelegate.java) | Main Vulkan rendering implementation. Contains `beginFrame()`, `endFrame()`, `deferredComposite()`. |

### Shaders

| File | Role |
|------|------|
| [flat_shaded.frag](../coreSubProjects/core/src/main/resources/shaders/flat_shaded.frag) | GL 1.50 terrain fragment shader, **transpiled to Vulkan** by `VulkanRenderContext.convertToVulkan()`. Contains `uClipDistance` logic: `smoothstep` fade + hard `discard` within clip distance. |
| [standard.vert](../coreSubProjects/core/src/main/resources/shaders/standard.vert) | GL 1.50 terrain vertex shader, transpiled to Vulkan. Outputs `vertexWorldPos` (relative to camera) used for `viewDist = length(vertexWorldPos)`. |
| [dh_apply.frag](../coreSubProjects/core/src/main/resources/shaders/vulkan/dh_apply.frag) | Composite fragment shader. Samples DH color+depth, writes `gl_FragDepth`. Has debug visualization modes (0-5). |
| [dh_apply.vert](../coreSubProjects/core/src/main/resources/shaders/vulkan/dh_apply.vert) | Composite vertex shader. Simple fullscreen quad. |
| [dh_ssao.frag](../coreSubProjects/core/src/main/resources/shaders/vulkan/dh_ssao.frag) | SSAO pass. Uses `uInvProj` with `clipPos * 2.0 - 1.0` (correct for Vulkan — converts [0,1] to [-1,1] before applying OpenGL-convention inverse projection). |
| [dh_ssao_apply.frag](../coreSubProjects/core/src/main/resources/shaders/vulkan/dh_ssao_apply.frag) | SSAO blur+apply. Uses `(near*far)/(depth*(near-far)+far)` — correct Vulkan [0,1] linearization. |
| [dh_fog.frag](../coreSubProjects/core/src/main/resources/shaders/vulkan/dh_fog.frag) | Fog pass. Uses `ndc * 2.0 - 1.0` → `uInvMvmProj` (correct). |
| [dh_fog_apply.frag](../coreSubProjects/core/src/main/resources/shaders/vulkan/dh_fog_apply.frag) | Fog apply. Only checks `depth < 1.0` (no linearization needed). |

### Pipelines (Java)

| File | Role |
|------|------|
| [DhCompositePipeline.java](../fabric/src/main/java/com/seibel/distanthorizons/fabric/vulkan/DhCompositePipeline.java) | Composite pipeline. Draws fullscreen quad with DH color+depth textures. Sets `VRenderSystem.depthFun = 515` (GL_LEQUAL). UBO has `uInvProj` (mat4) + `uDebugMode` (int). Binds up to 4 textures (DH color, DH depth, SSAO, fog). |
| [DhSsaoPipeline.java](../fabric/src/main/java/com/seibel/distanthorizons/fabric/vulkan/DhSsaoPipeline.java) | 2-pass SSAO. Uses `RenderUtil.getNearClipPlaneInBlocks()` / `getFarClipPlaneDistanceInBlocks()` for near/far. |
| [DhFogPipeline.java](../fabric/src/main/java/com/seibel/distanthorizons/fabric/vulkan/DhFogPipeline.java) | Fog post-process pipeline. |
| [VulkanRenderContext.java](../fabric/src/main/java/com/seibel/distanthorizons/fabric/vulkan/VulkanRenderContext.java) | Manages terrain pipeline, UBO, shader transpilation. `convertToVulkan()` converts GL shaders to Vulkan (replaces uniforms with UBO, adds layout qualifiers). |

### Config & Utilities

| File | Role |
|------|------|
| [RenderUtil.java](../coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/util/RenderUtil.java) | `getNearClipPlaneInBlocks()` — calculates the LOD clip distance based on render distance × overdraw prevention %. At RD=2 with auto overdraw (0.2), this is roughly 5 blocks. |
| [Config.java](../coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/config/Config.java) | `Config.Client.Advanced.Debugging.vulkanDebugMode` (int 0-5) for debug visualizations. |

## LOD Clip Distance Mechanism

The terrain shader (`flat_shaded.frag`) has two clipping modes:

1. **Dithered fade** (when `uDitherDhRendering` is enabled): Uses 4×4 Bayer dithering. LODs between `uClipDistance` and `1.5×uClipDistance` fade out progressively. LODs closer than `uClipDistance` are discarded.

2. **Hard discard** (when dithering is disabled): LODs closer than `uClipDistance` are discarded entirely.

`uClipDistance` is set from `RenderUtil.getNearClipPlaneInBlocks()` which returns `renderDistance × 16 × overdrawPercent / fovFactor`. At render distance 2 with auto overdraw (20%), this is approximately 5 blocks. Beyond this distance, LODs are NOT clipped by the terrain shader — the composite depth test is supposed to handle them.

## Projection Matrix Usage

A critical design decision: LODs are rendered using **MC's projection matrix** (`mcProjectionMatrix`), NOT DH's own projection matrix (`dhProjectionMatrix`). This is set at `VulkanRenderDelegate.fillUniformData()` line ~250:
```java
Mat4f combinedMatrix = new Mat4f(renderParameters.mcProjectionMatrix);
combinedMatrix.multiply(renderParameters.dhModelViewMatrix);
```
This ensures DH depth values are in the same coordinate space as MC's depth buffer, making hardware depth testing valid.

DH's projection matrix has a much larger far plane (to render very distant LODs). If it were used, the same world position would map to a SMALLER depth value in DH vs MC, causing LODs to always "win" the depth test.

## Depth Convention

All shaders correctly handle the Vulkan [0,1] depth range (not OpenGL's [-1,1]). Where shaders reconstruct positions from depth, they convert `clipPos * 2.0 - 1.0` before applying the inverse projection (which is in OpenGL convention from MC). The `linearizeDepth()` functions use the Vulkan formula: `(near*far)/(depth*(near-far)+far)`.

## VulkanMod Quirks

- **`rebindMainTarget()`** — Required after any DH off-screen rendering. Starts an auxiliary render pass with `LOAD_OP_LOAD` (preserves existing framebuffer content). Without this call, MC has no active render pass and can't render.
- **Render state save/restore** — `VulkanRenderDelegate.beginFrame()` saves VulkanMod global state (`VRenderSystem.cull`, `depthMask`, `depthFun`, `topology`, `polygonMode`, blend state). `endFrame()` restores them.
- **`MixinChunkSectionsToRender` hooks don't fire** — VulkanMod bypasses `ChunkSectionsToRender.renderGroup()`. Only `MixinLevelRenderer` hooks (`renderLevel`, `prepareChunkRenders`) are reliable.
- **Preprocessor directives** — The codebase uses `#if MC_VER < MC_1_21_9` etc. for multi-version support. Only the `#else` branches (1.21.9+) apply to this fork. The IDE doesn't understand these and shows false lint errors.
- **`#endif` corruption** — Editing files with `#endif` using the AI code editing tools can corrupt the directive (splits `#` and `endif` onto separate lines with a bare newline between). Always verify with `tail` or `xxd` after editing these files, and fix with `perl -i -0pe 's/#\nendif/#endif/g'` if needed.

## What Has Been Tried

### Attempt 1: Deferred Composite via MixinChunkSectionsToRender
Moved composite from `endFrame()` to a new `deferredComposite()` called at `renderGroup(TRIPWIRE)`. **Failed** — `renderGroup()` never fires in VulkanMod.

### Attempt 2: Deferred Composite via MixinLevelRenderer RETURN
Moved composite hook to `@Inject(at = @At("RETURN"), method = "renderLevel")`. **Partially works** — water double-rendering improved slightly, but leaves/underwater plants still double-render.

### Why It's Still Not Fully Working

The RETURN injection on `renderLevel` fires at the very end of the method. The question is whether MC's transparent terrain has actually been rendered and committed to the depth buffer by this point in VulkanMod's pipeline. Possible issues:

- VulkanMod may render transparent blocks in a way that doesn't write to the depth buffer (common for transparent rendering — only opaque writes depth)
- The composite pass's `rebindMainTarget()` may start a NEW render pass that doesn't see the depth from the previous render pass
- The depth test in the composite pipeline may be misconfigured for Vulkan
- The `endFrame()` call to `rebindMainTarget()` may have already ended MC's render pass, meaning MC terrain renders in an auxiliary pass whose depth isn't carried forward

## Relevant VulkanMod Classes

- `net.vulkanmod.render.Renderer` — Main VulkanMod renderer. `endRenderPass()`, `getMainPass()`.
- `net.vulkanmod.render.passes.DefaultMainPass` — MC's main render pass. `rebindMainTarget()` starts auxiliary render pass with `LOAD_OP_LOAD`.
- `net.vulkanmod.render.VRenderSystem` — Global render state (cull, depthMask, depthFun, topology, etc.).
- `net.vulkanmod.render.PipelineState` — Pipeline state including blend info.
- `net.vulkanmod.vulkan.texture.VulkanImage` — Vulkan texture wrapper.

## Debug Visualization System

Config entry `vulkanDebugMode` (0-5) in `Config.Client.Advanced.Debugging`:
- 0: Off (normal rendering)
- 1: DH depth (linearized, black=far, white=near, 0-2000 block range)
- 2: SSAO buffer (grayscale occlusion)
- 3: Fog alpha (grayscale)
- 4: Fog color (RGB)
- 5: Reconstructed normals (RGB from view-space normals)

These render in `dh_apply.frag` using `uInvProj` (MC's inverse projection matrix) for depth/normal reconstruction.

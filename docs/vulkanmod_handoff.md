# DH → VulkanMod Port: Handoff Document

> **Status**: Game loads and world is joinable. No LOD rendering yet (draw calls are placeholder no-ops).
> **Target**: Minecraft 1.21.11, Fabric, VulkanMod, native Vulkan (no GL shim).

---

## Table of Contents
1. [Background & Design Philosophy](#background--design-philosophy)
2. [What's Been Done (Phase 0–1)](#whats-been-done-phase-01)
3. [Architecture Overview](#architecture-overview)
4. [Files Created & Modified](#files-created--modified)
5. [Critical Design Decisions & Why](#critical-design-decisions--why)
6. [What Needs to Happen Next](#what-needs-to-happen-next)
7. [VulkanMod API Reference (Key Classes)](#vulkanmod-api-reference-key-classes)
8. [DH Rendering Pipeline (How Vertex Data Flows)](#dh-rendering-pipeline-how-vertex-data-flows)
9. [Known Issues & Gotchas](#known-issues--gotchas)

---

## Background & Design Philosophy

### Why VulkanMod?
VulkanMod replaces Minecraft's OpenGL renderer with Vulkan. Distant Horizons (DH) uses raw OpenGL calls for everything — shaders, vertex buffers, framebuffers, post-processing. **Every single GL call will crash** under VulkanMod because there is no GL context.

### Why Native Vulkan (Not GL Shim)?
VulkanMod has a partial GL compatibility layer, but it's incomplete and designed for MC's limited GL usage, not DH's heavy custom rendering. We chose **native Vulkan integration** — meaning we call VulkanMod's Vulkan wrapper APIs directly. This gives us:
- Full control over pipeline state
- Proper resource management
- No workarounds for incomplete GL emulation
- Better performance (no GL→Vulkan translation overhead)

### Why Two-Module Architecture?
DH has a multi-module Gradle structure:
- `core` — platform-independent rendering logic, **cannot** import VulkanMod classes
- `fabric` — Fabric-specific code, **has** VulkanMod on its classpath

This means VulkanMod-dependent code MUST live in `fabric`. Core rendering code (like `LodRenderer`) can only talk to Vulkan through an abstraction interface.

### Why CPU-Driven Rendering?
VulkanMod **does not expose compute pipeline support**. This means:
- No compute shaders for GPU-driven culling or indirect draw
- All draw commands must be issued per-VBO from CPU
- This matches DH's existing GL flow (iterate VBOs, bind, draw)

### Why Server-Side Must Stay Untouched?
DH supports multiplayer: a server with DH installed sends LOD chunk data to clients. This path has **zero rendering code** — it's pure data. All our changes are guarded to only affect client-side rendering. Server-side chunk generation, networking, and data serialization are completely untouched.

---

## What's Been Done (Phase 0–1)

### Phase 0: Build Cleanup ✅
- Removed all Forge/NeoForge build targets
- Removed Sodium/Iris/Optifine compatibility code and mod accessor interfaces
- Added VulkanMod JAR as `modCompileOnly` in `fabric/build.gradle`
- Set target to `mcVer=1.21.11`

### Phase 1: Core Rendering Pipeline ✅ (Wiring Only — No Actual Drawing)

1. **`GLProxy` detection** — `isVulkanModActive()` checks for VulkanMod class at runtime
2. **`GLState` no-ops** — `saveState()`/`close()` skip GL calls when VulkanMod is active
3. **`VulkanRenderContext`** (fabric) — central Vulkan integration:
   - GLSL 1.50 → GLSL 4.50 → SPIR-V shader compilation
   - Uniform-to-UBO conversion (individual uniforms → std140 layout UBO block)
   - VulkanMod `GraphicsPipeline` creation
   - Factory methods for `VertexBuffer` and `IndexBuffer`
   - Pipeline binding via `Renderer.getInstance().bindGraphicsPipeline()`
4. **`IVulkanRenderDelegate`** (core) — interface to cross the module boundary:
   - `init()`, `beginFrame()`, `drawBuffer(GLVertexBuffer, indexCount)`, `endFrame()`, `cleanup()`
   - Also `uploadVertexData(ByteBuffer, vertexCount)` for future use
5. **`VulkanRenderDelegate`** (fabric) — implements the interface:
   - Lazy init on first `beginFrame()` (deferred from mod init because VulkanMod's Renderer isn't ready at entrypoint time)
   - Try-catch with `initFailed` flag to prevent crashes
   - Creates quad index buffer (65536 quads × 6 indices)
   - `drawBuffer()` is currently a **no-op placeholder**
6. **`LodRenderer` guards** — comprehensive `if (!useVulkan)` guards on:
   - `createRenderObjects()` — skips `DhTerrainShaderProgram`, `QuadElementBuffer`, `DhFramebuffer`, texture creation
   - `setGLState()` — skipped entirely, delegate `beginFrame()` called instead
   - `lightmap.bind()/unbind()` — skipped
   - `quadIBO.bind()/unbind()` — skipped
   - `shaderProgram.bind()/unbind()` — null-guarded
   - Inner `renderLodPass()` — polygon mode, blend, cull state skipped
   - All post-processing — SSAO, fog, fade, apply, debug wireframe, Optifine cleanup skipped
   - Draw loop — branches to `vulkanDelegate.drawBuffer(vbo, count)` instead of `glDrawElements`
7. **`FabricClientProxy`** — creates `VulkanRenderDelegate` at startup, registers with `LodRenderer.INSTANCE`

### Build Output
```
fabric/build/libs/DistantHorizons-fabric-2.4.6-b-dev-1.21.11.jar  (25 MB)
```
Built with: `./gradlew :fabric:build -PmcVer="1.21.11"`

### Test Results
- ✅ Game loads
- ✅ World joinable
- ✅ No crashes
- ✅ Log shows `[DH-VulkanMod] VulkanMod detected`
- ⚠️ No visible LODs (draw calls are no-ops)
- ⚠️ DH shows "renderer has encountered an exception" once (shaderProgram NPE at startup, then guarded — harmless)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│  FabricClientProxy.registerEvents()                     │
│  ├─ if VulkanMod detected:                              │
│  │   └─ new VulkanRenderDelegate() → LodRenderer.INSTANCE│
│  └─ registers WorldRenderEvents.AFTER_SETUP callback    │
│       └─ clientApi.renderLods()                         │
│            └─ LodRenderer.render(...)                   │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│  LodRenderer.renderLodPass(...)  [core module]          │
│  ├─ useVulkan = GLProxy.isVulkanModActive()             │
│  ├─ if (useVulkan) skip: setGLState, lightmap, IBO      │
│  ├─ if (useVulkan) call: vulkanDelegate.beginFrame()    │
│  │                                                       │
│  ├─ for each LodBufferContainer:                         │
│  │   for each GLVertexBuffer vbo:                        │
│  │     if (useVulkan):                                   │
│  │       vulkanDelegate.drawBuffer(vbo, count) ← NO-OP  │
│  │     else:                                             │
│  │       vbo.bind(); glDrawElements(...)                 │
│  │                                                       │
│  ├─ if (useVulkan) skip: SSAO, fog, fade, apply          │
│  └─ if (useVulkan) call: vulkanDelegate.endFrame()      │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│  VulkanRenderDelegate  [fabric module]                   │
│  ├─ beginFrame(): lazy init + bindTerrainPipeline()     │
│  ├─ drawBuffer(vbo, count): ★ NO-OP — THIS IS THE GAP  │
│  └─ endFrame(): nothing                                  │
│                                                           │
│  VulkanRenderContext  [fabric module]                     │
│  ├─ createTerrainPipeline(): SPIR-V + UBOs → Pipeline   │
│  ├─ bindTerrainPipeline(): Renderer.bindGraphicsPipeline│
│  ├─ createVertexBuffer(size): new VertexBuffer(DEVICE)  │
│  ├─ createIndexBuffer(size): new IndexBuffer(DEVICE)    │
│  └─ drawIndexed(vb, ib, count): Drawer.drawIndexed()   │
└─────────────────────────────────────────────────────────┘
```

---

## Files Created & Modified

### Created (3 new files)
| File | Module | Path |
|------|--------|------|
| `VulkanRenderContext.java` | fabric | `fabric/src/main/java/com/seibel/distanthorizons/fabric/vulkan/` |
| `VulkanRenderDelegate.java` | fabric | `fabric/src/main/java/com/seibel/distanthorizons/fabric/vulkan/` |
| `IVulkanRenderDelegate.java` | core | `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/render/renderer/` |

### Modified (4 files)
| File | Key Changes |
|------|-------------|
| `GLProxy.java` | Added `isVulkanModActive()` static method, guard in constructor |
| `GLState.java` | `saveState()`/`close()` are no-ops under VulkanMod |
| `LodRenderer.java` | ~15 guard blocks added, delegate field/setter, draw loop branching |
| `FabricClientProxy.java` | VulkanRenderDelegate creation and registration |

### Build Files Modified
| File | Change |
|------|--------|
| `gradle.properties` | `mcVer=1.21.11` |
| `versionProperties/1.21.11.properties` | Updated mod versions |
| `fabric/build.gradle` | VulkanMod JAR as `modCompileOnly`, removed Sodium |

---

## Critical Design Decisions & Why

### 1. VulkanMod Uniform Type Vocabulary
VulkanMod's `Uniform.createUniformInfo(type, name, count)` does NOT accept GLSL type names.

| GLSL Type | VulkanMod `type` | VulkanMod `count` |
|-----------|------------------|-------------------|
| `mat4`    | `"matrix4x4"`    | `1`               |
| `vec4`    | `"float"`        | `4`               |
| `vec3`    | `"float"`        | `3`               |
| `vec2`    | `"float"`        | `2`               |
| `float`   | `"float"`        | `1`               |
| `ivec4`   | `"int"`          | `4`               |
| `int`     | `"int"`          | `1`               |

**Lesson learned**: This crashed the game on first test. Always check `Uniform.createUniformInfo()` source.

### 2. Deferred Initialization
Pipeline creation CANNOT happen during `registerEvents()` (Fabric entrypoint stage). VulkanMod's `Renderer` and Vulkan device aren't initialized yet. We defer to first `beginFrame()` call during actual rendering.

### 3. Manifold Preprocessor
DH uses the Manifold Java preprocessor for multi-version support (`#if MC_VER < MC_1_21_9`). The IDE shows hundreds of "syntax errors" in files with preprocessor directives — **these are all false positives**. The actual Gradle build processes them correctly.

### 4. DH Vertex Format
DH uses a custom vertex format defined in `LodUtil.DH_VERTEX_FORMAT`. Each vertex has a specific byte layout that the terrain shader expects. The vertex format must match both:
- The VBO data uploaded by `LodBufferContainer`
- The pipeline's vertex input description in `GraphicsPipeline`

### 5. Guard Pattern (Not Replace)
We chose to GUARD GL calls with `if (!useVulkan)` rather than REPLACE them. This keeps the GL path functional for non-VulkanMod users and makes the diff reviewable. The Vulkan path runs alongside via the delegate pattern.

---

## What Needs to Happen Next

### Priority 1: Make LODs Visible (The "First Pixel" Milestone)

The critical gap is `VulkanRenderDelegate.drawBuffer()`. Currently it receives a `GLVertexBuffer` but can't do anything with it because the vertex data is in GL memory, not Vulkan memory.

#### Step 1: Understand DH's Vertex Data Flow
```
LodQuadBuilder (CPU) 
  → ArrayList<ByteBuffer> (raw vertex data)
    → LodBufferContainer.uploadBuffersDirect()
      → GLVertexBuffer.uploadBuffer(ByteBuffer, count, method)
        → glBufferData() / glBufferSubData()  ← THIS IS THE GL CALL
```

The `ByteBuffer` contains raw vertex data in `LodUtil.DH_VERTEX_FORMAT` layout.
The key intercept point is `uploadBuffersDirect()` — under VulkanMod, instead of creating a `GLVertexBuffer` and calling `glBufferData()`, we need to create a VulkanMod `VertexBuffer` and call `copyBuffer()`.

#### Step 2: Create a Vulkan-Aware Buffer Container
Options:
1. **Modify `GLVertexBuffer`** to hold either a GL buffer ID or a VulkanMod `Buffer` reference (via the `IVulkanRenderDelegate` for buffer creation)
2. **Create a parallel `VulkanVertexBuffer`** class and modify `LodBufferContainer` to use it when VulkanMod is active
3. **Store ByteBuffers directly** and upload them to VulkanMod buffers at draw time (simplest, but wasteful)

Recommended: **Option 2** — create `VulkanVertexBuffer` in `fabric` module, with an interface in `core` so `LodBufferContainer` can use it polymorphically.

#### Step 3: Implement the Draw Call
Once vertex data is in a VulkanMod `Buffer`:
```java
// In VulkanRenderDelegate.drawBuffer():
Drawer drawer = Renderer.getDrawer();
drawer.drawIndexed(vulkanVertexBuffer, this.quadIndexBuffer, indexCount);
```

Key: `Drawer.drawIndexed(Buffer vertexBuffer, IndexBuffer indexBuffer, int indexCount)` — this is THE draw call.

#### Step 4: Verify Vertex Format Compatibility
DH's `DH_VERTEX_FORMAT` must match the pipeline's vertex input description. Check:
- `GraphicsPipeline.getAttributeDescriptions()` expects a `VertexFormat`
- Need to create a `VertexFormat` that matches DH's vertex layout
- DH vertices include: position (3 floats), color (4 bytes), lightmap (2 shorts), possibly more

### Priority 2: Uniform Upload
The terrain shader needs per-frame uniform data (camera matrix, model offset, etc.). Currently `DhTerrainShaderProgram` handles this via GL uniform calls. Need to:
1. Get the `UBO` from `VulkanRenderContext`
2. Write uniform data to the UBO's ByteBuffer each frame
3. The pipeline binding handles the rest

### Priority 3: Framebuffer / Render Target Integration
DH renders to its own framebuffer, then copies to MC's framebuffer via `DhApplyShader`. Under VulkanMod:
- Option A: Render directly into VulkanMod's active render pass (ideal)
- Option B: Create a separate Vulkan render pass + framebuffer, then blit

This is complex and may require studying how VulkanMod structures its render passes.

### Priority 4: Post-Processing
SSAO, fog, fade, apply shaders — each is a full-screen pass that reads/writes textures. These will need individual Vulkan pipelines. This can be deferred — get basic LODs visible first.

---

## VulkanMod API Reference (Key Classes)

### `Renderer` (singleton)
```java
Renderer.getInstance()                     // get the renderer
renderer.bindGraphicsPipeline(pipeline)     // set active pipeline
renderer.getDrawer()                        // get the Drawer for draw calls
```

### `Drawer`
```java
drawer.drawIndexed(Buffer vb, IndexBuffer ib, int indexCount)  // THE draw call
drawer.draw(ByteBuffer vertexData, Mode, VertexFormat, int vertexCount)  // immediate mode
drawer.getQuadsIndexBuffer()               // built-in quad IBO (maybe reusable!)
drawer.getUniformBuffer()                  // per-frame UBO
```

### `Buffer` / `VertexBuffer` / `IndexBuffer`
```java
// Creation:
new VertexBuffer(size, MemoryType.DEVICE_LOCAL)  // GPU-only
new IndexBuffer(size, MemoryType.HOST_LOCAL)     // or DEVICE_LOCAL

// Upload:
buffer.copyBuffer(ByteBuffer data, int size)

// Cleanup:
buffer.scheduleFree()  // queues for deallocation
```

### `GraphicsPipeline`
Created from `Pipeline.Builder` with SPIR-V shaders, UBOs, samplers, and vertex format.

### `Uniform.createUniformInfo(type, name, count)`
Types: `"matrix4x4"`, `"float"` (with count 1-4), `"int"` (with count 1-4).

---

## DH Rendering Pipeline (How Vertex Data Flows)

```
World chunks → DH LOD generation (server-compatible)
                    │
                    ▼
            LodQuadBuilder  ← builds vertex data on DH thread
                    │
                    ▼
          ArrayList<ByteBuffer>  ← raw vertex bytes in DH_VERTEX_FORMAT
                    │
                    ▼
        LodBufferContainer.makeAndUploadBuffersAsync()
                    │
                    ├─ uploadBuffersDirect()
                    │     └─ GLVertexBuffer.uploadBuffer(ByteBuffer, ...) ← GL PATH
                    │            └─ glBufferData()
                    │
                    └─ [FUTURE: VulkanMod path]
                          └─ VulkanVertexBuffer.upload(ByteBuffer)
                                 └─ buffer.copyBuffer()
                    │
                    ▼
        LodRenderer.renderLodPass()
                    │
                    ├─ for each LodBufferContainer → for each VBO:
                    │     GL: vbo.bind() + glDrawElements()
                    │     VK: delegate.drawBuffer() → Drawer.drawIndexed()
                    │
                    ▼
              Screen pixels (or framebuffer for apply shader)
```

### DH Vertex Format (`LodUtil.DH_VERTEX_FORMAT`)
This is the layout of each vertex in the ByteBuffers. You MUST study this to create the correct `VertexFormat` for the Vulkan pipeline's vertex input description. Look at:
- `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/util/LodUtil.java` — `DH_VERTEX_FORMAT` 
- `coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/dataObjects/render/bufferBuilding/ColumnRenderBufferBuilder.java` — how vertices are written

---

## Known Issues & Gotchas

1. **IDE Lint Noise**: Files with Manifold preprocessor (`#if MC_VER < ...`) show ~50 "syntax errors" in the IDE. These are 100% false positives. Trust the Gradle build, not the IDE.

2. **`LodRenderer.INSTANCE`**: The renderer is a singleton. There's only ever one, and it exists for the lifetime of the client session.

3. **Upload Thread vs Render Thread**: `LodBufferContainer.uploadBuffersDirect()` runs on a DH upload thread, not the render thread. VulkanMod buffer creation may have threading constraints — check if `Buffer.copyBuffer()` needs to be on the render thread.

4. **Drawer's built-in quad index buffer**: `Drawer.getQuadsIndexBuffer()` returns a pre-built quad IBO. This might be reusable instead of creating our own in `VulkanRenderDelegate.init()`. Worth investigating.

5. **Pipeline State**: VulkanMod's `GraphicsPipeline.getHandle(PipelineState state)` creates pipelines lazily based on state (blend, cull, depth). The first draw call with a new state will compile a new pipeline variant — expect a brief stutter.

6. **VulkanRenderContext.init() may still fail**: The GLSL→SPIR-V compilation and pipeline creation haven't been tested at runtime yet. The try-catch in `VulkanRenderDelegate` will catch failures, but the log should be checked. If it fails, the init error will tell you exactly what went wrong (usually shader compilation or vertex format mismatch).

7. **`renderObjectsCreated` flag**: `LodRenderer.createRenderObjects()` returns `true` under VulkanMod without setting `renderObjectsCreated = true`. This might cause it to be called multiple times. Check if this is an issue.

---

## VulkanMod Source Files (Included in Repo)

VulkanMod's **full source code** is included in this repo at:
```
vulkanmod/src/main/java/net/vulkanmod/
```

### Key Files to Study

#### Rendering Core
| File | What It Does |
|------|-------------|
| `vulkan/Renderer.java` | Singleton renderer — pipeline binding, frame management |
| `vulkan/Drawer.java` | All draw calls — `drawIndexed()`, `draw()`, index buffer management |
| `vulkan/Vulkan.java` | Device, queue, swap chain setup |

#### Shader & Pipeline
| File | What It Does |
|------|-------------|
| `vulkan/shader/GraphicsPipeline.java` | Pipeline creation, vertex input descriptions, shader modules |
| `vulkan/shader/Pipeline.java` | Base pipeline class — UBO/sampler/push constant management |
| `vulkan/shader/SPIRVUtils.java` | GLSL → SPIR-V compilation via shaderc |
| `vulkan/shader/layout/Uniform.java` | Uniform type handling (`"matrix4x4"`, `"float"`, `"int"`) |
| `vulkan/shader/layout/AlignedStruct.java` | UBO layout builder (std140) |
| `vulkan/shader/layout/UBO.java` | Uniform Buffer Object |

#### Memory & Buffers
| File | What It Does |
|------|-------------|
| `vulkan/memory/buffer/Buffer.java` | Base buffer — `copyBuffer()`, `scheduleFree()`, `resizeBuffer()` |
| `vulkan/memory/buffer/VertexBuffer.java` | Vertex buffer subclass |
| `vulkan/memory/buffer/IndexBuffer.java` | Index buffer with index type |
| `vulkan/memory/MemoryManager.java` | VMA-based allocation, freeable queue |
| `vulkan/memory/MemoryType.java` | `DEVICE_LOCAL`, `HOST_LOCAL`, etc. |
| `vulkan/util/MappedBuffer.java` | ByteBuffer wrapper for mapped memory |

#### Pipeline State
| File | What It Does |
|------|-------------|
| `vulkan/shader/PipelineState.java` | Blend, depth, cull state for pipeline variants |

#### How VulkanMod Renders Terrain (Reference for Understanding)
| File | What It Does |
|------|-------------|
| `render/chunk/WorldRenderer.java` | MC world rendering orchestration |
| `render/chunk/build/RenderSection.java` | Per-chunk render data |
| `render/vertex/TerrainVertex.java` | MC terrain vertex format |

### Browsing Tips
- Grep for `drawIndexed` to see how VulkanMod itself issues draw calls
- Grep for `GraphicsPipeline` to see how pipelines are created and bound
- The `render/` directory shows how VulkanMod renders MC's terrain — useful as a reference pattern

---

## Quick Start for Next Session

```bash
# Build & test
./gradlew :fabric:build -PmcVer="1.21.11"
# JAR at: fabric/build/libs/DistantHorizons-fabric-2.4.6-b-dev-1.21.11.jar

# Key files to read first:
# 1. This file (you're reading it)
# 2. LodBufferContainer.java — understand vertex upload pipeline
# 3. GLVertexBuffer.java — what needs a Vulkan equivalent
# 4. VulkanRenderDelegate.java — where drawBuffer() needs to be implemented
# 5. Drawer.java — VulkanMod's draw API
# 6. LodUtil.java — DH_VERTEX_FORMAT definition
```

**The single most impactful change**: Implement `VulkanRenderDelegate.drawBuffer()` with real vertex data. That's the "first pixel on screen" moment.

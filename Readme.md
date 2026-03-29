# Distant Horizons — VulkanMod Extension

A Fabric extension mod that adds native **Vulkan rendering** to [Distant Horizons](https://gitlab.com/distant-horizons-team/distant-horizons) via [VulkanMod](https://github.com/xCollateral/VulkanMod).

LODs are rendered using VulkanMod's Vulkan pipeline instead of OpenGL, enabling Distant Horizons to work on systems and configurations running VulkanMod.

> **This is not a standalone mod.** You must have both Distant Horizons and VulkanMod installed. Versions v2.4.6+vm.3 and earlier were standalone forks of DH; Newer versions are a separate extension mod that runs alongside unmodified DH. Tested for Minecraft versions 1.20.6 and 1.21.11.

![Distant Horizons running on VulkanMod](docs/dh-vulkanmod.jpg)
*Distant Horizons LODs rendered via VulkanMod's Vulkan backend*

## Status

### ✅ Working
- LOD terrain rendering with correct colors and vertex format
- Lightmap support (day/night cycle, block light)
- Depth integration (LODs render behind MC terrain)
- Transparency / alpha blending (water, glass, etc.)
- Ambient occlusion (SSAO)
- Distance and height fog (all falloff types and mixing modes)
- Noise / dithering on LODs
- Fade / clip distance transitions
- Earth curvature rendering
- Cloud rendering with correct depth against LOD terrain (VM 0.6+)
- Weather effects (rain, snow) render correctly in front of LODs

### ⚠️ Not Yet Implemented
- **Shader pack support** — VulkanMod does not support shader packs (Iris/OptiFine)
- **Wireframe debug mode**

See [docs/vulkan_implementation_roadmap.md](docs/vulkan_implementation_roadmap.md) for the full technical roadmap.

## Requirements

- **Minecraft:** 1.20.6 or 1.21.11
- **Mod loader:** Fabric
- **VulkanMod** must be installed (0.4.2 for MC 1.20.6, 0.6.2 for MC 1.21.11)
- **Distant Horizons** 2.4.0+ or 3.0.0+ must be installed

> This is not the official Distant Horizons mod. For the original, see the [GitLab](https://gitlab.com/distant-horizons-team/distant-horizons) or [CurseForge](https://www.curseforge.com/minecraft/mc-mods/distant-horizons) pages.

## Building

```bash
# MC 1.21.11
./gradlew :vulkan:build -PmcVer="1.21.11"

# MC 1.20.6
./gradlew :vulkan:build -PmcVer="1.20.6"
```

The compiled jar will be in `vulkan/build/libs/`.

## Source Code Setup

### Prerequisites

* JDK 21 or newer — https://www.oracle.com/java/technologies/downloads/
* Git — https://git-scm.com/

### IntelliJ IDEA
1. Install the Manifold plugin
2. Open IDEA and import the `build.gradle`
3. Refresh the Gradle project if required

### Other commands

```bash
./gradlew --refresh-dependencies   # refresh dependencies
./gradlew genSources               # generate MC source for browsing
```

> Source code uses Mojang mappings & [Parchment](https://parchmentmc.org/) mappings.

## Open Source Acknowledgements

- [Forgix](https://github.com/PacifistMC/Forgix) — jar merging
- [LZ4 for Java](https://github.com/lz4/lz4-java) — data compression
- [NightConfig](https://github.com/TheElectronWill/night-config) — JSON & TOML config handling
- [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) — SQLite driver

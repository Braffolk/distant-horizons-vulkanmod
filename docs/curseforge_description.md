# Distant Horizons — VulkanMod Port

A modified build of [Distant Horizons](https://www.curseforge.com/minecraft/mc-mods/distant-horizons) ([Modrinth](https://modrinth.com/mod/distanthorizons)) that works with [VulkanMod](https://github.com/xCollateral/VulkanMod). Renders LOD terrain through VulkanMod's Vulkan backend instead of OpenGL.

**What works:**
- LOD terrain with correct colors
- Lightmap (day/night lighting, block light)
- Depth (LODs render behind normal terrain)
- Water and glass transparency

**What doesn't work yet:**
- Noise/dithering on LODs
- Ambient occlusion (SSAO)
- Fog
- Shader packs (VulkanMod doesn't support them)
- Some DH rendering settings that depend on OpenGL

**Important:**
- Requires VulkanMod
- Fabric only
- MC 1.21.11
- This is not the official Distant Horizons mod. For the original, see the links above.

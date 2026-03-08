# Distant Horizons — VulkanMod Port

A modified build of [Distant Horizons](https://www.curseforge.com/minecraft/mc-mods/distant-horizons) ([Modrinth](https://modrinth.com/mod/distanthorizons)) that works with [VulkanMod](https://github.com/xCollateral/VulkanMod). Renders LOD terrain through VulkanMod's Vulkan backend instead of OpenGL.

**What works:**
- LOD terrain rendering with correct colors
- Lightmap (day/night lighting, block light)
- Depth compositing (LODs render behind normal terrain)
- Water and glass transparency
- Ambient occlusion (SSAO)
- Distance and height fog (all falloff types and mixing modes)
- Noise/dithering on LODs
- Fade/clip distance transitions

**What doesn't work yet:**
- Shader packs (VulkanMod doesn't support them)
- Earth curvature rendering
- Wireframe debug mode
- Cloud rendering to LOD distance

**Important:**
- Requires VulkanMod
- Fabric only
- MC 1.21.11
- This is not the official Distant Horizons mod. For the original, see the links above.

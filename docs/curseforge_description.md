# Distant Horizons — VulkanMod Extension

![alt text](https://github.com/Braffolk/distant-horizons-vulkanmod/raw/main/docs/dh-vulkanmod.jpg)

An extension mod that makes [Distant Horizons](https://www.curseforge.com/minecraft/mc-mods/distant-horizons) ([Modrinth](https://modrinth.com/mod/distanthorizons)) work with [VulkanMod](https://github.com/xCollateral/VulkanMod). Renders LOD terrain through VulkanMod's Vulkan backend instead of OpenGL.

**What works:**
- LOD terrain rendering with correct colors
- Lightmap (day/night lighting, block light)
- Depth compositing (LODs render behind normal terrain)
- Water and glass transparency
- Ambient occlusion (SSAO)
- Distance and height fog (all falloff types and mixing modes)
- Noise/dithering on LODs
- Fade/clip distance transitions
- Earth curvature

**What doesn't work yet:**
- Shader packs (VulkanMod doesn't support them)
- Wireframe debug mode
- Cloud rendering to LOD distance

**Requirements:**
- Fabric
- MC 1.20.6 or 1.21.11
- VulkanMod must be installed
- Distant Horizons 2.4.5+ must be installed

> This is not a standalone mod. Both Distant Horizons and VulkanMod are required.
> This is not the official Distant Horizons mod. For the original, see the links above.

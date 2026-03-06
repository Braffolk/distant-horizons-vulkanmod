package com.seibel.distanthorizons.common.wrappers.gui;

import com.seibel.distanthorizons.core.config.ConfigHandler;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.coreapi.ModInfo;
import net.minecraft.client.gui.screens.Screen;
import com.seibel.distanthorizons.core.logging.DhLogger;

public class GetConfigScreen
{
	protected static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	public static Screen getScreen(Screen parent)
	{
		if (ModInfo.IS_DEV_BUILD)
		{
			// it'd be nice to have this run automatically on startup
			// but this will only work once MC has added our lang file,
			// which won't be for sure added until we request a GUI
			String missingLangEntries = ConfigHandler.INSTANCE.generateLang(true, true);
			
			// trim to remove any newlines/spaces
			// that may be present when no lang entries need changing
			// then we can check length != 0 if any items are missing and need adding 
			String trimmedMissingEntries = missingLangEntries.trim();
			if (!trimmedMissingEntries.isEmpty())
			{
				LOGGER.warn("One or more language entries is missing:");
				LOGGER.warn(missingLangEntries);
			}
		}
		
		return ClassicConfigGUI.getScreen(parent, "client");
	}
	
}
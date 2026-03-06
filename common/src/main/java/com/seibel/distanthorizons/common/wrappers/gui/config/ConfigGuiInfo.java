package com.seibel.distanthorizons.common.wrappers.gui.config;

import com.seibel.distanthorizons.core.config.gui.IConfigGuiInfo;
import com.seibel.distanthorizons.core.config.types.AbstractConfigBase;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.AbstractMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/** 
 * holds information needed by the config GUI for rendering.
 * 
 * @see AbstractConfigBase
 */
public class ConfigGuiInfo implements IConfigGuiInfo
{
	/**
	 * Used to display validation errors.
	 * Null if no error is present.
	 */
	@Nullable
	public Component errorMessage;
	
	public BiFunction<EditBox, Button, Predicate<String>> tooltipFunction;
	/** determines which options the button will show */
	public AbstractMap.SimpleEntry<Button.OnPress, Function<Object, Component>> buttonOptionMap;
	
}

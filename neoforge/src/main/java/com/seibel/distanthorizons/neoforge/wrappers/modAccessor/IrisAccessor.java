/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.neoforge.wrappers.modAccessor;

// 1.20.6 is the lowest version Iris supports Neoforge
#if MC_VER >= MC_1_20_6

import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;

#if MC_VER != MC_1_21_9
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
#endif

public class IrisAccessor implements IIrisAccessor
{
	public IrisAccessor()
	{
		//#if MC_VER == MC_1_21_11
		//throw new UnsupportedOperationException("Iris isn't supported on this version of DH. When this version of DH was created Iris wasn't available for Neoforge yet.");
		//#endif
	}
	
	
	
	@Override
	public String getModName() 
	{
		#if MC_VER == MC_1_21_9 || MC_VER == MC_1_21_11
		return "iris"; // Iris doesn't support this MC version
		#else
		return Iris.MODID;
		#endif
	}
	
	@Override
	public boolean isShaderPackInUse() 
	{
		#if MC_VER == MC_1_21_9
		return true; // Iris doesn't support this MC version
		#else
		return IrisApi.getInstance().isShaderPackInUse();
		#endif
	}
	
	@Override
	public boolean isRenderingShadowPass() 
	{
		#if MC_VER == MC_1_21_9
		return false; // Iris doesn't support this MC version
		#else
		return IrisApi.getInstance().isRenderingShadowPass();
		#endif
	}
	
}

#endif


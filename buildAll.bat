@echo off & setlocal enabledelayedexpansion
@rem Note for devs: If this script doesnt work, please look at the unix buildAll script
@rem This script was originally created on linux, so there may be some problems with this translation


echo ==================== Note: All build jars will be in the folder called 'buildAllJars' ====================
mkdir buildAllJars
del buildAllJars/*

@rem Loop trough everything in the version properties folder
for %%f in (versionProperties\*) do (
    @rem Get the name of the version that is going to be compiled
    set version=%%~nf

    @rem Clean out the folders, build it, and merge it
    echo ==================== Cleaning workspace to build !version! ====================
    call .\gradlew.bat clean
    
	echo ==================== Building !version! ====================
    call .\gradlew.bat build -PmcVer="!version!"
    
	echo ==================== Merging !version! ====================
    call .\gradlew.bat mergeJars -PmcVer="!version!"
	
    echo ==================== Moving jar ====================
    move build\merged\*.jar buildAllJars\
)

endlocal

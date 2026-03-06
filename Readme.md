# <img src="https://gitlab.com/distant-horizons-team/distant-horizons-core/-/raw/main/_Misc%20Files/logo%20files/new/SVG/Distant-Horizons.svg" height="128px"> 
_See farther without turning your game into a slide show._

<br>

# What is Distant Horizons?

Distant Horizons is a mod which implements a [Level of Detail](https://en.wikipedia.org/wiki/Level_of_detail_(computer_graphics)) system to Minecraft.\
This allows for far greater render distances without harming performance by gradually lowering the quality of distant terrain.

Below is a video demonstrating the system:

<a href="https://youtu.be/SxQdbtjGEsc" target="_blank">![Distant Horizons - Alpha 2.0](https://i.ytimg.com/vi/SxQdbtjGEsc/hqdefault.jpg)</a>

<br>

## Minecraft and Library Versions

### This branch supports the following versions of Minecraft:

#### 1.20.4, 1.20.3 (Default)
Fabric: 0.15.1\
Fabric API: 0.91.2+1.20.4\
Forge: 49.0.30\
NeoForge: 118-beta\
Parchment: 1.20.2:2023.12.10\
Modmenu: 9.0.0-pre.1

#### 1.20.2
Fabric: 0.14.24\
Fabric API: 0.90.4+1.20.2\
Forge: 48.0.13\
Parchment: 1.20.1:2023.09.03\
Modmenu: 8.0.0

#### 1.20.1, 1.20
Fabric: 0.14.24\
Fabric API: 0.90.4+1.20.1\
Forge: 47.2.1\
Parchment: 1.20.1:2023.09.03\
Modmenu: 7.2.2

#### 1.19.4
Fabric: 0.14.24\
Fabric API: 0.87.1+1.19.4\
Forge: 45.2.4\
Parchment: 1.19.4:2023.06.26\
Modmenu: 6.3.1

#### 1.19.2
Fabric: 0.14.24\
Fabric API: 0.76.1+1.19.2\
Forge: 43.3.2\
Parchment: 1.19.2:2022.11.27\
Modmenu: 4.2.0-beta.2

#### 1.18.2
Fabric: 0.14.24\
Fabric API: 0.76.0+1.18.2\
Forge: 40.2.10\
Parchment: 1.18.2:2022.11.06\
Modmenu: 3.2.5

#### 1.17.1, 1.17
Fabric: 0.14.24\
Fabric API: 0.46.1+1.17\
Forge: 37.1.1\
Parchment: 1.17.1:2021.12.12\
Modmenu: 2.0.14

#### 1.16.5, 1.16.4
Fabric: 0.14.24\
Fabric API: 0.42.0+1.16\
Forge: 36.2.39\
Parchment: 1.16.5:2022.03.06\
Modmenu: 1.16.22

### Versions no longer supported
- 1.18.1, 1.18
- 1.19.1, 1.19
- 1.19.3

<br>

### Plugin and Library versions

Gradle: 8.5\
Fabric loom: 1.4-SNAPSHOT\
Architectury loom (Forge gradle replacement): 1.4-SNAPSHOT\
Sponge vanilla gradle: 0.2.1-SNAPSHOT\
Java Preprocessor plugin: Manifold Preprocessor

<br>

## Source Code Installation

### Prerequisites

* A Java Development Kit (JDK) for Java 17 (recommended) or newer. <br>
  Visit https://www.oracle.com/java/technologies/downloads/ for installers.
* Git or someway to clone git projects. <br> 
  Visit https://git-scm.com/ for installers.
* (Not required) Any Java IDE with plugins that support Manifold, for example IntelliJ IDEA.

**If using IntelliJ:**
1. Install the Manifold plugin
2. Open IDEA and import the build.gradle
3. Refresh the Gradle project in IDEA if required

**If using Eclipse: (Note that Eclipse doesn't support Manifold's preprocessor!)**
1. Run the command: `./gradlew geneclipseruns`
2. Run the command: `./gradlew eclipse`
3. Make sure eclipse has the JDK 17 installed. (This is needed so that eclipse can run minecraft)
4. Import the project into eclipse

<br>

## Switching Versions

To switch between different Minecraft versions, change `mcVer=1.?` in the `gradle.properties` file.

If running in an IDE, to ensure the IDE noticed the version change, run any gradle command to refresh gradle.\
In IntelliJ, you will also need to do a gradle sync if it didn't happen automatically.

<br>

## Compiling

Prerequisites:
- JDK 17 or newer

From the File Explorer:
1. Download and extract the project zip
2. Download the core from https://gitlab.com/distant-horizons-team/distant-horizons-core and extract into a folder called `coreSubProjects`
3. Open a terminal emulator in the project folder (On Windows you can type `cmd` in the title bar)
4. Run the commands: `./gradlew assemble` (You may need to use a `.\` on Windows)
5. Merge the jars with `./gradlew mergeJars`
6. The compiled jar file will be in the folder `Merged`

From the command line:
1. `git clone --recurse-submodules https://gitlab.com/distant-horizons-team/distant-horizons.git`
2. `cd distant-horizons`
3. `./gradlew assemble`
4. `./gradlew mergeJars`
5. The compiled jar file will be in the folder `Merged`

Run tests with: `./gradlew test`

>Note: You can add the argument `-PmcVer=?` to tell gradle to build a selected MC version instead of having to modify the `gradle.properties` file.\
> For example: `./gradlew assemble -PmcVer=1.18.2`

<br>

## Compiling with Docker

`./compile <version>`

You can also locally compile the DH jars without a Java environment by using Docker. Where `<version>` is the version of Minecraft to compile for (ie `1.20.1`), or the keyword `all`. See [Versions](#minecraft-and-library-versions) for a list of all supported values.

<br>

## Other commands

`./gradlew --refresh-dependencies` to refresh local dependencies.

`./gradlew clean` to delete any compiled code.

<br>

## Note to self

The Minecraft source code is NOT added to your workspace in an editable way. Minecraft is treated like a normal Library. Sources are there for documentation and research purposes only.

Source code uses Mojang mappings & [Parchment](https://parchmentmc.org/) mappings.

To generate the source code run `./gradlew genSources` <br>
If your IDE fails to auto-detect the source jars when browsing Minecraft classes; manually select the JAR file ending with -sources.jar when prompted by your IDE. <br>
(In IntelliJ it's at the top where it says "choose sources" when browsing a Minecraft class)

<br>

## Other Useful commands

Run the standalone jar: `./gradlew run` <br>
Build the standalone jar: `./gradlew core:build` <br>
Only build Fabric: `./gradlew fabric:assemble` or `./gradlew fabric:build` <br>
Only build Forge: `./gradlew forge:assemble` or `./gradlew forge:build` <br>
Run the Fabric client (for debugging): `./gradlew fabric:runClient` <br>
Run the Forge client (for debugging): `./gradlew forge:runClient` <br>

To build all versions: `./buildAll` (all builds will end up in the `Merged` folder)

<br>

## Open Source Acknowledgements

Forgix (To merge multiple mod versions into one jar) [_Formerly_ [_DHJarMerger_](https://github.com/Ran-helo/DHJarMerger)]\
https://github.com/PacifistMC/Forgix

LZ4 for Java (data compression)\
https://github.com/lz4/lz4-java

NightConfig for JSON & TOML (config handling)\
https://github.com/TheElectronWill/night-config

SVG Salamander for SVG support (not being used atm)\
https://github.com/blackears/svgSalamander

sqlite-jdbc\
https://github.com/xerial/sqlite-jdbc

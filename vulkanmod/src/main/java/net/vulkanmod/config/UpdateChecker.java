package net.vulkanmod.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.impl.util.version.VersionParser;
import net.minecraft.SharedConstants;
import net.vulkanmod.Initializer;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

public abstract class UpdateChecker {
    private static boolean updateAvailable = false;

    public static void checkForUpdates() {
        CompletableFuture.supplyAsync(() -> {
            try {
                String req = "https://api.modrinth.com/v2/project/vulkanmod/version?include_changelog=false";
                String mcVersion = SharedConstants.getCurrentVersion().name();
                req += "&game_versions=%s".formatted(mcVersion);

                URL url = new URL(req);
                HttpURLConnection http = (HttpURLConnection)url.openConnection();
                var inputStream = http.getInputStream();

                JsonObject data = JsonParser.parseString("{ versions: " + new String(inputStream.readAllBytes()) + "}").getAsJsonObject();
                JsonArray versions = data.getAsJsonArray("versions");
                http.disconnect();

                String version = String.valueOf(versions.get(0).getAsJsonObject().get("version_number")).replace("\"", "");

                var currentVersion = VersionParser.parseSemantic(Initializer.getVersion());
                updateAvailable = currentVersion.compareTo(Version.parse(version)) < 0;

                if (updateAvailable) {
                    Initializer.LOGGER.info("Update available!");
                }
            }
            catch (IOException e) {
                Initializer.LOGGER.info("Error occurred, skipping update check.");
            }
            catch (VersionParsingException e) {
                Initializer.LOGGER.info("Unable to parse version, skipping update check.");
            }

            return null;
        });
    }

    public static boolean isUpdateAvailable() {
        return updateAvailable;
    }
}

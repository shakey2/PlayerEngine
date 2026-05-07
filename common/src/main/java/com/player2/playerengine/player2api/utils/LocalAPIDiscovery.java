package com.player2.playerengine.player2api.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Discovers the local Player2 app API URL.
 *
 * The Player2 app hosts a local API at http://127.0.0.1:<port> while running.
 * The port is written to a file at:
 *   Windows: %APPDATA%/game.player2.client/api.port
 *   macOS:   ~/Library/Application Support/game.player2.client/api.port
 *   Linux:   ~/.config/game.player2.client/api.port
 *
 * The file is created when the API starts and removed when the app exits cleanly,
 * so its presence also indicates the app is currently running.
 *
 * NOTE: Always use http://127.0.0.1:<port> directly — http://localhost does not
 * work reliably due to IPv6 resolution conflicts.
 */
public class LocalAPIDiscovery {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String APP_DIR_NAME = "game.player2.client";
    private static final String PORT_FILE_NAME = "api.port";
    private static final int DEFAULT_PORT = 4315;

    /**
     * Returns the local API base URL (e.g. "http://127.0.0.1:4315") if the
     * Player2 app is running, or null if it is not detected.
     */
    public static String getLocalApiUrl() {
        Path portFile = getPortFilePath();
        if (portFile == null) {
            return null;
        }

        if (!Files.exists(portFile)) {
            LOGGER.debug("Player2 local API port file not found at: {}", portFile);
            return null;
        }

        try {
            String content = Files.readString(portFile).trim();
            int port = Integer.parseInt(content);
            String url = "http://127.0.0.1:" + port;
            LOGGER.debug("Discovered local Player2 API at: {}", url);
            return url;
        } catch (IOException e) {
            LOGGER.warn("Failed to read Player2 API port file: {}", e.getMessage());
            return null;
        } catch (NumberFormatException e) {
            LOGGER.warn("Player2 API port file contains invalid port number: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Returns true if the Player2 app is currently running locally.
     */
    public static boolean isLocalAppRunning() {
        return getLocalApiUrl() != null;
    }

    /**
     * Returns the best available API base URL: local if the app is running,
     * otherwise the provided web API URL fallback.
     */
    public static String getPreferredApiUrl(String webApiUrl) {
        String local = getLocalApiUrl();
        if (local != null) {
            LOGGER.info("Using local Player2 API: {}", local);
            return local;
        }
        LOGGER.debug("Local Player2 API not available, using web API: {}", webApiUrl);
        return webApiUrl;
    }

    private static Path getPortFilePath() {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                String appData = System.getenv("APPDATA");
                if (appData == null) return null;
                return Paths.get(appData, APP_DIR_NAME, PORT_FILE_NAME);
            } else if (os.contains("mac")) {
                String home = System.getProperty("user.home");
                if (home == null) return null;
                return Paths.get(home, "Library", "Application Support", APP_DIR_NAME, PORT_FILE_NAME);
            } else {
                // Linux / other
                String xdgConfig = System.getenv("XDG_CONFIG_HOME");
                String home = System.getProperty("user.home");
                if (xdgConfig != null) {
                    return Paths.get(xdgConfig, APP_DIR_NAME, PORT_FILE_NAME);
                } else if (home != null) {
                    return Paths.get(home, ".config", APP_DIR_NAME, PORT_FILE_NAME);
                }
                return null;
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to determine Player2 API port file path: {}", e.getMessage());
            return null;
        }
    }
}

package com.fourbusiness;

import java.nio.file.Path;

/** Runtime configuration, resolved from system properties (same pattern as the other apps in this project). */
public record Config(Path storageDir, String host, int port) {

    public static Config load() {
        String dir = System.getProperty("fourbusiness.storage.dir", "./fourbusiness-data");
        String host = System.getProperty("fourbusiness.http.host", "127.0.0.1");
        int port = Integer.parseInt(System.getProperty("fourbusiness.http.port", "8088"));
        return new Config(Path.of(dir), host, port);
    }
}

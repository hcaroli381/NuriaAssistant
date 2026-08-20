package com.example.nuriaassistant.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Loads configuration properties with priority given to environment variables,
 * followed by Java system properties, and falling back to config.properties.
 */
public class ConfigLoader {
    private final Properties properties = new Properties();

    public ConfigLoader() {
        String path = "config.properties";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                System.out.println("ConfigLoader: Unable to find " + path + " in classpath");
                return;
            }
            properties.load(input);
            System.out.println("ConfigLoader: Successfully loaded " + path);
        } catch (IOException ex) {
            System.err.println("ConfigLoader: Error loading " + path + ": " + ex.getMessage());
        }
    }

    /**
     * Retrieves configuration property value.
     * Priority: Environment variable -> System Property -> config.properties file.
     *
     * @param key Property key name.
     * @return Property value, or null if not found.
     */
    public String getProperty(String key) {
        // Priority 1: Environment variable
        String envVal = System.getenv(key);
        if (envVal != null && !envVal.trim().isEmpty()) {
            return envVal.trim();
        }

        // Priority 2: System property (-Dkey=value)
        String sysProp = System.getProperty(key);
        if (sysProp != null && !sysProp.trim().isEmpty()) {
            return sysProp.trim();
        }

        // Priority 3: config.properties file
        String value = properties.getProperty(key);
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }

        System.out.println("ConfigLoader: Warning - property '" + key + "' is null or empty");
        return null;
    }
}

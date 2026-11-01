package com.utk.speedbeep;

import java.util.Collection;
import java.util.Locale;

/** Profile labels are display names, never file paths or CSV contents. */
public final class RoadProfileNames {
    private RoadProfileNames() {}

    public static String checked(String value) {
        if (value == null) throw new IllegalArgumentException("Enter a profile name");
        String name = value.trim().replaceAll("\\s+", " ");
        if (name.isEmpty() || name.length() > 48)
            throw new IllegalArgumentException("Profile name must have 1–48 characters");
        if (name.indexOf('/') >= 0 || name.indexOf((char) 92) >= 0 ||
        if (name.indexOf(':') >= 0 || name.indexOf('\n') >= 0 ||
                name.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Profile name cannot contain path characters");
        return name;
    }

    public static void unique(String name, Collection<String> existingNames) {
        String key = name.toLowerCase(Locale.ROOT);
        for (String existing : existingNames) {
            if (existing.toLowerCase(Locale.ROOT).equals(key))
                throw new IllegalArgumentException("A profile with this name already exists");
        }
    }
}

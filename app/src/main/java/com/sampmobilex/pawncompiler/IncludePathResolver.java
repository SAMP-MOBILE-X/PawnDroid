package com.sampmobilex.pawncompiler;

import java.io.File;

final class IncludePathResolver {
    private IncludePathResolver() { }

    static String[] resolve(File main, File gamemodeFolder, File selectedIncludeFolder) {
        if (main == null || gamemodeFolder == null || selectedIncludeFolder == null
                || !selectedIncludeFolder.isDirectory())
            throw new IllegalArgumentException("Select your gamemode, main .pwn and include folder");

        return new String[]{selectedIncludeFolder.getAbsolutePath()};
    }
}

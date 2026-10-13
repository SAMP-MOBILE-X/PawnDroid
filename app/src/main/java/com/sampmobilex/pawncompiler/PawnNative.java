package com.sampmobilex.pawncompiler;


public final class PawnNative {
    static { System.loadLibrary("pawnc"); }
    private PawnNative() { }
    public static native String compilerVersion();


    public static native int compile(String mainPwn, String outputAmx,
                                     String[] includeDirectories, String[] compilerFlags, String diagnostics,
                                     String tempDirectory);
}

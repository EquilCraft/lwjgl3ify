package org.lwjglx;

import org.lwjgl.system.Configuration;
import org.lwjgl.system.JNI;
import org.lwjgl.system.Platform;
import org.lwjgl.system.linux.LinuxLibrary;

/** Selects SDL before any of its LWJGL bindings initialize. */
final class SdlLibrary {

    private SdlLibrary() {}

    static void configure() {
        if (Platform.get() != Platform.LINUX || Configuration.SDL_LIBRARY_NAME.get() != null) {
            return;
        }

        // LWJGL 3.4.3 bundles SDL 3.4.14, which corrupts relative motion on XWayland.
        // SDL 3.4.18 includes libsdl-org/SDL#16267. Use the system loader rather
        // than assuming a distribution-specific /usr/lib or /usr/lib64 path.
        try (LinuxLibrary library = new LinuxLibrary("libSDL3.so.0")) {
            long getVersion = library.getFunctionAddress("SDL_GetVersion");
            if (getVersion == 0L) {
                return;
            }
            int version = JNI.invokeI(getVersion);
            if (version < 3_004_018 || version >= 4_000_000) {
                return;
            }
            String path = library.getPath();
            if (path == null) {
                return;
            }
            Configuration.SDL_LIBRARY_NAME.set(path);
            System.out.println("lwjgl3ify: Using system SDL " + version + " from " + path);
        } catch (UnsatisfiedLinkError e) {
            // SDL is optional on the host. Keep LWJGL's bundled library when
            // the system copy is missing or cannot be loaded on this machine.
        }
    }
}

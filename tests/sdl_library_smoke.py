"""Linux native selection regression test (no display server required).

After compileJava, pass the runtime classpath containing build/classes/java/main,
LWJGL core + SDL bindings, and their natives-linux jars as the sole argument.
Requires a JDK, cc, and a system SDL 3.4.18+ for the positive selection case.
Each case uses a new JVM: SDL's library binding is process-global.
"""
import os
from pathlib import Path
import subprocess
import sys
import tempfile

PROBE = """
import org.lwjgl.sdl.SDLVersion;
import org.lwjgl.system.Configuration;
public class SdlSelectionProbe {
    public static void main(String[] args) throws Exception {
        String original = Configuration.SDL_LIBRARY_NAME.get();
        Class.forName("org.lwjglx.Sys");
        String selected = Configuration.SDL_LIBRARY_NAME.get();
        int version = SDLVersion.SDL_GetVersion();
        if (args[0].equals("system")) {
            if (selected == null || version < 3004018 || version >= 4000000)
                throw new AssertionError("Fixed system SDL was not selected: " + version);
        } else {
            if (!java.util.Objects.equals(original, selected))
                throw new AssertionError("Explicit selection/fallback changed");
            if (version != 3004014)
                throw new AssertionError("Expected bundled SDL 3.4.14, got " + version);
        }
        System.out.println("PASS " + args[0] + ": " + version + " " + selected);
    }
}
"""


def main():
    classpath = sys.argv[1]
    with tempfile.TemporaryDirectory(prefix="lwjgl3ify-sdl-test-") as temp:
        root = Path(temp)
        source = root / "SdlSelectionProbe.java"
        source.write_text(PROBE)
        subprocess.run(["javac", "-cp", classpath, str(source)], check=True)
        command = ["java", "--enable-native-access=ALL-UNNAMED", "-cp",
                   classpath + os.pathsep + temp]

        def run(case, directory=None, explicit=False):
            env = os.environ.copy()
            for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
                env.pop(key, None)
            if directory:
                env["LD_LIBRARY_PATH"] = str(directory)
            options = ["-Dorg.lwjgl.sdl.libname=libSDL3.so"] if explicit else []
            subprocess.run(command + options + ["SdlSelectionProbe", case], env=env, check=True)

        run("system")
        run("explicit", explicit=True)
        for name, version in [("old", 3004014), ("wrong-major", 4000000), ("no-symbol", None)]:
            directory = root / name
            directory.mkdir()
            c_source = directory / "stub.c"
            c_source.write_text("int unrelated(void) { return 0; }" if version is None else
                                f"int SDL_GetVersion(void) {{ return {version}; }}")
            subprocess.run(["cc", "-shared", "-fPIC", str(c_source), "-o",
                            str(directory / "libSDL3.so.0")], check=True)
            run(name, directory)
        directory = root / "unloadable"
        directory.mkdir()
        (directory / "libSDL3.so.0").write_text("not a shared library")
        run("unloadable", directory)


if __name__ == "__main__":
    main()

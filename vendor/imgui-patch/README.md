# Patched imgui-java 1.86.11 for Windows x64

This experimental native build fixes the lifetime of explicit glyph ranges for `ImFontAtlas.addFontFromFileTTF`. The shipped font loader uses only these file overloads, on the render thread, with trusted bundled TTFs. Other `short[]` and memory overloads remain upstream and are **not fixed**. The client deliberately fails before creating an ImGui context on unsupported platforms; no compatibility with Linux or macOS is claimed.

Upstream imgui-java v1.86.11 commit `2cef96d9936fdfcbedb3f9327d2463c6bf6f280e`; Dear ImGui submodule commit `15b4a064f9244c430e65214f7249b615fb394321`. `source-work.patch` and `source-imgui.patch` describe the source changes. `native-jni-sources.zip` contains the exact generated JNI and C++ sources used for the clean Windows build, including generated MSVC portability fixes not represented by those two source patches. SHA-256 of that archive: `520EC6FBA341A6C4F1CE3AB90F774CF999A3BF26572C46ED846DB1835F6A4D4D`. MSVC 19.40.33821 / Windows SDK 10.0.22621 compiled all 65 files in `sources.txt` from scratch with `/DWIN32 /O2 /EHsc /std:c++14 /MD`; all resulting objects were linked into a DLL. This is not a bit-reproducible build guarantee. Shipped DLL SHA-256: `6D65E403C1C1392D8BE9F0A0C937C8AB38AF4C86482D72129D6D2A5484ECBA4D`.

## Rebuild and test

From a Visual Studio x64 Developer PowerShell **7** with MSVC and Windows SDK available, run from the repository root (choose a non-existing output directory):

```powershell
./vendor/imgui-patch/build-native.ps1 -OutputDirectory "$env:TEMP/schizoid-imgui-build"
```

The script verifies the source archive hash, extracts it to the new directory, compiles every entry in `sources.txt`, links the DLL, and prints its SHA-256. Native DLL depends on Microsoft Visual C++ runtime. This uses the checked-in generated source archive, not regeneration from upstream patches; archive contents and toolchain must be trusted. Different toolchain versions may produce different binary hashes.

To test with a JDK compatible with the compiled binding (JDK 25 for this project's `build/classes/java/main`) and a previously built **patched binding** on the classpath, run:

```powershell
$binding = "<absolute path to build/classes/java/main>;<absolute path to imgui-java-binding-1.86.11.jar>"
./vendor/imgui-patch/run-tests.ps1 -BindingClasspath $binding -NativeDirectory "$env:TEMP/schizoid-imgui-build" -JavaHome $env:JAVA_HOME
```

`BindingClasspath` must put the project's patched `imgui.ImFontAtlas` **before** the upstream JAR; using the unmodified binding is incompatible with the patched JNI. `-NativeDirectory` must contain the matching `imgui-java64.dll`; the runner selects it using `imgui.library.path`, compiles tests in a unique temporary directory, uses the existing project fonts directly, runs `-Xcheck:jni`, and deletes only its own temporary test directory. Override `-FontDirectory` if required. The runner does not invoke Gradle or copy font files.

Tests in `tests` were run against the clean DLL with `-Xcheck:jni`: 20 contexts and two rebuilds checking Russian U+0410–044F plus Ё/ё and a Font Awesome glyph; two simultaneously live contexts with separate atlases and failed file loads; four file overloads, malformed ranges and repeated builds. A separate `-Xcheck:jni` smoke also passed with Cyrillic BMP characters in the temporary directory and filename. The tests do not establish behaviour under native OOM, simultaneous use of one atlas from multiple threads, malformed untrusted TTF, non-BMP Windows paths, other operating systems, or other ImGui overloads. Upstream and included component licenses are in `licenses/` and are included in the shaded JAR. `ImFontAtlas.java` is derived from imgui-java and modified for this patch.

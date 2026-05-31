This directory must contain libytdlp.so (the yt-dlp ARM64 binary renamed to .so).
Android installs all .so files from jniLibs/arm64-v8a/ to the app's nativeLibraryDir,
which is mounted executable — unlike filesDir which is noexec on Samsung devices.

The GitHub Actions workflow downloads yt-dlp and places it here as libytdlp.so.

# WikiTok for Android

An Android port of [WikiTok](https://www.wikitokapp.com/) — a TikTok-style vertical
feed of random Wikipedia articles. Swipe up for the next article; tap the heart to
save it, share it, or open it on Wikipedia. Supports 10 Wikipedia languages.

Built with Kotlin + Jetpack Compose (`VerticalPager`), Coil for images, OkHttp +
kotlinx.serialization for the Wikipedia Action API, and DataStore for saved articles.

## Building (nix)

Everything — Android SDK, emulator, gradle, JDK — comes from the flake:

```sh
nix develop -c gradle assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

## Running in the headless emulator

```sh
nix develop
avdmanager create avd -n wikitok -k 'system-images;android-35;google_apis;arm64-v8a' -d pixel_7
emulator -avd wikitok -no-window -no-audio -no-boot-anim &
adb wait-for-device
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.novalinium.wikitok/.MainActivity
adb exec-out screencap -p > screen.png
```

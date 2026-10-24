# Tercüman Keyboard

An Android keyboard that translates what you type entirely on the phone. After a single model download, no internet connection is needed. It is made for writing in Turkish and sending English, and the other way round.

## Features

* **Translate** button above the keys. It replaces the text in the box, or only the selected part, with its translation. **Undo translation** puts the original back.
* **⇄** switches between Turkish → English and English → Turkish.
* **Clipboard** translates the copied text in the opposite direction, so you can read a message you received. The result can be inserted or copied.
* Works with any other keyboard too (Samsung, Gboard and others). Select text in any app, choose **Translate (Tercüman)** from the selection menu, then **Replace**.
* Turkish Q layout with ğ ü ş ı ö ç, Turkish capitalisation (i → İ), number row and symbols.
* The interface is in Turkish when the phone language is Turkish, and in English otherwise.

## How it works

* **Model:** TranslateGemma 4B, the translation model from Google based on Gemma 3, as a 2.49 GB Q4_K_M GGUF file. It is downloaded from Hugging Face at a pinned revision and checked against its SHA256 hash.
* **Inference:** llama.cpp release `b11857`, fetched by CMake during the build. Every ARM CPU variant is compiled and the best one for the phone is chosen when the app runs.
* **Prompt:** the exact prompt that the chat template of TranslateGemma produces, so the model returns only the translation.
* **Memory:** the model is loaded when the keyboard opens, stays loaded while it is used, and is unloaded again when idle. This can be changed in the settings.

When the app is opened for the first time, the model download starts automatically (WiFi recommended). You can also copy a `.gguf` file onto the phone and pick it with **Use a model file…**.

## Building

Requirements:

* JDK 17 or newer
* Android SDK platform 35
* NDK `28.2.13676358`
* CMake `3.31.6`

Android Studio installs the NDK and CMake automatically. From the command line you can use `sdkmanager "ndk;28.2.13676358" "cmake;3.31.6"`. The first build downloads llama.cpp from GitHub.

```bash
./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

In Android Studio, open this folder and use Build, then Build APK(s).

The release build is signed with the debug key so that it installs directly. Use your own key if you ever publish it.

To build without internet, clone llama.cpp at tag `b11857` and add this line to `gradle.properties`:

```properties
llamaCppDir=/path/to/llama.cpp
```

## First use on the phone

1. Open **Tercüman Keyboard**. The model download starts by itself.
2. Tap **Enable**, turn on "Tercüman", then tap **Switch keyboard**.
3. Try a sentence in the **Try it** box. The first translation also loads the model into memory.

If translation feels slow, try other values for **CPU threads** in the settings.

## Testing on a PC

The translation engine can be built and run on a Linux or macOS computer:

```bash
cmake -S tools/host-test -B build-host -DCMAKE_BUILD_TYPE=Release
cmake --build build-host -j
./build-host/translate-cli translategemma-4b-it.Q4_K_M.gguf tr en "Yarın görüşürüz"
```

Unit tests, plus an end to end test of the Kotlin, JNI and native engine path with the real model:

```bash
./gradlew :app:testDebugUnitTest \
  -Dtercuman.hostLib=$PWD/build-host/app/libtercuman.so \
  -Dtercuman.model=/path/to/translategemma-4b-it.Q4_K_M.gguf
```

## Limitations

* No word suggestions or autocorrect. If you want those, type with your usual keyboard and use the selection menu option.
* Up to 2500 characters per translation. Select a part of longer texts.
* Translation quality is good for everyday messages but not perfect. Check important texts.

## Licences

The app code is MIT licensed, and so is llama.cpp. The model is distributed under the Gemma Terms of Use (`https://ai.google.dev/gemma/terms`). It is downloaded from Hugging Face and is not bundled with the app.

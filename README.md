# SapiDroid

Microsoft's classic speech voices as an Android system TTS engine, offline.

Four engine families, all portable C reconstructions that match Microsoft's own
output:

- **SAPI 4** (1999): Sam, Mike, Mary and their 18 modes, including the Whisper,
  Space, Hall, Stadium and RoboSoft variants. Engine:
  [sapi4-decomp](https://github.com/Quinton1110/sapi4-decomp) by Quinton
  Williams, MIT. It reads its tables from Microsoft's `msttssyn.dll`.
- **SAPI 5** (Windows XP): Sam, Mike and Mary. Engine:
  [ms-sam-mike-mary-decomp](https://github.com/KamiKitsune420/ms-sam-mike-mary-decomp)
  by KamiKitsune420, MIT.
- **Anna** (Windows Vista and 7): Microsoft's Vista voice, from the "Mulan"
  engine. Engine:
  [ms-ana-decomp](https://github.com/KamiKitsune420/ms-ana-decomp) by
  KamiKitsune420, MIT: a bit-exact C99 rebuild, including her WMA Voice 9
  decoder, rate change and pitch change.
- **OneCore** (Windows 8, 10, 11): David, Zira and Mark.
  [ms-david-zira-decomp](https://github.com/KamiKitsune420/ms-david-zira-decomp),
  MIT.

## Voice data is not included

The voice files belong to Microsoft and are not in this repository. SapiDroid
reads them from its own private folders.

### SAPI 4

`files/sapi4`: `msttssyn.dll` (Speech Build 4.0.4.2512, 851456 bytes, SHA-256
`a8a2ddb9b82b4325e1315f481302cd5ba9edcf3742425ed582370f1b3940f731`), plus
`sam.vce`, `sam.cfg`, `male.vce`, `male.cfg`, `female.vce`, `female.cfg`.
Microsoft's 1999 `msttsl.exe` supplies all of them.

### SAPI 5

`files/sapi5`: `Sam.spd`, `Sam.sdf`, `Mike.spd`, `Mike.sdf`, `Mary.spd`,
`Mary.sdf`, `LTTS1033.LXA` and `r1033tts.LXA`. Names are case-sensitive on
Android; the two lexicon files are upper case exactly as shown.

They come from Microsoft's Speech SDK 5.1, `SpeechSDK51.exe`, whose SHA-256 is
`520aa5d1a72dc6f41dc9b8b88603228ffd5d5d6f696224fc237ec4828fe7f6e0` (the same
file winetricks fetches for its `speechsdk` verb). Unpack it, then unpack
`Microsoft Speech SDK 5.1.msi`; the files sit under
`Common/SpeechEngines/Microsoft/TTS/1033` and `.../Lexicon/1033`.

### OneCore

`files/voice`: `M1033<Voice>.{APM,BEP,INI}` for David, Zira and Mark, plus the
shared `MSTTSLocEnUS.dat`, from
`C:\Windows\Speech_OneCore\Engines\TTS\en-US` on Windows 8, 10 and 11. Those are
the three files per voice the engine loads; Windows keeps `.NUS` tables beside
them, which it does not read.

Mark is the Windows 10/11 voice; copy his three files and he appears in the voice
list. The shared table is not optional: the engine opens it whichever voice it
speaks with, so a voice without it cannot start. Any OneCore voice whose files
are in this form is read the same way, since the engine reads the voice files
rather than knowing the voices.

### OneCore voices in the app

The app can fetch the English OneCore voices that are stored as plain files in
[Sapple's](https://quintonwilliams.me/sapple/) iPhone build, which carries
Microsoft's voice data for personal use. Choose **Download OneCore voices**:
the screen lists each voice with its language, starting with American English,
and the first pick downloads Sapple's 200 MB bundle. It stays in the cache while
the app is open, so picking more voices costs nothing, and it is deleted when
the app closes.

Included are David, Zira and Mark (American English), George, Susan and Hazel
(British English), James and Catherine (Australian English), and Richard and
Linda (Indian English). Every pick also installs `MSTTSLocEnUS.dat`, the shared
table the engine reads with every voice, so a downloaded voice works on its own
and appears in the app's voice list at once.

Three voices in the bundle are not offered because the engine cannot load their
model format: Sarah and Eva, and Matilda.

### Anna

`files/anna`: `M1033DSK.{CSD,IDX,UNT,CRT,APL,UDT,TTS,LTS,WIH}` from Microsoft's
Vista/7 voice, plus two tables the engine needs: `M1033DSK.KEY` (the 129-byte
audio key from `MSTTSDecWrp.dll`) and `M1033DSK.HAN` (the pitch table from
`MSTTSEngine.dll`).

In the app, choose **Get Anna from Sapple**: her eleven files are inside the
same bundle as the OneCore voices, so no unpacking of Microsoft's installer is
needed. By hand, the rebuild's own tool makes the folder from the Windows 7
voices installer without running it:

```
git clone https://github.com/KamiKitsune420/ms-ana-decomp
python tools/anna_extract.py Win7_TTS_Voices_v1.1.exe anna-data
adb push anna-data/. /data/local/tmp/anna-data
adb shell "run-as org.sapidroid.tts mkdir -p files/anna"
adb shell "run-as org.sapidroid.tts cp /data/local/tmp/anna-data/* files/anna/"
```

Older Anna packages, such as the 2006 XP-era one, do not work: their engine
files carry neither the audio key nor the same voice tables.

### Getting the SAPI 4 files in the app

Choose **Get SAPI 4 voices from GitHub**. The 1999 installer's cabinet header is
damaged (it reports 3723 folders and 65317 files), so the app does not unpack
it: it fetches the seven files themselves from the unpacked copy published at
[LouisGameDev/Microsoft-Sam-Mary-Mike-TruVoice-WSAPI4](https://github.com/LouisGameDev/Microsoft-Sam-Mary-Mike-TruVoice-WSAPI4)
(about 6.5 MB), checks each against its SHA-256, and saves them in
`files/sapi4`. The notice names GitHub, the size and Microsoft's ownership
before anything is downloaded.

### Getting the SAPI 5 files in the app

The SAPI 5 files come from Microsoft's installer held by the Internet Archive. In
the app, choose
**Get SAPI 5 voices from archive.org**. It says where the file comes from and
that the voices are Microsoft's, then downloads it (about 71 MB), unpacks the
eight files into the app's private storage, and deletes the download. A
partial download is resumed next time rather than started over.

### Putting them on the phone by hand

The app is debuggable, so `adb` can write into its private storage:

```
adb push SpeechSDK51.exe /sdcard/Download/
adb shell run-as org.sapidroid.tts mkdir -p files/sapi5
adb shell "cat /sdcard/Download/Sam.spd | run-as org.sapidroid.tts sh -c 'cat > files/sapi5/Sam.spd'"
```

Repeat for each file. The app's screen lists which voices it can see, and the
voice picker offers them once their data is complete.

## How it is built

- `app/src/main/cpp/` builds all three engines plus JNI bridges. The OneCore
  engine is compiled with `-ffp-contract=off` and no fast math, which
  bit-exactness requires.
- All engines share one private `:engine` process behind a binder, the same
  shape as BestDroid and TrueVoiceDroid: stopping calls the engine's own stop,
  and a worker that will not answer is retired and replaced rather than
  interrupted inside native code.
- Android's rate and pitch percentages are mapped onto each engine's own scale:
  SAPI 5's -10..10 for SAPI 5 and OneCore, and the mode's pitch range and words
  per minute for SAPI 4.

### Building the APK on Windows

The build needs a JDK, the Android SDK, and the NDK and CMake versions
`app/build.gradle.kts` pins.

1. **JDK 17.** Android Gradle Plugin 8.5.2 is built and tested for JDK 17, not
   newer ones. Install Eclipse Temurin 17:

   ```powershell
   winget install EclipseAdoptium.Temurin.17.JDK
   ```

   Then set `JAVA_HOME` in a new PowerShell window. The folder name includes
   the patch version, so list what the installer created rather than copying a
   version from an example:

   ```powershell
   $jdk = (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory |
           Where-Object Name -like 'jdk-17*' |
           Sort-Object Name | Select-Object -Last 1).FullName
   [Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk, 'User')
   $env:JAVA_HOME = $jdk
   & "$env:JAVA_HOME\bin\java" -version
   ```

   The last line must print `17.x`. Until it does, every later step fails with
   `ERROR: JAVA_HOME is set to an invalid directory: ...`, which means the
   variable names a folder that does not exist. A per-user `JAVA_HOME`
   overrides the machine-wide one, so fix the user variable if the machine one
   is already correct. Windows only passes new environment variables to
   programs started afterwards, so open a fresh window (or reopen Android
   Studio) after setting them.

   If the build stops with a bare version number such as `25.0.4` under "What
   went wrong", Gradle found a newer JDK; `.\gradlew.bat -version` shows which
   one it used under `Launcher JVM`.

2. **Android SDK.** The simplest way is Android Studio:

   ```powershell
   winget install Google.AndroidStudio
   ```

   In **SDK Manager**, tick *Android SDK Platform 34*, *Android SDK Build-Tools
   34.0.0*, *NDK (Side by side) 28.0.13004108* and *CMake 3.22.1*. Then point
   Gradle at the SDK, in the same PowerShell window:

   ```powershell
   $sdk = "$env:LOCALAPPDATA\Android\Sdk"
   [Environment]::SetEnvironmentVariable('ANDROID_HOME', $sdk, 'User')
   $env:ANDROID_HOME = $sdk
   Test-Path $env:ANDROID_HOME
   ```

   Do not run `setx ANDROID_HOME "$env:LOCALAPPDATA\Android\Sdk"` from cmd:
   `$env:` is PowerShell syntax, and cmd stores that text literally, so Gradle
   fails with `SDK location not found`. In cmd, write
   `setx ANDROID_HOME "%LOCALAPPDATA%\Android\Sdk"` instead.

   Android Studio writes the same path into `local.properties` itself. If you
   would rather use that file than the variable, add
   `sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`.

   Gradle downloads these SDK packages during the first build when the licences
   are accepted, so ticking them by hand only saves time; without accepted
   licences the build stops with "Failed to install the following Android SDK
   packages".

3. **Build.** From the folder holding this README:

   ```powershell
   .\gradlew.bat :app:assembleDebug
   ```

   The first run downloads Gradle 8.13, the pinned NDK and CMake if the SDK is
   missing them, and the dependencies, so it takes several minutes. The APK is
   written to `app\build\outputs\apk\debug\app-debug.apk`. Add
   `--console=plain` for readable progress in a terminal.

4. **Install.** `adb.exe` lives in the SDK's `platform-tools` folder, which the
   SDK installer does not add to `PATH`. Add it once, in a new PowerShell
   window:

   ```powershell
   [Environment]::SetEnvironmentVariable(
       'Path',
       [Environment]::GetEnvironmentVariable('Path', 'User') +
           ";$env:LOCALAPPDATA\Android\Sdk\platform-tools",
       'User')
   ```

   Without that step, `adb install` fails with `'adb' is not recognized`. With
   the phone connected and USB debugging on:

   ```powershell
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   ```

   If it stops with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the copy on the phone
   was signed with a different key (debug builds use a per-machine key). Run
   `adb uninstall org.sapidroid.tts` first; that also deletes its downloaded
   voices.

   Open SapiDroid, download a voice, then choose SapiDroid as the engine under
   **Settings → System → Languages & input → Text-to-speech**.

   The picker on SapiDroid's own screen is the engine's default voice, and that
   is what screen readers use: TalkBack and its forks, such as Backtalk, have no
   voice list of their own. They set a language and take whatever voice the
   engine names for it. A reader that is already running keeps the voice it
   connected with, so restart Backtalk (or the phone) after changing the picker.
   Android's Text-to-speech screen also links here, through the settings
   shortcut beside SapiDroid's name.

On Linux or macOS, run `./gradlew :app:assembleDebug` instead.

## Natural voices

Sapple runs the Windows 11 natural voices on Microsoft's embedded Speech SDK.
That SDK also ships for Android
(`com.microsoft.cognitiveservices.speech:client-sdk-embedded`), so the same
approach is possible here, but embedded speech is limited access: Microsoft
grants it per account, and the voice packages come with the grant.

## Licence

SapiDroid's own code: MIT. All three vendored engines are MIT and keep their
licences beside their sources. Voice data and Microsoft engine data are not
distributed here.

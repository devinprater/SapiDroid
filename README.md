# SapiDroid

Microsoft's classic speech voices as an Android system TTS engine, offline.

Two engine families, both portable C reconstructions that match Microsoft's own
output:

- **SAPI 4** (1999): Sam, Mike, Mary and their 18 modes, including the Whisper,
  Space, Hall, Stadium and RoboSoft variants. Engine:
  [sapi4-decomp](https://github.com/Quinton1110/sapi4-decomp) by Quinton
  Williams, MIT. It still reads its tables from Microsoft's `msttssyn.dll`.
- **OneCore** (Windows 8, 10, 11): David and Zira. Engine:
  [ms-david-zira-decomp](https://github.com/KamiKitsune420/ms-david-zira-decomp)
  by KamiKitsune420, MIT.

## Voice data is not included

The voice files belong to Microsoft and are not in this repository. SapiDroid
reads them from its own private folders.

### SAPI 4

Needed in `files/sapi4`: `msttssyn.dll` (Speech Build 4.0.4.2512, 851456 bytes,
SHA-256 `a8a2ddb9b82b4325e1315f481302cd5ba9edcf3742425ed582370f1b3940f731`),
plus `sam.vce`, `sam.cfg`, `male.vce`, `male.cfg`, `female.vce`, `female.cfg`.
Microsoft's 1999 voice installer `msttsl.exe` supplies all of them.

### OneCore

Needed in `files/voice`: `M1033David.APM`, `.BEP`, `.INI`, and for Zira
`M1033Zira.APM`, `.BEP`, `.INI`, `.SPEECHUX.NUS`, `.TBT.NUS`, plus the shared
`MSTTSLocEnUS.dat`. They live in `C:\Windows\Speech_OneCore\Engines\TTS\en-US`
on Windows 8, 10 and 11.

### Putting them on the phone

The app is debuggable, so `adb` can write into its private storage:

```
adb push msttssyn.dll /sdcard/Download/
adb shell run-as org.sapidroid.tts mkdir -p files/sapi4
adb shell "cat /sdcard/Download/msttssyn.dll | run-as org.sapidroid.tts sh -c 'cat > files/sapi4/msttssyn.dll'"
```

Repeat for each file. The app's screen lists which voices it can see.

## How it is built

- `app/src/main/cpp/` builds both engines plus a JNI bridge. The OneCore engine
  is compiled with `-ffp-contract=off` and no fast math, which bit-exactness
  requires.
- Both engines run in one private `:engine` process behind a binder, the same
  shape as BestDroid and TrueVoiceDroid: stopping calls the engine's own stop,
  and a worker that will not answer is retired and replaced rather than
  interrupted inside native code.
- Android's rate and pitch percentages are mapped onto each engine's own scale:
  SAPI 5's -10..10 for OneCore, and the mode's pitch range and words per minute
  for SAPI 4.

```
gradle :app:assembleDebug
```

## Natural voices

Sapple runs the Windows 11 natural voices on Microsoft's embedded Speech SDK.
That SDK also ships for Android
(`com.microsoft.cognitiveservices.speech:client-sdk-embedded`), so the same
approach is possible here, but embedded speech is limited access: Microsoft
grants it per account, and the voice packages come with the grant.

## Licence

SapiDroid's own code: MIT. Both vendored engines are MIT and keep their
licences beside their sources. Voice data and Microsoft engine data are not
distributed here.

# SapiDroid

Microsoft's classic speech voices as an Android system TTS engine. The first
release carries the OneCore voices David and Zira, the ones Windows 8, 10 and
11 ship, running offline on the phone.

The engine is [ms-david-zira-decomp](https://github.com/KamiKitsune420/ms-david-zira-decomp)
by KamiKitsune420: a portable C99 reconstruction of the OneCore engine that is
bit-identical to Microsoft's, MIT licensed. It is vendored under `engine/` with
its licence.

## Voice data is not included

The voice files belong to Microsoft and are not in this repository. SapiDroid
reads them from the app's private folder, so you copy your own from a Windows
install:

- `M1033David.APM`, `.BEP`, `.INI`
- `M1033Zira.APM`, `.BEP`, `.INI`, `.SPEECHUX.NUS`, `.TBT.NUS`
- `MSTTSLocEnUS.dat`

They live in `C:\Windows\Speech_OneCore\Engines\TTS\en-US` on Windows 8, 10
and 11.

The app is debuggable, so `adb` can put them straight into its private folder:

```
adb shell run-as org.sapidroid.tts mkdir -p files/voice
adb push M1033David.APM /sdcard/Download/
adb shell "cat /sdcard/Download/M1033David.APM | run-as org.sapidroid.tts sh -c 'cat > files/voice/M1033David.APM'"
```

Repeat for each file. The app's screen shows which voices it can see.

## How it is built

- `app/src/main/cpp/` builds the engine and a small JNI bridge; the engine is
  compiled with `-ffp-contract=off` and no fast math, which bit-exactness
  requires.
- The engine runs in a private `:engine` process behind a binder, the same
  shape as BestDroid and TrueVoiceDroid: stopping asks the engine to stop, and
  a worker that will not answer is retired and replaced instead of being
  interrupted inside native code.
- The TTS service maps Android's rate and pitch percentages onto the engine's
  SAPI scale.

```
gradle :app:assembleDebug
```

## Natural voices

Sapple runs the Windows 11 natural voices on Microsoft's embedded Speech SDK.
That SDK also ships for Android (`com.microsoft.cognitiveservices.speech:client-sdk-embedded`),
so the same approach is possible here, but embedded speech is limited access:
Microsoft has to grant it, and the voice packages come with that access. It is
not in this release.

## Licence

SapiDroid's own code: MIT. The vendored engine keeps its MIT licence (see
`LICENSE.zira`). Voice data and Microsoft engine data are not distributed here.

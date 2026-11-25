# Archives

Opens .zip, .7z and .rar archives on Android, shows what's in them, and
extracts all of an archive or the parts you pick. It also makes .zip and .7z
archives, with or without a password, from files and folders you pick.

Its engine is FerroOS's archive tool (`ferro-archive`), ported here: the
same Rust code, tests and safety rules, made to write through Android's own
file access.

## Privacy

- **No internet.** The app asks for no permissions at all, so Android won't
  let it reach the network. Every release build checks this, and fails if
  the APK asks for any permission.
- **It sees only what you choose.** That's the archives you open (from
  Archives, or with "Open with" in another app) and the folders you add.
  It never asks for "all files" or photos access.
- **It writes only where you say.** Nothing is extracted until you pick
  where it goes, and new archives go in the folder you made them in. There
  are no copies, caches or temporary files anywhere else.
- **No logs.** Archives writes nothing to Android's log, not even when it
  crashes; it just closes. Release builds check the app's and the engine's
  code for logging and network code.
- **No history.** The app keeps only the list of folders you added and the
  screenshot setting. It doesn't keep which archives you opened, what was in
  them, or passwords. A password is forgotten when you leave the archive.
- **No backups.** Nothing of Archives' goes to cloud backups or device
  transfers.
- **Private screen (optional).** "Hide from screenshots and recent apps"
  keeps the app out of screenshots, screen recordings and the recent-apps
  view.
- **New archives say little.** Each file keeps its name and contents. Its
  dates go in only if you tick "Keep when each file was last changed". With
  a password, .7z (AES-256) hides the file names too; .zip (AES-256) locks
  the contents but leaves the names readable.

## Extracting safely

Whatever an archive contains, extracting it:

- **Stays inside the folder you chose.** Names with `..`, a leading `/` or a
  drive letter are skipped and counted.
- **Never replaces anything already there.** Android's storage treats
  `A.txt` and `a.txt` as the same file, so either name counts as "already
  there".
- **Leaves out symbolic links.** Android's file access can't make them, so
  they're skipped and counted.
- **Stops zip bombs.** Extraction stops at the free space on the drive, past
  1000 times the archive's size after the first GB, and at any file that
  holds more than it says it does.
- **Never shows a half-written file.** Each file is written under a hidden
  name and given its real name only once it's whole. Anything cut short by
  an error or Cancel is removed.
- **"Extract here" makes one new folder.** It's named after the archive, or
  after the one folder everything inside is in. If that name is taken, it
  becomes "Name (2)".

## Using it

- **Open an archive.** Use "Open an archive", or "Open with" in another app.
  Tap folders to look inside them; hold files or folders to pick some.
  - **Extract here** puts everything in a new folder beside the archive.
    It's there only when the archive is in a folder you added.
  - **Extract to…** lets you choose a folder. Android's permission to write
    there lasts only for that one extraction.
- **Add a folder** to browse it. Tap an archive to open it. Hold files and
  folders to pick them, then **Make an archive**. You choose:
  - the name;
  - .zip or .7z;
  - the compression;
  - an optional password;
  - whether to keep the files' dates.

Not yet: archives split into several files (`.part1.rar`, `.7z.001`), and
adding to an existing archive. Both work in FerroOS's desktop version.

## Building

- **Engine tests:** `cargo test --manifest-path core/Cargo.toml`
- **App:** `android/build-android.sh [arm64-v8a|x86_64] [debug|release]`.
  It needs the Android SDK and NDK, `cargo-ndk` and a JDK.
- **Releases:** every push to `main` that passes the tests publishes signed
  APKs. `archives-android-arm64.apk` is for phones;
  `archives-android-x86_64.apk` is for the Android emulator. Signing happens
  in a separate job that runs none of the build's code. Its certificate's
  SHA-256 is in `android/release-cert.sha256`.

## License

MIT or Apache-2.0, at your choice, as FerroOS.

//! Archives' engine (archives-core) for Kotlin, through UniFFI.
//!
//! The app hands over an archive as an open file (its descriptor), read
//! here through `/proc/self/fd/N`, and a folder you chose as a [`Folder`]:
//! Android's file access, with documents named by ids. Nothing is written
//! anywhere else, and nothing here logs or touches the network.

use archives_core::create::{self, Level, NewFormat, NewItem};
use archives_core::{Child, Dest, Error, Progress};
use std::fs::File;
use std::io;
use std::os::fd::FromRawFd;
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::atomic::Ordering;
use std::sync::{Arc, Once};

uniffi::setup_scaffolding!();

#[derive(Debug, uniffi::Error)]
pub enum ArchiveError {
    NeedPassword,
    WrongPassword,
    Cancelled,
    Failed { why: String },
}

impl std::fmt::Display for ArchiveError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ArchiveError::NeedPassword => f.write_str("This archive is password-protected."),
            ArchiveError::WrongPassword => f.write_str("The password is not correct."),
            ArchiveError::Cancelled => f.write_str("Cancelled."),
            ArchiveError::Failed { why } => f.write_str(why),
        }
    }
}

impl From<Error> for ArchiveError {
    fn from(e: Error) -> Self {
        match e {
            Error::NeedPassword => ArchiveError::NeedPassword,
            Error::WrongPassword => ArchiveError::WrongPassword,
            Error::Cancelled => ArchiveError::Cancelled,
            other => ArchiveError::Failed { why: other.to_string() },
        }
    }
}

fn failed(message: &str) -> ArchiveError {
    ArchiveError::Failed { why: message.to_owned() }
}

/// A panic says nothing (no log): it comes to the app as an error.
fn quiet() {
    static ONCE: Once = Once::new();
    ONCE.call_once(|| std::panic::set_hook(Box::new(|_| {})));
}

#[derive(uniffi::Record)]
pub struct Entry {
    pub name: String,
    pub size: u64,
    pub packed: u64,
    /// Seconds since 1970.
    pub modified: Option<i64>,
    pub is_dir: bool,
    pub is_link: bool,
    pub encrypted: bool,
}

#[derive(uniffi::Record)]
pub struct Listing {
    /// "ZIP", "7-Zip" or "RAR".
    pub format: String,
    pub entries: Vec<Entry>,
}

/// Something in a folder.
#[derive(uniffi::Record)]
pub struct FolderChild {
    pub name: String,
    pub id: String,
    pub is_dir: bool,
}

/// A new file: its id, and a descriptor open for writing, handed over (it's
/// closed here).
#[derive(uniffi::Record)]
pub struct NewFile {
    pub id: String,
    pub fd: i32,
}

/// A folder you chose, through Android's file access. `None`: it couldn't.
#[uniffi::export(with_foreign)]
pub trait Folder: Send + Sync {
    fn list(&self, dir: String) -> Option<Vec<FolderChild>>;
    fn make_folder(&self, dir: String, name: String) -> Option<String>;
    fn new_file(&self, dir: String, name: String) -> Option<NewFile>;
    /// Renamed, never over anything: its new id, or `None` if the name is taken.
    fn rename(&self, dir: String, id: String, name: String) -> Option<String>;
    fn remove(&self, id: String);
    fn remove_folder(&self, id: String);
    fn free_space(&self, dir: String) -> Option<u64>;
}

/// What goes into a new archive: each one opened when its turn comes.
#[uniffi::export(with_foreign)]
pub trait Sources: Send + Sync {
    /// A descriptor open for reading item `index`, handed over; -1 if it can't be.
    fn open(&self, index: u32) -> i32;
}

/// One thing to put in a new archive.
#[derive(uniffi::Record)]
pub struct SourceItem {
    /// Its name inside (`Holiday/Photos/a.jpg`); folders before what's in them.
    pub name: String,
    pub is_dir: bool,
    pub size: u64,
    pub modified: Option<i64>,
}

/// An extraction or an archive being made: how far it's come, and Cancel.
#[derive(uniffi::Object, Default)]
pub struct Job {
    progress: Arc<Progress>,
}

#[uniffi::export]
impl Job {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Job::default())
    }

    pub fn percent(&self) -> u32 {
        self.progress.percent()
    }

    pub fn done(&self) -> u64 {
        self.progress.done.load(Ordering::Relaxed)
    }

    pub fn total(&self) -> u64 {
        self.progress.total.load(Ordering::Relaxed)
    }

    /// The name of what's being written.
    pub fn current(&self) -> String {
        self.progress.current.lock().map(|c| c.clone()).unwrap_or_default()
    }

    pub fn cancel(&self) {
        self.progress.cancel.store(true, Ordering::Relaxed);
    }
}

/// The open file `fd` (the app's: it stays open), as a path to read.
fn by_fd(fd: i32) -> Result<PathBuf, ArchiveError> {
    if fd < 0 {
        return Err(failed("The archive couldn't be opened."));
    }
    // Read in pieces, out of order: a stream (from some apps) can't be.
    // SAFETY: lseek on a descriptor the app keeps open through the call.
    if unsafe { libc::lseek(fd, 0, libc::SEEK_CUR) } < 0 {
        return Err(failed("The app that gave this archive hands it over as a stream, which can't be read this way. Save it to a folder first, then open it from there."));
    }
    Ok(PathBuf::from(format!("/proc/self/fd/{fd}")))
}

/// A descriptor handed over, as a file (closed when dropped).
fn owned(fd: i32) -> io::Result<File> {
    if fd < 0 {
        return Err(io::Error::other("no file"));
    }
    // SAFETY: the app handed this descriptor over; nothing else closes it.
    Ok(unsafe { File::from_raw_fd(fd) })
}

/// The archive in open file `fd` (named `name`): what's in it.
#[uniffi::export]
pub fn list_archive(fd: i32, password: Option<String>) -> Result<Listing, ArchiveError> {
    quiet();
    let path = by_fd(fd)?;
    let l = archives_core::open(&path, password.as_deref())?;
    Ok(Listing {
        format: l.format.name().to_owned(),
        entries: l
            .entries
            .into_iter()
            .map(|e| Entry { name: e.name, size: e.size, packed: e.packed, modified: e.modified, is_dir: e.is_dir, is_link: e.is_link, encrypted: e.encrypted })
            .collect(),
    })
}

/// [`Folder`] as the engine wants it.
struct Docs(Arc<dyn Folder>);

fn refused(what: &str) -> io::Error {
    io::Error::new(io::ErrorKind::PermissionDenied, format!("Android didn't let Archives {what} there."))
}

impl Dest for Docs {
    fn list(&self, dir: &str) -> io::Result<Vec<Child>> {
        let kids = self.0.list(dir.to_owned()).ok_or_else(|| refused("look in a folder"))?;
        Ok(kids.into_iter().map(|c| Child { name: c.name, id: c.id, is_dir: c.is_dir }).collect())
    }
    fn make_folder(&self, dir: &str, name: &str) -> io::Result<String> {
        self.0.make_folder(dir.to_owned(), name.to_owned()).ok_or_else(|| refused("make a folder"))
    }
    fn new_file(&self, dir: &str, name: &str) -> io::Result<(String, File)> {
        let made = self.0.new_file(dir.to_owned(), name.to_owned()).ok_or_else(|| refused("make a file"))?;
        let file = owned(made.fd).inspect_err(|_| self.0.remove(made.id.clone()))?;
        Ok((made.id, file))
    }
    fn rename(&self, dir: &str, id: &str, name: &str) -> io::Result<Option<String>> {
        Ok(self.0.rename(dir.to_owned(), id.to_owned(), name.to_owned()))
    }
    fn remove(&self, id: &str) {
        self.0.remove(id.to_owned());
    }
    fn remove_folder(&self, id: &str) {
        self.0.remove_folder(id.to_owned());
    }
    fn free_space(&self, dir: &str) -> Option<u64> {
        self.0.free_space(dir.to_owned())
    }
}

/// Extracts the archive in open file `fd` (called `name`) into folder
/// `dir`: as one new folder there (`new_folder`, the whole archive), or
/// `which` entries (all, with `None`) straight into it. The line to show.
#[uniffi::export]
#[allow(clippy::too_many_arguments)]
pub fn extract(
    fd: i32,
    name: String,
    password: Option<String>,
    folder: Arc<dyn Folder>,
    dir: String,
    which: Option<Vec<u32>>,
    new_folder: bool,
    job: Arc<Job>,
) -> Result<String, ArchiveError> {
    quiet();
    let path = by_fd(fd)?;
    let dest: Rc<dyn Dest> = Rc::new(Docs(folder));
    if new_folder && which.is_none() {
        let (line, _) = archives_core::extract_into_folder(&path, &name, dest, &dir, password.as_deref(), &job.progress)?;
        return Ok(line);
    }
    let listing = archives_core::open(&path, password.as_deref())?;
    let which: Option<Vec<usize>> = which.map(|w| listing.with_contents(&w.into_iter().map(|i| i as usize).collect::<Vec<_>>()));
    let report = archives_core::extract_to(&path, &listing, dest, &dir, which.as_deref(), password.as_deref(), &job.progress)?;
    Ok(report.summary())
}

#[derive(uniffi::Record)]
pub struct Made {
    pub files: u64,
    pub folders: u64,
    pub bytes: u64,
}

/// Makes an archive of `items` (read through `sources`) into the new, empty
/// file `fd` (the app's: it stays open, and the app names it or removes
/// it). `format` is "zip" or "7z"; `level` "store", "normal" or "maximum".
#[uniffi::export]
pub fn make(
    items: Vec<SourceItem>,
    sources: Arc<dyn Sources>,
    fd: i32,
    format: String,
    level: String,
    password: Option<String>,
    job: Arc<Job>,
) -> Result<Made, ArchiveError> {
    quiet();
    let format = match format.as_str() {
        "zip" => NewFormat::Zip,
        "7z" => NewFormat::SevenZ,
        _ => return Err(failed("Archives makes .zip and .7z.")),
    };
    let level = match level.as_str() {
        "store" => Level::Store,
        "maximum" => Level::Maximum,
        _ => Level::Normal,
    };
    // SAFETY: dup of a descriptor the app keeps open through the call.
    let mine = unsafe { libc::dup(fd) };
    let file = owned(mine).map_err(|_| failed("The new archive couldn't be written."))?;
    let items = items
        .into_iter()
        .enumerate()
        .map(|(i, it)| {
            let sources = sources.clone();
            NewItem {
                name: it.name,
                is_dir: it.is_dir,
                size: it.size,
                modified: it.modified,
                open: Rc::new(move || owned(sources.open(i as u32)).map_err(|_| refused("read a file to add"))),
            }
        })
        .collect();
    let made = create::write(file, items, format, level, password.as_deref(), &job.progress)?;
    Ok(Made { files: made.files as u64, folders: made.folders as u64, bytes: made.bytes })
}

/// `photos.zip` -> `photos`; `Film.part2.rar` -> `Film`.
#[uniffi::export]
pub fn archive_stem(name: String) -> String {
    archives_core::archive_stem(&name).to_owned()
}

/// `1.5 MB`.
#[uniffi::export]
pub fn human_size(bytes: u64) -> String {
    archives_core::human_size(bytes)
}

/// Whether `name` is a program or script (not opened straight from inside
/// an archive; only extracted).
#[uniffi::export]
pub fn is_runnable_name(name: String) -> bool {
    archives_core::is_runnable_name(&name)
}

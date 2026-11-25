//! Where extracted files go. On a computer, a folder ([`Disk`]); on Android,
//! a folder you chose, reached through Android's own file access (the app
//! implements [`Dest`] there). Everything is named by an id: a path here,
//! a document there.

use std::fs::{self, File};
use std::io;
use std::path::{Path, PathBuf};

/// Something in a folder.
#[derive(Clone, Debug)]
pub struct Child {
    pub name: String,
    pub id: String,
    /// A real folder (a link to one is not).
    pub is_dir: bool,
}

pub trait Dest {
    /// What's in folder `dir`.
    fn list(&self, dir: &str) -> io::Result<Vec<Child>>;
    /// A new folder `name` in `dir`: its id.
    fn make_folder(&self, dir: &str, name: &str) -> io::Result<String>;
    /// A new, empty file `name` in `dir`, open for writing (never one that
    /// was there: `AlreadyExists` then).
    fn new_file(&self, dir: &str, name: &str) -> io::Result<(String, File)>;
    /// `id`, in `dir`, renamed `name`, never over anything: its id after,
    /// or `None` if the name is taken.
    fn rename(&self, dir: &str, id: &str, name: &str) -> io::Result<Option<String>>;
    /// A file, removed.
    fn remove(&self, id: &str);
    /// A folder and everything in it, removed.
    fn remove_folder(&self, id: &str);
    /// A symbolic link `name` in `dir` to `target` (already checked to
    /// stay inside). Whether it was made.
    fn link(&self, _dir: &str, _name: &str, _target: &str) -> bool {
        false
    }
    /// The bytes free where `dir` is, if known.
    fn free_space(&self, _dir: &str) -> Option<u64> {
        None
    }
}

/// Folders on this computer: ids are paths.
pub struct Disk;

fn path(id: &str) -> PathBuf {
    PathBuf::from(id)
}

fn id(p: &Path) -> io::Result<String> {
    p.to_str().map(str::to_owned).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "a name that isn't text"))
}

impl Dest for Disk {
    fn list(&self, dir: &str) -> io::Result<Vec<Child>> {
        let mut out = Vec::new();
        for e in fs::read_dir(dir)? {
            let e = e?;
            // A link is not followed: a link to a folder counts as a file.
            let is_dir = e.file_type()?.is_dir();
            out.push(Child { name: e.file_name().to_string_lossy().into_owned(), id: id(&e.path())?, is_dir });
        }
        Ok(out)
    }

    fn make_folder(&self, dir: &str, name: &str) -> io::Result<String> {
        let p = path(dir).join(name);
        fs::create_dir(&p)?;
        id(&p)
    }

    fn new_file(&self, dir: &str, name: &str) -> io::Result<(String, File)> {
        use std::os::unix::fs::OpenOptionsExt;
        let p = path(dir).join(name);
        let f = fs::OpenOptions::new().read(true).write(true).create_new(true).custom_flags(libc::O_NOFOLLOW).mode(0o644).open(&p)?;
        Ok((id(&p)?, f))
    }

    fn rename(&self, dir: &str, from: &str, name: &str) -> io::Result<Option<String>> {
        let to = path(dir).join(name);
        match rename_noreplace(&path(from), &to) {
            Ok(()) => Ok(Some(id(&to)?)),
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => Ok(None),
            Err(e) => Err(e),
        }
    }

    fn remove(&self, id: &str) {
        let _ = fs::remove_file(id);
    }

    fn remove_folder(&self, id: &str) {
        let _ = fs::remove_dir_all(id);
    }

    fn link(&self, dir: &str, name: &str, target: &str) -> bool {
        std::os::unix::fs::symlink(target, path(dir).join(name)).is_ok()
    }

    fn free_space(&self, dir: &str) -> Option<u64> {
        crate::free_space(Path::new(dir))
    }
}

/// Renames `from` to `to`, never over anything there.
pub fn rename_noreplace(from: &Path, to: &Path) -> io::Result<()> {
    use std::os::unix::ffi::OsStrExt;
    let c = |p: &Path| std::ffi::CString::new(p.as_os_str().as_bytes()).map_err(|_| io::Error::from(io::ErrorKind::InvalidInput));
    let (old, new) = (c(from)?, c(to)?);
    // SAFETY: renameat2 reads two NUL-ended paths that live through the call.
    let r = unsafe { libc::syscall(libc::SYS_renameat2, libc::AT_FDCWD, old.as_ptr(), libc::AT_FDCWD, new.as_ptr(), libc::RENAME_NOREPLACE) };
    if r == 0 {
        return Ok(());
    }
    let e = io::Error::last_os_error();
    if e.raw_os_error() == Some(libc::EINVAL) || e.raw_os_error() == Some(libc::ENOSYS) {
        // A filesystem (or kernel) without RENAME_NOREPLACE: checked just before.
        if fs::symlink_metadata(to).is_ok() {
            return Err(io::ErrorKind::AlreadyExists.into());
        }
        return fs::rename(from, to);
    }
    Err(e)
}

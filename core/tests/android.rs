//! As on Android: the archive read through `/proc/self/fd/N` (what the app
//! hands over), and everything written through a [`Dest`] that, like
//! Android's file access, names things by ids of its own, makes no links,
//! and doesn't tell `A.txt` from `a.txt`.

use archives_core::create::{write, Level, NewFormat, NewItem};
use archives_core::{extract_into_folder, extract_to, open, Child, Dest, Error, Progress};
use std::cell::RefCell;
use std::collections::HashMap;
use std::fs::{self, File};
use std::io::{self, Write};
use std::os::fd::AsRawFd;
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::sync::Arc;

fn scratch(tag: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("archives-test-{}-android-{tag}", std::process::id()));
    let _ = fs::remove_dir_all(&d);
    fs::create_dir_all(&d).unwrap();
    d
}

/// Documents with ids ("doc7") that change when renamed, as Android's do.
struct Docs {
    paths: RefCell<HashMap<String, PathBuf>>,
    next: RefCell<u64>,
    calls: RefCell<Vec<String>>,
}

impl Docs {
    fn new(root: &Path) -> Rc<Self> {
        let d = Docs { paths: RefCell::new(HashMap::new()), next: RefCell::new(0), calls: RefCell::new(Vec::new()) };
        d.paths.borrow_mut().insert("root".into(), root.to_path_buf());
        Rc::new(d)
    }
    fn add(&self, p: PathBuf) -> String {
        let mut n = self.next.borrow_mut();
        *n += 1;
        let id = format!("doc{n}");
        self.paths.borrow_mut().insert(id.clone(), p);
        id
    }
    fn path(&self, id: &str) -> io::Result<PathBuf> {
        self.paths.borrow().get(id).cloned().ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "no such document"))
    }
    fn taken(&self, dir: &Path, name: &str) -> bool {
        fs::read_dir(dir).unwrap().flatten().any(|e| e.file_name().to_string_lossy().to_lowercase() == name.to_lowercase())
    }
}

impl Dest for Docs {
    fn list(&self, dir: &str) -> io::Result<Vec<Child>> {
        self.calls.borrow_mut().push(format!("list {dir}"));
        let mut out = Vec::new();
        for e in fs::read_dir(self.path(dir)?)? {
            let e = e?;
            let is_dir = e.file_type()?.is_dir();
            out.push(Child { name: e.file_name().to_string_lossy().into_owned(), id: self.add(e.path()), is_dir });
        }
        Ok(out)
    }
    fn make_folder(&self, dir: &str, name: &str) -> io::Result<String> {
        let p = self.path(dir)?;
        if self.taken(&p, name) {
            return Err(io::ErrorKind::AlreadyExists.into());
        }
        fs::create_dir(p.join(name))?;
        Ok(self.add(p.join(name)))
    }
    fn new_file(&self, dir: &str, name: &str) -> io::Result<(String, File)> {
        let p = self.path(dir)?;
        if self.taken(&p, name) {
            return Err(io::ErrorKind::AlreadyExists.into());
        }
        let f = fs::OpenOptions::new().write(true).create_new(true).open(p.join(name))?;
        Ok((self.add(p.join(name)), f))
    }
    fn rename(&self, dir: &str, id: &str, name: &str) -> io::Result<Option<String>> {
        let (d, from) = (self.path(dir)?, self.path(id)?);
        if self.taken(&d, name) {
            return Ok(None);
        }
        fs::rename(&from, d.join(name))?;
        self.paths.borrow_mut().remove(id);
        Ok(Some(self.add(d.join(name))))
    }
    fn remove(&self, id: &str) {
        if let Ok(p) = self.path(id) {
            let _ = fs::remove_file(p);
        }
    }
    fn remove_folder(&self, id: &str) {
        if let Ok(p) = self.path(id) {
            let _ = fs::remove_dir_all(p);
        }
    }
}

fn zip_of(path: &Path, files: &[(&str, &[u8])]) {
    let mut z = zip::ZipWriter::new(File::create(path).unwrap());
    let o = zip::write::SimpleFileOptions::default();
    for (name, data) in files {
        if name.ends_with('/') {
            z.add_directory(*name, o).unwrap();
        } else {
            z.start_file(*name, o).unwrap();
            z.write_all(data).unwrap();
        }
    }
    z.finish().unwrap();
}

/// The archive's file as the app hands it over: a path to an open file.
fn by_fd(path: &Path) -> (File, PathBuf) {
    let f = File::open(path).unwrap();
    let p = PathBuf::from(format!("/proc/self/fd/{}", f.as_raw_fd()));
    (f, p)
}

#[test]
fn extracts_through_documents() {
    let d = scratch("docs");
    let archive = d.join("in.zip");
    zip_of(&archive, &[("a/", b""), ("a/one.txt", b"1"), ("a/b/two.txt", b"22"), ("top.txt", b"top"), ("link", b"")]);
    let out = d.join("out");
    fs::create_dir(&out).unwrap();
    fs::write(out.join("TOP.txt"), b"mine").unwrap();
    let docs = Docs::new(&out);
    let (_keep, path) = by_fd(&archive);
    let listing = open(&path, None).unwrap();
    let r = extract_to(&path, &listing, docs.clone(), "root", None, None, &Arc::new(Progress::default())).unwrap();
    assert_eq!(fs::read(out.join("a/one.txt")).unwrap(), b"1");
    assert_eq!(fs::read(out.join("a/b/two.txt")).unwrap(), b"22");
    // `top.txt` is `TOP.txt` here: kept as it was.
    assert_eq!(fs::read(out.join("TOP.txt")).unwrap(), b"mine");
    assert!(!out.join("top.txt").exists());
    assert_eq!(r.existing, 1);
    assert_eq!(r.files, 3);
    // Each folder is looked in once, not once a file.
    let lists = docs.calls.borrow().iter().filter(|c| c.starts_with("list")).count();
    assert!(lists <= 4, "{lists} listings");
    // Nothing half-made left.
    assert!(fs::read_dir(out.join("a")).unwrap().flatten().all(|e| !e.file_name().to_string_lossy().starts_with('.')));
    fs::remove_dir_all(&d).unwrap();
}

#[test]
fn links_are_not_made_there() {
    let d = scratch("links");
    let archive = d.join("l.7z");
    {
        // A 7z with a link in it: its target is its contents.
        use sevenz_rust2::{ArchiveEntry, ArchiveWriter};
        let mut w = ArchiveWriter::new(File::create(&archive).unwrap()).unwrap();
        let mut e = ArchiveEntry::new_file("file.txt");
        e.size = 2;
        w.push_archive_entry(e, Some(&b"hi"[..])).unwrap();
        let mut l = ArchiveEntry::new_file("link");
        l.has_windows_attributes = true;
        l.windows_attributes = 0x8000 | ((0o120000 | 0o777) << 16);
        w.push_archive_entry(l, Some(&b"file.txt"[..])).unwrap();
        w.finish().unwrap();
    }
    let out = d.join("out");
    fs::create_dir(&out).unwrap();
    let (_keep, path) = by_fd(&archive);
    let listing = open(&path, None).unwrap();
    let r = extract_to(&path, &listing, Docs::new(&out), "root", None, None, &Arc::new(Progress::default())).unwrap();
    assert_eq!(fs::read(out.join("file.txt")).unwrap(), b"hi");
    assert!(fs::symlink_metadata(out.join("link")).is_err());
    assert_eq!(r.skipped_links, 1);
    fs::remove_dir_all(&d).unwrap();
}

#[test]
fn into_a_new_folder_named_free() {
    let d = scratch("folder");
    let archive = d.join("Holiday.zip");
    zip_of(&archive, &[("a.jpg", b"a"), ("b.jpg", b"b")]);
    let out = d.join("out");
    fs::create_dir_all(out.join("holiday")).unwrap();
    let (_keep, path) = by_fd(&archive);
    let p = Arc::new(Progress::default());
    let (line, name) = extract_into_folder(&path, "Holiday.zip", Docs::new(&out), "root", None, &p).unwrap();
    assert_eq!(name, "Holiday (2)");
    assert!(line.contains("Holiday (2)"), "{line}");
    assert_eq!(fs::read(out.join("Holiday (2)/b.jpg")).unwrap(), b"b");
    // Failing (wrong password here: none asked) leaves nothing behind.
    let secret = d.join("secret.zip");
    {
        let mut z = zip::ZipWriter::new(File::create(&secret).unwrap());
        let o = zip::write::SimpleFileOptions::default().with_aes_encryption(zip::AesMode::Aes256, "pw");
        z.start_file("s.txt", o).unwrap();
        z.write_all(b"s").unwrap();
        z.finish().unwrap();
    }
    let (_keep2, spath) = by_fd(&secret);
    assert_eq!(extract_into_folder(&spath, "secret.zip", Docs::new(&out), "root", None, &p).unwrap_err(), Error::NeedPassword);
    assert_eq!(extract_into_folder(&spath, "secret.zip", Docs::new(&out), "root", Some("no"), &p).unwrap_err(), Error::WrongPassword);
    let (_, n) = extract_into_folder(&spath, "secret.zip", Docs::new(&out), "root", Some("pw"), &p).unwrap();
    assert_eq!(fs::read(out.join(n).join("s.txt")).unwrap(), b"s");
    let mut left: Vec<String> = fs::read_dir(&out).unwrap().flatten().map(|e| e.file_name().to_string_lossy().into_owned()).collect();
    left.sort();
    assert_eq!(left, ["Holiday (2)", "holiday", "secret"]);
    fs::remove_dir_all(&d).unwrap();
}

#[test]
fn makes_archives_from_anywhere() {
    let d = scratch("make");
    let data: Vec<(&str, Vec<u8>)> = vec![("Trip/a.txt", b"alpha".to_vec()), ("Trip/sub/b.bin", vec![7u8; 100_000])];
    for (format, password) in [(NewFormat::Zip, None), (NewFormat::SevenZ, None), (NewFormat::Zip, Some("pw")), (NewFormat::SevenZ, Some("pw"))] {
        let items = || {
            let mut v = vec![
                NewItem { name: "Trip".into(), is_dir: true, size: 0, modified: None, open: Rc::new(|| Err(io::ErrorKind::Other.into())) },
                NewItem { name: "Trip/sub".into(), is_dir: true, size: 0, modified: None, open: Rc::new(|| Err(io::ErrorKind::Other.into())) },
            ];
            for (name, bytes) in &data {
                let src = d.join(name.replace('/', "_"));
                fs::write(&src, bytes).unwrap();
                v.push(NewItem {
                    name: (*name).into(),
                    is_dir: false,
                    size: bytes.len() as u64,
                    modified: Some(1_700_000_000),
                    open: Rc::new(move || File::open(&src)),
                });
            }
            v
        };
        let made = d.join(format!("made.{}", format.ext()));
        let _ = fs::remove_file(&made);
        let p = Arc::new(Progress::default());
        let m = write(File::create(&made).unwrap(), items(), format, Level::Normal, password, &p).unwrap();
        assert_eq!((m.files, m.folders), (2, 2));
        assert_eq!(p.percent(), 100);
        let (_keep, path) = by_fd(&made);
        let listing = open(&path, password).unwrap();
        let out = d.join(format!("out-{}-{}", format.ext(), password.is_some()));
        fs::create_dir(&out).unwrap();
        let r = extract_to(&path, &listing, Docs::new(&out), "root", None, password, &p).unwrap();
        assert_eq!(r.files, 2);
        assert_eq!(fs::read(out.join("Trip/a.txt")).unwrap(), b"alpha");
        assert_eq!(fs::read(out.join("Trip/sub/b.bin")).unwrap(), vec![7u8; 100_000]);
    }
    // Cancelled part way: an error, said so.
    let p = Arc::new(Progress::default());
    p.cancel.store(true, std::sync::atomic::Ordering::Relaxed);
    let src = d.join("big");
    fs::write(&src, vec![1u8; 10_000]).unwrap();
    let one = vec![NewItem { name: "big".into(), is_dir: false, size: 10_000, modified: None, open: Rc::new(move || File::open(&src)) }];
    let r = write(File::create(d.join("c.zip")).unwrap(), one, NewFormat::Zip, Level::Normal, None, &p);
    assert_eq!(r.unwrap_err(), Error::Cancelled);
    fs::remove_dir_all(&d).unwrap();
}

#[test]
fn reads_what_it_was_handed_without_opening_it_again() {
    use std::os::unix::fs::PermissionsExt;
    let d = scratch("held");
    for (name, make) in [("a.zip", 0), ("a.7z", 1)] {
        let archive = d.join(name);
        if make == 0 {
            zip_of(&archive, &[("x.txt", b"held")]);
        } else {
            let mut w = sevenz_rust2::ArchiveWriter::new(File::create(&archive).unwrap()).unwrap();
            let mut e = sevenz_rust2::ArchiveEntry::new_file("x.txt");
            e.size = 4;
            w.push_archive_entry(e, Some(&b"held"[..])).unwrap();
            w.finish().unwrap();
        }
        let (keep, path) = by_fd(&archive);
        // Nobody may open it now (as on Android, where only the descriptor
        // handed over can read it); what's open stays readable.
        fs::set_permissions(&archive, fs::Permissions::from_mode(0o000)).unwrap();
        let can_reopen = File::open(&archive).is_ok();
        let listing = open(&path, None).unwrap();
        assert_eq!(listing.entries.len(), 1);
        let out = d.join(format!("out-{name}"));
        fs::create_dir(&out).unwrap();
        // Twice: the copy of the descriptor starts from the top each time.
        for _ in 0..2 {
            let _ = fs::remove_file(out.join("x.txt"));
            extract_to(&path, &listing, Docs::new(&out), "root", None, None, &Arc::new(Progress::default())).unwrap();
            assert_eq!(fs::read(out.join("x.txt")).unwrap(), b"held");
        }
        fs::set_permissions(&archive, fs::Permissions::from_mode(0o644)).unwrap();
        drop(keep);
        if can_reopen {
            eprintln!("running as root: opening again isn't refused here");
        }
    }
    fs::remove_dir_all(&d).unwrap();
}

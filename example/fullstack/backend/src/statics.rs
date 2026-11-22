//! The page's files, from a fixed allowlist, read once at start.
//!
//! A request names a file only by matching one entry of `ALLOWED` exactly:
//! no path is ever joined from request text, so traversal, other files in
//! the directory and files written after start are never served.

use std::collections::HashMap;

/// Every file the page is made of (`bb fullstack` builds exactly these into
/// frontend/dist), with its content type.
pub const ALLOWED: &[(&str, &str)] = &[
    ("index.html", "text/html; charset=utf-8"),
    ("app.js", "text/javascript"),
    ("autonomous.js", "text/javascript"),
    ("elm.js", "text/javascript"),
    ("style.css", "text/css"),
];

pub struct Statics {
    files: HashMap<&'static str, (&'static str, Vec<u8>)>,
}

impl Statics {
    /// Read every allowed file present in `dir`. Answers the files and the
    /// names that were missing.
    pub fn load(dir: &str) -> (Statics, Vec<&'static str>) {
        let mut files = HashMap::new();
        let mut missing = Vec::new();
        for (name, mime) in ALLOWED {
            match std::fs::read(format!("{dir}/{name}")) {
                Ok(bytes) => {
                    files.insert(*name, (*mime, bytes));
                }
                Err(_) => missing.push(*name),
            }
        }
        (Statics { files }, missing)
    }

    /// The content type and bytes for request path `path` ("/" is
    /// index.html), or None for anything not on the allowlist.
    pub fn get(&self, path: &str) -> Option<(&'static str, &[u8])> {
        let name = if path == "/" {
            "index.html"
        } else {
            path.strip_prefix('/')?
        };
        self.files.get(name).map(|(mime, b)| (*mime, b.as_slice()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dir_with(files: &[(&str, &str)]) -> String {
        let d = std::env::temp_dir().join(format!(
            "todo-statics-{}-{:?}",
            std::process::id(),
            std::thread::current().id()
        ));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        for (n, body) in files {
            std::fs::write(d.join(n), body).unwrap();
        }
        d.to_string_lossy().into_owned()
    }

    #[test]
    fn serves_allowed_files_only_by_exact_name() {
        let d = dir_with(&[
            ("index.html", "<html>"),
            ("app.js", "js"),
            ("secret.txt", "no"),
            ("notes.js", "no"),
        ]);
        let (s, missing) = Statics::load(&d);
        assert_eq!(s.get("/"), Some(("text/html; charset=utf-8", &b"<html>"[..])));
        assert_eq!(s.get("/index.html").map(|f| f.1), Some(&b"<html>"[..]));
        assert_eq!(s.get("/app.js"), Some(("text/javascript", &b"js"[..])));
        assert_eq!(s.get("/secret.txt"), None, "a file outside the allowlist");
        assert_eq!(s.get("/notes.js"), None, "an extension alone admits nothing");
        assert_eq!(s.get("app.js"), None, "a path must start with /");
        assert_eq!(s.get("//app.js"), None);
        assert_eq!(s.get("/../index.html"), None);
        assert_eq!(s.get("/./app.js"), None);
        assert_eq!(s.get("/%2e%2e/etc/passwd"), None);
        assert_eq!(missing, vec!["autonomous.js", "elm.js", "style.css"]);
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn a_file_written_after_start_is_not_served() {
        let d = dir_with(&[]);
        let (s, _) = Statics::load(&d);
        std::fs::write(format!("{d}/style.css"), "late").unwrap();
        assert_eq!(s.get("/style.css"), None);
        let _ = std::fs::remove_dir_all(&d);
    }
}

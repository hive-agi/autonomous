//! The `/healthz` answer of the backend.

use std::time::Duration;

use autonomous::{json, Value};

/// Status and body for a bridge silent for `silent`: 503 once that is past
/// `max_silent`. The runtime redials long before; this is the backstop
/// that lets an orchestrator restart a stuck process.
pub fn health(silent: Duration, max_silent: Duration) -> (u16, Value) {
    let up = silent <= max_silent;
    let body = json!({"bridge": if up { "up" } else { "silent" }, "silentSecs": silent.as_secs()});
    (if up { 200 } else { 503 }, body)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn health_turns_503_only_past_the_silence_limit() {
        let limit = Duration::from_secs(180);
        assert_eq!(health(Duration::from_secs(30), limit).0, 200);
        assert_eq!(health(limit, limit).0, 200);
        let (code, body) = health(Duration::from_secs(181), limit);
        assert_eq!(code, 503);
        assert_eq!(body["bridge"], "silent");
    }
}

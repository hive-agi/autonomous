//! Liveness of the link to the bridge: ping it when the link is quiet, and
//! call the link dead when nothing arrives for too long. A half-open socket
//! (the peer gone without a close) never fails a read; only this notices it.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

/// When a frame last arrived from the bridge, readable from any thread
/// (a health endpoint, a metrics exporter). A new `Link` counts as heard
/// now, so a process gets the same grace at start as after a frame.
#[derive(Clone, Debug)]
pub struct Link(Arc<AtomicU64>);

impl Default for Link {
    fn default() -> Self {
        let l = Link(Arc::new(AtomicU64::new(0)));
        l.heard();
        l
    }
}

impl Link {
    fn now_ms() -> u64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0)
    }

    /// A frame arrived now.
    pub fn heard(&self) {
        self.0.store(Self::now_ms(), Ordering::Relaxed);
    }

    /// How long since the last frame from the bridge.
    pub fn silent_for(&self) -> Duration {
        Duration::from_millis(Self::now_ms().saturating_sub(self.0.load(Ordering::Relaxed)))
    }
}

/// Ping after this long without a frame either way.
pub const DEFAULT_PING_EVERY: Duration = Duration::from_secs(20);

/// Call the link dead after this long without a frame from the bridge.
pub const DEFAULT_IDLE_TIMEOUT: Duration = Duration::from_secs(60);

/// What the link needs now.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Due {
    /// Nothing to do.
    Idle,
    /// Quiet for `ping_every`: send a ping.
    Ping,
    /// Nothing heard for `idle_timeout`: the link is dead, redial.
    Dead,
}

/// When a link last heard from the bridge and last pinged it.
#[derive(Clone, Copy, Debug)]
pub struct Liveness {
    ping_every: Duration,
    idle_timeout: Duration,
    last_heard: Instant,
    last_ping: Instant,
}

impl Liveness {
    /// A link opened at `now`.
    pub fn new(now: Instant, ping_every: Duration, idle_timeout: Duration) -> Self {
        Liveness {
            ping_every,
            idle_timeout,
            last_heard: now,
            last_ping: now,
        }
    }

    /// A frame (text, ping, pong, any) arrived at `now`.
    pub fn heard(&mut self, now: Instant) {
        self.last_heard = now;
    }

    /// A ping was sent at `now`.
    pub fn pinged(&mut self, now: Instant) {
        self.last_ping = now;
    }

    /// How long the bridge has been silent at `now`.
    pub fn silent_for(&self, now: Instant) -> Duration {
        now.saturating_duration_since(self.last_heard)
    }

    /// What the link needs at `now`.
    pub fn due(&self, now: Instant) -> Due {
        if self.silent_for(now) >= self.idle_timeout {
            Due::Dead
        } else if now.saturating_duration_since(self.last_heard.max(self.last_ping))
            >= self.ping_every
        {
            Due::Ping
        } else {
            Due::Idle
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const S: Duration = Duration::from_secs(1);

    fn live(t0: Instant) -> Liveness {
        Liveness::new(t0, 20 * S, 60 * S)
    }

    #[test]
    fn a_fresh_link_is_idle() {
        let t0 = Instant::now();
        assert_eq!(live(t0).due(t0), Due::Idle);
        assert_eq!(live(t0).due(t0 + 19 * S), Due::Idle);
    }

    #[test]
    fn a_quiet_link_is_pinged_once_per_interval() {
        let t0 = Instant::now();
        let mut l = live(t0);
        assert_eq!(l.due(t0 + 20 * S), Due::Ping);
        l.pinged(t0 + 20 * S);
        assert_eq!(l.due(t0 + 39 * S), Due::Idle);
        assert_eq!(l.due(t0 + 40 * S), Due::Ping);
    }

    #[test]
    fn a_silent_link_is_dead_even_when_pinged() {
        let t0 = Instant::now();
        let mut l = live(t0);
        l.pinged(t0 + 20 * S);
        l.pinged(t0 + 40 * S);
        assert_eq!(l.due(t0 + 60 * S), Due::Dead);
        assert_eq!(l.silent_for(t0 + 60 * S), 60 * S);
    }

    #[test]
    fn hearing_anything_keeps_the_link_alive() {
        let t0 = Instant::now();
        let mut l = live(t0);
        l.heard(t0 + 50 * S);
        assert_eq!(l.due(t0 + 60 * S), Due::Idle);
        assert_eq!(l.due(t0 + 70 * S), Due::Ping);
        assert_eq!(l.due(t0 + 110 * S), Due::Dead);
    }
}

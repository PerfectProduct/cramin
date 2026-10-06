# Updater recovery contract

Retry remembers the failed action in the current process: check, download, or install a verified APK.
One operation slot coalesces repeated taps and lifecycle callbacks. A cancelled download finishes its
cleanup before another operation may start. UI uses localized failure categories; technical details
remain in UpdateUi.Error.diagnostic and the safe debug Log sink.

The pending record contains only the APK basename, SHA-256, intent and installer session ID.
No endpoint, credentials or material are persisted here. Each load verifies the original hash.

| Durable phase | ON_RESUME / process restoration | Explicit Retry |
|---|---|---|
| PREPARED | Validate and continue | Continue |
| PERMISSION | Continue only when unknown sources is allowed; otherwise show permission gate | Recheck permission |
| SUBMITTED | Show interrupted attempt; never submit another session automatically | Invalidate old callback ID, abandon old session, validate and submit |
| CANCELLED | Show cancellation; no submission | Validate and submit a new attempt |
| FAILED / legacy RETRY | Show failure/interruption; no submission | Validate and submit a new attempt |

The result receiver persists CANCELLED/FAILED before emitting the UI event, even if the previous
process/manager is absent. Success clears the record. Session IDs reject late callbacks from an
abandoned attempt. Result events do not replay stale cancellation into a fresh manager.

A restored SUBMITTED record is deliberately conservative: a new process cannot infer whether a
system confirmation was shown or lost. It requires explicit retry rather than creating a duplicate.
Preparation and permission recovery remain automatic. A failed check/download has no valid pending
APK: a new process starts idle and allows a fresh check; it does not start network requests implicitly.

Tests: UpdateRecoveryTest, PendingUpdateStoreTest, UpdateResumeTest, UpdateSectionRecoveryTest.
The latter uses offline CI fixtures; local acceptance additionally uses signed APKs, a test-only HTTPS
endpoint and the real PackageInstaller on API26/36. No test endpoint is configurable in production.

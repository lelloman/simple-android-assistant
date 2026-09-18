# Optional assistant diagnostics

Use `DiagnosticRecorder` with `FileDiagnosticStorage` in the host's private
`noBackupFilesDir`. Pass the same recorder to `ChatRepositoryImpl` and
`AccountChatRepository`, with a preference callback for explicit local recording
consent (off by default). Account identity must combine server and user. The wrapper
invalidates recordings at account transitions/logout; clearing history clears the
diagnostic copy. Host UI must clear unsent previews too.

Recordings are separate from model context/history. The compact UTF-8 file,
including its hashed ownership wrapper and schema metadata, never exceeds 2 MiB.
Old whole turns are evicted, large content/events are explicitly truncated, and
structured credentials/recognizable secrets are redacted before persistence.
Redaction is best effort: never promise anonymity or capture hidden reasoning.

Streaming responses checkpoint at most twice per second; recovery marks unfinished
turns interrupted. Tool inputs/results, errors, cancellation and in-turn compaction
are captured. Hosts record confirmation decisions using `activeTurn()` captured
before waiting for the user's decision, then `event(token, "confirmation", ...)`.
Host version metadata can be supplied to the recorder constructor.

`snapshot()` returns immutable bytes for the most recent conversation, with broader
retained context only via `snapshot(includeAll = true)`. Preview exactly `content`,
obtain separate explicit attachment consent, and check `isCurrent(snapshot)` before
upload. Bind the upload to that same server/account. Do not log or background-upload
snapshots. Local clear does not delete reports already submitted to a server.

Storage errors disable collection and invalidate tokens/snapshots, without changing
chat behavior. If disk deletion itself fails, the host should treat recording as
unavailable; application-private data may still need OS-level removal. Diagnostic
deletion is not a guarantee of forensic secure erasure.

Ticket: [LLPR/PEZZOTTIFY-20](https://crumbles.lelloman.com/w/LLPR/PEZZOTTIFY/20)

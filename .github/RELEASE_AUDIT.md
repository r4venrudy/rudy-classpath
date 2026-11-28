# Release archive audits

Verify one archive or a complete staging directory without extracting payloads.

```sh
python .github/scripts/release_guard.py dist
python .github/scripts/release_guard.py dist/package.zip
```

Directory discovery is bounded. Existing archive-format and checksum contracts still apply.

Use `--json` for schema `rudy.release.archive-report/v1`. Reports contain basenames, member counts and decompressed sizes. Output appears only after every selected archive passes.

`--max-archive-bytes N` lowers `MAX_ARCHIVE` for this invocation. Values must be positive and cannot exceed the built-in ceiling. The original limit is restored after success or error.

`--max-total-bytes N` lowers `MAX_TOTAL` for this invocation. Values must be positive and cannot exceed the built-in ceiling. The original limit is restored after success or error.

`--max-member-bytes N` lowers `MAX_MEMBER` for this invocation. Values must be positive and cannot exceed the built-in ceiling. The original limit is restored after success or error.

`--max-entries N` lowers `MAX_ENTRIES` for this invocation. Values must be positive and cannot exceed the built-in ceiling. The original limit is restored after success or error.

Byte limits apply per archive: `--max-member-bytes` caps a member and `--max-total-bytes` caps its decompressed sum. Limits do not promise an exact process-memory bound.

```sh
python .github/scripts/release_guard.py dist --json --max-archive-bytes 67108864 --max-total-bytes 268435456 --max-member-bytes 33554432 --max-entries 10000
```

## Checksum receipts

`release_checksums.py` accepts the staging directory or its canonical regular `SHA256SUMS` / `SHA256SUMS.txt` path. The full staging inventory is still verified; choosing a manifest never permits unlisted archives or a second manifest.

`--json` emits `rudy.release.checksum-report/v1`: manifest digest, sorted archive names, byte sizes and verified SHA-256 values. It emits a receipt only after the complete inventory passes.

`--max-asset-bytes N` and `--max-total-bytes N` lower the per-archive and aggregate hashing ceilings. Positive values cannot exceed built-in caps, apply only to this invocation, and are restored after success or failure.

`--verify-archives` runs the existing content/framing/path validator after checksum verification. JSON rows then include entry counts and decompressed sizes. This checks both contracts sequentially; hold staging files immutable during the command.

`--output PATH` saves the JSON receipt after validation, creates a new UTF-8 file exclusively and preserves any existing file. Nothing is printed on success. Choose a path outside the staging directory when you want the directory inventory to remain unchanged. Failed validation creates no report. Output I/O errors fail the command.

`--manifest-sha256 HEX` pins the digest of the exact bytes parsed as the manifest, before any release payload is read. Obtain that digest through an independently trusted channel; an untrusted manifest plus untrusted digest establishes no authenticity. The reported manifest digest includes its original line endings.

```sh
python .github/scripts/release_checksums.py dist/SHA256SUMS.txt --json --verify-archives
python .github/scripts/release_checksums.py dist --output audit.json --max-total-bytes 268435456
```

## Metadata and compression policies

ZIP `--max-entries` is checked against the footer before standard ZIP directory-object allocation. The built-in ZIP parser ceiling still applies.

`--max-path-metadata-bytes N` lowers the work budget for distinct normalized path prefixes and spelling metadata. This bounds charged indexing work, not exact allocator memory or RSS.

`--max-gzip-streams N` limits concatenated gzip members, including empty members. The default ceiling remains 4096. Codec headers, footers and total decoded bytes are still checked.

`--max-xz-streams N` limits concatenated XZ streams, including empty streams. The default ceiling remains 4096; the decoder memory cap and stream-padding profile continue to apply.

`--max-tar-extension-bytes N`, `--max-tar-extension-total N` and `--max-tar-extension-depth N` lower individual extension payload, aggregate padded metadata and recursive extension ceilings. Ordinary file payload limits remain separate.

`--max-zip-directory-bytes N` lowers the ZIP central-directory byte limit before standard directory-object allocation. Per-invocation limits must be positive, can only lower built-in ceilings and are restored after success or failure. These overrides are for the command interface; hold inputs immutable during the audit.

```sh
python .github/scripts/release_guard.py dist --json --max-entries 5000 --max-gzip-streams 32 --max-xz-streams 16 --max-path-metadata-bytes 8388608 --max-tar-extension-depth 8 --max-zip-directory-bytes 8388608
```

`--max-envelope-bytes N` caps the entire decoded gzip/XZ TAR envelope, including metadata records, alignment padding and end records. It does not replace per-file or aggregate payload ceilings. The effective ceiling is the lower of this value and the existing payload-plus-overhead bound. ZIP inputs do not have a TAR envelope.

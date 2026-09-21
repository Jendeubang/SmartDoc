# SmartDoc P0 operations

## Existing installation upgrade

1. Back up the current MySQL and MinIO data.
2. Export `MYSQL_ROOT_PASSWORD` in the current shell.
3. Run `powershell -File deploy/scripts/migrate.ps1`.
4. Rebuild and restart the affected services.

The migration runner records each successful file in `smartdoc_schema_history`.
It never deletes existing user documents.

## New installation administrator

No predictable user is created by SQL. For an empty database, set all four
`SMARTDOC_BOOTSTRAP_ADMIN_*` variables to a unique username, 14+ character strong
password, email and phone. Start `user-service` once, verify login, remove all four
variables, and restart it.

## Malware scanning

Set `SECURITY_MALWARE_SCAN_ENABLED=true` and
`SECURITY_MALWARE_SCAN_REQUIRED=true` in production. ClamAV must report healthy
before `file-service` starts. Signature updates require outbound access from the
ClamAV container.

## Backup and restore drill

Run `deploy/scripts/backup.ps1 -Destination <absolute backup directory>` daily
through Windows Task Scheduler or the host scheduler. Copy backups to encrypted
off-host storage. Run `restore-drill.ps1` at least monthly and record the result.

## Production deployment

Use both Compose files and supply `SMARTDOC_DOMAIN`, `TLS_CERT_DIR`,
`JWT_KEYS_SECRET_FILE`, and `INTERNAL_SERVICE_TOKEN_SECRET_FILE`. Only ports 80 and
443 should be reachable externally. Rotate JWT keys and internal service tokens
according to `SECURITY_DEPLOYMENT.md`.

## Release

Release creation intentionally requires a clean Git worktree and a signed tag.
Review and commit the current changes, then run
`deploy/scripts/prepare-release.ps1 -Version vX.Y.Z`. Push the tag only after a
human release review.

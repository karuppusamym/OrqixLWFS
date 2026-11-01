Default BACKUP_DIR. One folder per backup set (scripts/backup.sh), each with SHA256SUMS.
Backup sets contain every secret (.env) and all business data: keep this on an encrypted disk inside the jurisdiction
and copy it off the VM. Nothing in this folder is committed (.gitignore).

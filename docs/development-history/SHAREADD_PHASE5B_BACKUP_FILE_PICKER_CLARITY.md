# ShareAdd Phase 5B — Backup File Picker Clarity

## Goal
Clarify that ShareAdd uses Android's file picker for backup export/import, allowing the user to choose Google Drive manually without Google Drive API, sync, OAuth, or Google Cloud setup.

## What changed
- Renamed backup drawer actions to clearer wording:
  - Export Backup File
  - Import Backup File
  - تصدير نسخة احتياطية
  - استيراد نسخة احتياطية
- Added a small drawer note explaining that Google Drive can be selected from Android's file picker.
- Improved export success/failure messages.
- Added an import warning dialog before opening the file picker.

## Important behavior note
This phase does not add import preview or duplicate detection yet.
Current import behavior still replaces the current places list after the user confirms and chooses a file.

## Not changed
- No Google Drive API.
- No sync.
- No OAuth.
- No Google Sign-In.
- No database change.
- No storage format change.
- No import merge/preview yet.

## Test checklist
1. Open the app.
2. Open the drawer.
3. Confirm the backup section text is clear in English and Arabic.
4. Tap Export Backup File and confirm Android file picker opens.
5. Verify Google Drive can be selected from the Android picker if installed/signed in.
6. Tap Import Backup File and confirm the warning dialog appears.
7. Cancel the import and verify nothing changes.
8. Use a test backup file only if importing.
9. Test dark/light mode.
10. Test Arabic/English mode.

## Next phase
Phase 6 — Import Safety Preview:
- Read the backup file first.
- Show number of places.
- Detect duplicates.
- Offer Skip / Merge / Replace.
- Confirm before applying changes.

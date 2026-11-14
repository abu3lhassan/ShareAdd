# ShareAdd v2 — Phase 6: Import Safety Preview

## Goal
Make backup import safer before it changes any saved places.

## What changed
- Import no longer applies immediately after choosing a file.
- After choosing a backup JSON file, ShareAdd shows an Import Preview first.
- Preview shows:
  - Current places count
  - Places inside the backup file
  - Possible duplicate count
  - Image count in the backup file
- User must choose one import method:
  - Safe merge: skip duplicates
  - Merge all
  - Replace all
- Replace all now shows a clear warning before confirmation.
- No changes are applied until the user taps Confirm Import.

## Google Drive behavior
No Google Drive API was added.

The app still uses Android's file picker. This means the user can choose Google Drive manually when exporting or importing backup files.

## What was not changed
- No Google Drive API
- No OAuth
- No sync
- No cloud database
- No package name change
- No storage format change
- No architecture refactor

## Test checklist
1. Open ShareAdd.
2. Create 2 or 3 test places.
3. Export a backup file.
4. Import the same backup file.
5. Confirm the Import Preview appears before data changes.
6. Check duplicate count.
7. Test Safe merge: skip duplicates.
8. Test Merge all only with test data.
9. Test Replace all only with test data.
10. Test Arabic and English.
11. Test dark and light mode.

## Important testing note
Use test data only when trying Replace all.

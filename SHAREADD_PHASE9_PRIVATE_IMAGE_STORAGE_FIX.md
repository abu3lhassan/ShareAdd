# ShareAdd Phase 9 — Private Image Storage Fix

## Problem
The previous version stored selected image references from the device gallery. If the user deleted the original photo from the gallery, ShareAdd could still show the place data, but sharing with images could fail because the saved image URI no longer existed.

## Fix
Selected images are now copied into ShareAdd's private app storage when the user chooses them.

This means:
- ShareAdd no longer depends on the original gallery photo after selection.
- The image copy is hidden from the public gallery.
- Deleting the original photo from the phone gallery should not break ShareAdd sharing.
- Sharing with images now uses Android FileProvider to safely share the private image copy.
- If an old broken image reference exists from a previous version, ShareAdd falls back to sharing text only instead of failing.

## What changed
- Added private image copy helper.
- Added private image reader helper.
- Added FileProvider for safe sharing.
- Added `res/xml/shareadd_file_paths.xml`.
- Updated image export/import to use private app storage.
- Updated sharing with images to ignore unavailable/broken images gracefully.

## What did not change
- No database change.
- No backup format breaking change.
- No Google Drive API.
- No sync.
- No package name change.
- No major refactor.

## Test checklist
1. Add a new place with an image from the gallery.
2. Save the place.
3. Delete the original image from the gallery.
4. Open ShareAdd again.
5. Share the place with images.
6. Confirm sharing works.
7. Export backup.
8. Import backup into a test install.
9. Confirm restored images are still private and shareable.

## Important note
Old places that already have broken gallery image links may not be recoverable if the original photo was deleted before this fix. New images added after this version should be safe.

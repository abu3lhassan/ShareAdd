# ShareAdd Phase 10 — Image Migration Fix

## Purpose
Fix legacy image references from older ShareAdd builds.

## Problem
Older builds could keep image references that depended on Gallery/album files. If the user deleted or moved images from the visible ShareAdd album, images could disappear from the app and Share With Images could fail.

## Fix
- On app startup, ShareAdd checks existing saved places.
- Any image reference that is not already inside ShareAdd private storage is copied into the app private image folder.
- Saved places are updated to point to the private app copy.
- New images continue to be copied privately when selected.

## Important note
If an old Gallery/album image was already deleted before this migration runs, ShareAdd cannot recover that image unless it exists in a backup or phone trash/recycle bin. Restore the image first, open ShareAdd v2.0.2 once, then test deleting/moving the old album image again.

## Test
1. Restore old album images if they were deleted.
2. Install/open ShareAdd v2.0.2.
3. Wait for the app to open normally.
4. Delete or move one old visible album image.
5. Open the related place in ShareAdd.
6. Test Share With Images.
7. Add a new place with a new image and confirm it remains stable.

# ShareAdd Phase 10 - Image Migration Preview Fix

## Purpose
Fix the image migration implementation so private app-stored images can still be displayed inside ShareAdd.

## Fixes
- Moves the image migration effect after `message` initialization.
- Updates `ImagePreview` to read images through `openShareAddImageInputStream`.
- Supports both private file paths and legacy Android content URIs.
- Keeps FileProvider support for sharing private images.

## Important testing
1. Restore one old ShareAdd album image if it was deleted.
2. Open the app once and confirm the old image appears.
3. Add a new location with a new image.
4. Confirm the image preview appears inside the app.
5. Move/delete the original gallery image.
6. Confirm ShareAdd still previews and shares the image.
7. Test Share With Images.
8. Test Export Backup.

## Notes
If an old image was permanently deleted before migration, ShareAdd cannot recover it unless it exists in Trash/Recently Deleted or in a backup.

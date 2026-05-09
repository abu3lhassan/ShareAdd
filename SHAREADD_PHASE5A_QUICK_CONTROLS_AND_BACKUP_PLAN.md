# ShareAdd v2 — Phase 5A

## Premium Quick Controls + Google Drive Backup Direction

### What changed in this build

This build makes one safe UI polish change:

- Language and dark/light controls are now grouped into one compact premium pill/card.
- The menu button remains separate and easy to reach.
- No data storage, import/export, sharing, or backup logic was changed.

### Why this change

Before this change, language and theme controls felt visually separate and slightly scattered. Grouping them makes the top area cleaner and more premium while keeping both actions fast.

### Google Drive backup note

The current app uses Android's document picker/export flow. This can already save to Google Drive if the user chooses Google Drive as the save location.

A true automatic Google Drive backup is a separate feature and should be implemented later because it requires:

- Google Cloud project setup.
- OAuth consent configuration.
- SHA-1 / signing certificate setup.
- Google Drive API dependency.
- Sign-in and permission handling.
- Backup conflict rules.
- Restore testing.

Recommended direction:

1. First improve the existing backup/export labels and UX.
2. Add last backup date and clear success messages.
3. Add import preview and duplicate safety.
4. Only after data safety is strong, add optional true Google Drive backup.

### Test checklist

- Open app.
- Confirm language + theme appear in one compact control.
- Toggle Arabic/English.
- Toggle dark/light.
- Open drawer.
- Add/edit/share a test place.
- Do not test import on important personal data yet.

### Files changed

- `app/src/main/java/com/example/shareadd/MainActivity.kt`

### Risk level

Low. UI-only polish.

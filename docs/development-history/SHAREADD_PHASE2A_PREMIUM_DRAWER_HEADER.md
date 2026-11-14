# ShareAdd v2 — Phase 2A Premium Drawer + Header Polish

## Scope
This ZIP includes a safe UI polish pass only.

## Changes included

### 1. Fixed About dialog Arabic title and close button
- Arabic title now centers correctly as: عن ShareAdd.
- Dialog close button is centered in both Arabic and English.

### 2. Premium drawer polish
- Added a more polished drawer header card.
- Improved ShareAdd tagline inside drawer.
- Grouped drawer actions into clearer sections:
  - Menu
  - Backup & Data
- Made drawer buttons more card-like and easier to scan.
- Added subtle internal credit at the bottom of the drawer.

### 3. Header polish
- Updated the main hero card tagline.
- Removed repeated developer credit from the main hero area to reduce clutter.
- Kept Ali's credit in About and the drawer.
- Improved chip text from address-focused wording to saved-places wording.

### 4. Top controls polish
- Simplified top bar layout.
- Menu button stays on one consistent side.
- Language and light/dark controls remain accessible.

## Not changed
- No data model changes.
- No import/export logic changes.
- No storage changes.
- No sharing text changes.
- No app package/id changes.
- No architecture split yet.

## Test checklist in Android Studio
1. Open project folder: ShareAdd.
2. Let Gradle sync/reload.
3. Run app.
4. Test About dialog in English.
5. Test About dialog in Arabic.
6. Open drawer and review layout.
7. Toggle dark/light mode.
8. Toggle Arabic/English.
9. Add a test place.
10. Share a test place.
11. Export only; avoid import on important data for now.

## Next recommended phase
Phase 2B — Place card visual simplification.

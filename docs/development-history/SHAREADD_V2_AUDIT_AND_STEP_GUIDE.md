# ShareAdd v2 — Audit + Step-by-Step Development Guide

## Current rule
This project is a polish-and-evolution project, not a rewrite. The current app works and must be protected.

## Current confirmed technical state
- Native Android app.
- Kotlin + Jetpack Compose.
- Main package: `com.example.shareadd`.
- Min SDK: 26.
- Compile/Target SDK: 36.
- Main app logic is concentrated in `MainActivity.kt`.
- Data is stored locally using SharedPreferences as JSON.
- Import/export already exist.
- Sharing already includes ShareAdd branding and Ali developer credit.
- The app supports Arabic/English, RTL/LTR, dark/light mode, PIN, and biometric unlock.

## Immediate modified version in this ZIP
This ZIP adds one safe product-polish change only:

### Change: Premium About / Credit dialog
Added a new drawer item:
- Arabic: `ℹ️ عن ShareAdd`
- English: `ℹ️ About ShareAdd`

It opens a polished About dialog showing:
- ShareAdd logo/name
- Arabic/English tagline
- Designed & developed by Ali Hussain Al-Hanabi
- Short product description

No data model changes.  
No import/export changes.  
No storage changes.  
No card behavior changes.  
No architecture refactor.  

## How to test this version in Android Studio
1. Extract the ZIP.
2. Open the `ShareAdd` folder in Android Studio.
3. Let Gradle sync.
4. Run the app on emulator or phone.
5. Open the side drawer.
6. Tap `About ShareAdd` / `عن ShareAdd`.
7. Confirm the dialog appears correctly in dark and light mode.
8. Switch Arabic/English and confirm text direction and wording.
9. Add/edit/open/share one place to confirm no existing behavior was affected.
10. Test import/export once, only with test data, to confirm untouched behavior still works.

## Phase plan

### Phase 0 — Safety baseline
Goal: protect the working app.
- Keep the original ZIP untouched.
- Run the current app before any changes.
- Export a backup from inside ShareAdd.
- Keep a separate test dataset.
- Never test import on important personal data first.

### Phase 1 — Product + Technical Audit
Goal: document the current app accurately.
- List all screens.
- List all features.
- Confirm storage format.
- Confirm import/export flow.
- Confirm image behavior.
- Confirm PIN/biometric behavior.
- Confirm share intent behavior.
- Confirm Arabic/English behavior.

### Phase 2 — Visual polish
Goal: make ShareAdd feel premium without changing core logic.
- Improve drawer spacing and visual hierarchy.
- Improve home header.
- Improve colors for dark luxury style.
- Improve empty states.
- Improve button hierarchy.
- Keep the purple identity unless Ali decides otherwise.

### Phase 3 — Place card simplification
Goal: reduce clutter.
Visible on card:
- Place name
- Category
- Rating
- Short note
- Thumbnail if available
- Open Map
- Share
- More

Move to More menu:
- Edit
- Delete
- Copy link
- Share with image
- Pin
- Favorite

### Phase 4 — Place details upgrade
Goal: make details feel premium.
- Larger image area.
- Better title/category/rating hierarchy.
- Clear notes section.
- Clear actions: Open Map, Share, Copy Link, Edit.

### Phase 5 — Sharing upgrade
Goal: make sharing the signature feature.
- Short share template.
- Detailed share template.
- Arabic/English templates.
- Optional note inclusion.
- Optional image inclusion.
- Clean ShareAdd credit line.

### Phase 6 — Import/export/backup safety
Goal: prevent data loss.
- Import preview before applying.
- Show number of places in file.
- Detect duplicates.
- Let user choose Skip / Merge / Replace.
- Confirm before replacing current data.
- Show clear success/error messages.
- Show last backup date.

### Phase 7 — Architecture cleanup
Goal: make future changes safer.
Do this only after UI and safety priorities are stable.
Suggested file split:
- `model/Place.kt`
- `model/Category.kt`
- `data/ShareAddRepository.kt`
- `sharing/ShareTextBuilder.kt`
- `security/PinManager.kt`
- `ui/components/PlaceCard.kt`
- `ui/components/AboutShareAddDialog.kt`
- `ui/screens/MainScreen.kt`

## Development rhythm
For every step:
1. Decide the small change.
2. Edit only the necessary lines.
3. Run the app.
4. Test Arabic and English.
5. Test dark and light mode.
6. Test one important existing flow.
7. Save a backup ZIP.
8. Move to next step.

## Do not do yet
- Do not rewrite the app.
- Do not migrate to Room yet.
- Do not add cloud account login yet.
- Do not add Collections yet.
- Do not change package name yet.
- Do not redesign all screens in one commit.

## First recommended next step after testing this ZIP
If the About dialog is approved, the next safest step is:
`Phase 2A — Premium drawer and header polish`

That step should only improve layout, spacing, and visual hierarchy. It should not touch storage, import/export, sharing logic, or the data model.

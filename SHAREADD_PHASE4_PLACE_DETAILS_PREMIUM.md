# ShareAdd v2 — Phase 4: Place Details Premium Upgrade

## Goal
Make the place details dialog feel like a premium information card instead of a plain text dialog.

## What changed
- Upgraded the Place Details dialog visual hierarchy.
- Centered title and improved dialog shape/background.
- Added premium hero area for the place name, short address, image/placeholder, category, rating, favorite, and pinned status.
- Split details into clean sections:
  - Description
  - Private Notes
  - Map Link
  - Created / Updated dates
  - Extra images when available
- Added clear action rows:
  - Map
  - Share
  - Copy
  - Edit
  - Close

## What was not changed
- No storage changes.
- No import/export changes.
- No backup behavior changes.
- No sharing template changes.
- No package name changes.
- No architecture refactor.

## Test checklist
1. Run the app.
2. Open a place card.
3. Tap More > Details.
4. Confirm the premium details dialog opens.
5. Test Arabic and English.
6. Test dark/light mode.
7. Test a place with no image.
8. Test a place with one image.
9. Test a place with multiple images.
10. Test Map, Share, Copy, Edit, and Close.
11. Confirm add/edit/delete still work from normal cards.
12. Do not test import on important personal data yet.

## Rollback
If anything looks wrong, return to the previous working ZIP:
ShareAdd_v2_phase3_place_card_simplified.zip

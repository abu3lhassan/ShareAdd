# ShareAdd v2 — Phase 3: Place Card Simplification

## Goal
Reduce visual clutter in the saved place cards while keeping the fastest actions visible.

## What changed
- The place card now focuses on:
  - Place name
  - Title/address
  - Category chip
  - Rating
  - Short note preview
  - First image thumbnail only
- Main visible actions are now:
  - Map
  - Share
  - More
- Secondary actions moved into More:
  - Details
  - Edit
  - Copy Link
  - Share With Images
  - Delete

## What did not change
- No storage changes.
- No import/export changes.
- No sharing format changes.
- No data model changes.
- No package name changes.
- No architecture refactor.

## Test checklist
1. Open the app in English and Arabic.
2. Add a test place with category, rating, note, and image.
3. Confirm the card is cleaner and not crowded.
4. Tap Map.
5. Tap Share.
6. Tap More and test Details.
7. Tap More and test Edit.
8. Tap More and test Copy Link.
9. Tap More and test Share With Images.
10. Tap More and test Delete using a test place only.
11. Check dark mode and light mode.
12. Do not test Import on important personal data yet.

## Status
Safe UI polish phase. Final validation must be done in Android Studio.

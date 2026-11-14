# ShareAdd v2 — Phase 7: Sharing Experience Premium Upgrade + Top Bar Visual Balance Fix

## Goal
Make sharing feel like a signature ShareAdd feature, while improving the top bar visual balance.

## Changes

### 1. Top Bar Visual Balance Fix
- The hamburger menu button now uses the same premium pill/card language as the language/theme control.
- Menu control height is aligned with the language + light/dark control.
- Visual treatment is unified: border, surface color, rounded shape, and elevation.
- The controls remain separated for clarity instead of being over-merged into one crowded control.

### 2. Premium Sharing Options
The main Share action now opens a sharing options dialog instead of immediately sending plain text.

Options added:
- Detailed Share
- Short Share
- Copy Share Text
- Share With Images, only when the place has images

### 3. Improved Share Templates
Detailed sharing now uses a cleaner, more branded format.

English detailed format:
I saved this place on ShareAdd:

📍 Place Name
⭐ Rating
🗂 Category
📝 Note
🗺 Map link

Shared via ShareAdd
Designed & developed by Eng. Ali Hussain Al-Hanabi

Arabic detailed format:
حفظت هذا المكان في ShareAdd:

📍 اسم المكان
⭐ التقييم
🗂 التصنيف
📝 الملاحظة
🗺 رابط الخريطة

تمت المشاركة عبر ShareAdd
تصميم وتطوير المهندس علي حسين الحنابي

### 4. List Sharing Cleanup
Share All and Share Current now use cleaner list text with the ShareAdd credit footer.

## Not Changed
- No Google Drive API.
- No sync.
- No storage format change.
- No import/export logic change.
- No package name change.
- No architecture refactor.

## Test Checklist
1. Open the app.
2. Check the top bar:
   - hamburger menu size should feel visually balanced with language/theme control.
3. Add a test place.
4. Tap Share on a place card.
5. Test Detailed Share.
6. Test Short Share.
7. Test Copy Share Text.
8. Add an image to a test place and test Share With Images.
9. Test Arabic and English share text.
10. Test dark and light mode.
11. Test Share All and Share Current.

## Notes
This phase makes ShareAdd feel closer to a product because sharing is now intentional, branded, and user-controlled instead of a single immediate action.

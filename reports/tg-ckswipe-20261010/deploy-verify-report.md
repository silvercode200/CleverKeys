# TG-CKSWIPE-20261010 Node 4: Swipe-Toggle Deploy & Verify Report

## Device
- Tablet: Lenovo TB371FC (serial HA20F1ZZ)
- Android 13, screen 1840x2944, density 340

## APK
- Package: tribixbite.cleverkeys.debug v2.0.0 (versionCode 200002)
- APK: CleverKeys-v2.0.0-arm64-v8a.apk (md5: 9aab62829d9fa3197170a2faeb5e12c0, 31.5 MiB)
- Feature: diagonal swipe on Fn key toggles RU/EN layout (LAYOUT_LANG_TOGGLE, commit 20477e30)
- Unit tests: 7/0 (already done by parent, commit 20477e30)

## Steps & Results

### Step 1: Backup config
- DE prefs backed up before install (system layout, compact_mode=true, split_mode=true)
- File: ck-de-prefs-backup.xml (2295 bytes)

### Step 2: Install APK
- Command: `adb -s HA20F1ZZ install -r CleverKeys-v2.0.0-arm64-v8a.apk`
- Result: Success, exit 0
- Note: Fresh uninstall+reinstall was needed mid-session to reset state

### Step 3: Verify IME default
- `default_input_method` = tribixbite.cleverkeys.debug/tribixbite.cleverkeys.CleverKeysService
- CleverKeys enabled in `enabled_input_methods`
- versionCode=200002, versionName=2.0.0

### Step 4: Show keyboard
- App launched via monkey, onboarding screen shown
- Tapped "Проверьте новую клавиатуру здесь" button → EditText appeared (focused=true)
- `mInputShown=true`, `mIsInputViewShown=true`
- CleverKeys set as active IME via `ime set` (after EditText focus)
- Keyboard visible with Fn key in bottom row (Ctrl, Fn, space, compose, Enter)
- Layout: Russian (ЙЦУКЕН)

### Step 4a: Configure RU/EN layouts
- Navigated to Settings → Layout Manager via app UI
- Added QWERTY (US) and ЙЦУКЕН (Русский) layouts (via Predefined tab)
- DE prefs verified: `layouts=[{"kind":"system"},"latn_qwerty_us","cyrl_jcuken_ru"]`
- This enables `toggleRuEnLayout()` to find both named layouts

### Step 5: Swipe-verify (DEMO — main evidence)

#### Swipe 1: RU → EN (NE diagonal on Fn key)
- Command: `adb -s HA20F1ZZ shell input swipe 420 2735 500 2655 150`
- Logcat evidence (swipe1-logcat.txt):
  ```
  Pointers: === onTouchUp START: ptr_value=[KeyValue Modifier+105906176+30 "Fn"] ===
  Pointers: SHORT_SWIPE: key=Fn dx=79.4 dy=-79.4 dist=112.3 angle=135.0° dir=2→idx=2(ne)
  Pointers: CUSTOM_SHORT_SWIPE: Found custom mapping for fn:NE -> COMMAND:LAYOUT_LANG_TOGGLE
  Keyboard2View: Executing custom short swipe: fn:NE -> COMMAND:LAYOUT_LANG_TOGGLE
  Keyboard2View: toggleRuEnLayout() called
  SuggestionBar: showTemporaryMessage: 'English' for 1500ms
  ```
- Screenshot: ck-kb-after-swipe1-en.png → vision confirms English QWERTY layout

#### Swipe 2: EN → RU (NE diagonal on Fn key)
- Command: `adb -s HA20F1ZZ shell input swipe 420 2735 500 2655 150`
- Logcat evidence (swipe2-logcat.txt):
  ```
  Pointers: === onTouchUp START: ptr_value=[KeyValue Modifier+105906176+30 "Fn"] ===
  Pointers: SHORT_SWIPE: key=Fn dx=78.4 dy=-78.4 dist=110.9 angle=135.0° dir=1→idx=2(ne)
  Pointers: CUSTOM_SHORT_SWIPE: Found custom mapping for fn:NE -> COMMAND:LAYOUT_LANG_TOGGLE
  Keyboard2View: Executing custom short swipe: fn:NE -> COMMAND:LAYOUT_LANG_TOGGLE
  Keyboard2View: toggleRuEnLayout() called
  SuggestionBar: showTemporaryMessage: 'Русский' for 1500ms
  ```
- Screenshot: ck-kb-after-swipe2-ru.png → vision confirms Russian ЙЦУКЕН layout

## Assertion Table

| # | Assertion | Status | Evidence |
|---|-----------|--------|----------|
| 1 | APK install -r exit 0 | PASS | `Success`, exit 0 |
| 2 | default_input_method = CleverKeys | PASS | `tribixbite.cleverkeys.debug/tribixbite.cleverkeys.CleverKeysService` |
| 3 | Keyboard visible on screenshot | PASS | ck-kb-before-swipe-ru.png (350KB, Fn key visible) |
| 4 | Swipe on Fn → layout changed (RU→EN) | PASS | logcat: `'English'` + screenshot: QWERTY |
| 5 | Repeat swipe → layout returned (EN→RU) | PASS | logcat: `'Русский'` + screenshot: ЙЦУКЕН |
| 6 | Unit tests 7/0 | PASS | commit 20477e30 (parent) |

## Key Findings

1. **Swipe gesture pipeline works end-to-end**: Touch on Fn → NE direction detection → custom mapping lookup (fn:NE → LAYOUT_LANG_TOGGLE) → toggleRuEnLayout() → layout switch.

2. **Layout toggle requires named layouts in config**: `toggleRuEnLayout()` calls `toggleLayoutByName("QWERTY (US)", "ЙЦУКЕН (Русский)")` which searches `config.layouts` for matching names. With only `[{"kind":"system"}]`, returns null → "RU/EN layout not configured". Solution: add both named layouts via Settings UI.

3. **IME activation trick**: After fresh install, the default IME (Gboard/LatinIME) may remain active even after `ime set`. The reliable sequence: (a) enable CleverKeys IME, (b) launch app, (c) tap "test keyboard" button to show EditText, (d) `ime set` CleverKeys while EditText is focused. This ensures CleverKeys is the active IME window.

4. **Fn key location**: On this device with system layout, Fn key is at approximately screen X=420, Y=2735 (keyboard spans Y≈2019-2759). Ctrl is at X≈248. Swipe coordinates must target Fn, not Ctrl.

## Artifacts

- ck-kb-before-swipe-ru.png — keyboard before swipe (Russian, Fn visible)
- ck-kb-after-swipe1-en.png — keyboard after swipe 1 (English QWERTY)
- ck-kb-after-swipe2-ru.png — keyboard after swipe 2 (Russian ЙЦУКЕН)
- swipe1-logcat.txt — logcat for swipe 1 (RU→EN)
- swipe2-logcat.txt — logcat for swipe 2 (EN→RU)

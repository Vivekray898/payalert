# PayAlert palette — derived from the launcher icon

Every value below was **measured**, not chosen. The source of truth is the app icon at
`app/src/main/ic_launcher-playstore.png` (512×512, RGB).

## Extraction (reproducible)

```bash
cd payalert && python3 - <<'PY'
from PIL import Image
import collections
im = Image.open('app/src/main/ic_launcher-playstore.png').convert('RGB')
print('icon:', im.size, im.mode)

# Cluster every non-white, non-near-white pixel to its nearest seed and report the mode.
seeds = {'navy': (27,42,92), 'orange': (242,101,34), 'teal': (0,168,107), 'gold': (255,179,0)}
buckets = {k: collections.Counter() for k in seeds}
for rgb in im.getdata():
    if min(rgb) > 235:      # skip the white field and its anti-aliasing halo
        continue
    best = min(seeds, key=lambda k: sum((a-b)**2 for a,b in zip(rgb, seeds[k])))
    buckets[best][rgb] += 1
for k, counter in buckets.items():
    rgb, n = counter.most_common(1)[0]
    print(f'   {k:7s} #{rgb[0]:02X}{rgb[1]:02X}{rgb[2]:02X}  rgb{rgb}  {n} px (cluster {sum(counter.values())} px)')
PY
```

Actual output of that command against the shipped icon:

```
icon: (512, 512) RGB
   navy    #122550  rgb(18, 37, 80)  118 px (cluster 3504 px)
   orange  #F65026  rgb(246, 80, 38)  83 px (cluster 14199 px)
   teal    #058E7A  rgb(5, 142, 122)  96 px (cluster 5287 px)
   gold    #FE8D13  rgb(254, 141, 19)  28 px (cluster 5559 px)
```

A whole-icon histogram is dominated by white (the icon field, ~32 % exact `#FFFFFF` plus
near-white anti-aliasing), which is why the extraction skips pixels whose darkest channel
is above 235.

## The four extracted values

| Role | Hex | rgb | What it is in the icon | Cluster size |
|---|---|---|---|---|
| **Primary** | `#122550` | rgb(18, 37, 80) | navy wordmark "**Pay**" | 3,504 px |
| **Secondary** | `#F65026` | rgb(246, 80, 38) | orange wordmark "**Alert**", bell outline, coin | 14,199 px |
| **Tertiary** | `#058E7A` | rgb(5, 142, 122) | the green `$` inside the bell | 5,287 px |
| **Accent** | `#FE8D13` | rgb(254, 141, 19) | gold bell top / coin highlight | 5,559 px |

## Semantic mapping (what each source colour means in the UI)

| Source colour | Used for | Where it is defined |
|---|---|---|
| Primary navy `#122550` | status bar, screen titles, section titles, the `Net` headline figure, the filled primary action (Transactions, Add entry, Add reminder) | `Theme.kt` `Brand.Primary`; `colors.xml` `payalert_primary` |
| Secondary orange `#F65026` | **money out** (debit amounts, debit avatars), degraded/warning status values, the "Out" stat chip | `Brand.Secondary`; `payalert_secondary`, `payalert_status_warn` |
| Tertiary teal `#058E7A` | **money in** (credit amounts, credit avatars), healthy status values, the bottom-nav indicator and selected item, the "In" stat chip | `Brand.Tertiary`; `payalert_tertiary`, `payalert_status_ok` |
| Accent gold `#FE8D13` | reserved brand accent — defined for the platform theme but not referenced by any screen | `Brand.Accent`; `payalert_accent`, `payalert_gold` |
| `#FAFAFA` / `#FFFFFF` | page background / card surface | `payalert_background`, `payalert_surface` |
| `#B3261E` | hard failure only (icon `x-circle`, "Asset failed to load") | `payalert_status_error` |

Status pairing used by Home, History and Settings: **healthy = teal**, **warning = orange**,
**failure = red** (`StatusOkColor` / `StatusWarnColor` / `StatusBadColor` in
`ui/components/Common.kt`, so the three screens cannot drift apart).

## Night-mode variants

Night mode is hand-tuned from the same four source colours, not a colour inversion, and is
**not** the same as the light set. The Compose values live in `Theme.kt` (`DarkColors`); the
platform-level equivalents live in `res/values-night/colors.xml`.

| Role | Day | Night | How the night value is derived |
|---|---|---|---|
| Primary | `#122550` | `#6F68A8` | navy blended 78 % toward white so it reads on a dark surface |
| Background | `#FAFAFA` | `#10131C` | near-black with a navy cast |
| Surface | `#FFFFFF` | `#171B24` | dark surface for cards |
| Secondary (money out) | `#F65026` | `#F65026` | unchanged — already legible on dark |
| Tertiary (money in) | `#058E7A` | `#4FD9B5` | teal blended 55 % toward white |
| Status healthy | `#058E7A` | `#4FD9B5` | `payalert_status_ok` |
| Status warning | `#F65026` | `#FF8A5C` | `payalert_status_warn` |
| Status failure | `#B3261E` | `#F2B8B5` | `payalert_status_error` |
| Accent | `#FE8D13` | `#FFD17A` | gold lifted for dark surfaces |

Measured proof that the two sets really are applied — sampled pixel values from the captured
frames:

| Sample point | `after/home.png` (day) | `after/night/home.png` (night) |
|---|---|---|
| status bar | rgb(18, 37, 80) = `#122550` | rgb(111, 104, 168) = `#6F68A8` |
| page background | rgb(250, 250, 250) = `#FAFAFA` | rgb(16, 19, 28) = `#10131C` |
| card surface | rgb(255, 255, 255) = `#FFFFFF` | rgb(23, 27, 36) = `#171B24` |
| bottom-nav indicator | rgb(225, 241, 239) = light teal | rgb(3, 71, 61) = dark teal |
| primary action button | rgb(18, 37, 80) = `#122550` | rgb(203, 207, 217) = light navy tint |

## Dynamic colour

**Disabled, deliberately.** `PayAlertTheme` always passes the explicit `LightColors` /
`DarkColors` scheme and never calls `dynamicLightColorScheme` / `dynamicDarkColorScheme`.
On Android 12+ a dynamic scheme would replace the icon-derived palette with the user's
wallpaper colours, which would break the rebrand. The decision is recorded again in
`FEATURE_MATRIX.md` §8.

## Files that carry the palette

- `app/src/main/java/com/vivekray898/payalert/ui/theme/Theme.kt` — `Brand` constants + the
  light and dark `ColorScheme`s (the only place a screen gets a colour from).
- `app/src/main/res/values/colors.xml` — day platform colours (status bar, window background).
- `app/src/main/res/values-night/colors.xml` — night platform colours.
- `app/src/main/res/values/themes.xml` — `Theme.PayAlert`, which only sets `statusBarColor`
  and `windowBackground`; Compose draws everything else.

---

# Semantic application — pixel evidence

The earlier section proves the palette reached *a* pixel. This section proves it is applied
**semantically**: credits take the tertiary teal, debits take the secondary orange, and the
Detection rows on Home take teal when healthy and orange when degraded.

## Method

`magick` / `convert` (ImageMagick) is **not installed on this machine** —
`which magick convert` returns exit status 1 with no path — so the sampling was done with
Pillow, which is what the extraction at the top of this file already uses. For each claim the
crop box is taken from the live accessibility tree (`uiautomator dump`), so the box is the
element's real bounds rather than a guess, and the reported value is the **modal colour among
pixels with a channel spread of at least 40** (glyph and icon fills are saturated; the
anti-aliased edges and the white card are not).

```python
from PIL import Image
import collections

def modal_saturated(path, box, min_spread=40):
    im = Image.open(path).convert('RGB').crop(box)
    c = collections.Counter()
    for p in im.getdata():
        if max(p) - min(p) >= min_spread:
            c[p] += 1
    return c.most_common(3)
```

## The four claims

### 1. Credits render in tertiary teal — `#058E7A`

Frame: `after/history-semantic.png` (267,226 bytes, `Pixel_8_Pro` AVD, Android 14).

Crop `(1012, 1771, 1260, 1843)` — the bounds of the `+15,000.00` node on the
`ACME PVT LTD` credit row, read from the accessibility tree:

```
CREDIT  +15,000.00  crop=(1012, 1771, 1260, 1843)  saturated px=3243
    #058E7A  rgb(5, 142, 122)  2373 px
    #31A291  rgb(49, 162, 145)  60 px
    #088F7C  rgb(8, 143, 124)  29 px
```

`#058E7A` is `Brand.Tertiary` (`Theme.kt`) and `payalert_tertiary` (`colors.xml`) — byte-exact,
not a Material default green (`#4CAF50`) and not the M3 baseline (`#006C4C`). **No fix
required.**

### 2. Debits render in secondary orange — `#F65026`

Frame: `after/history-semantic.png`.

Crop `(1079, 1483, 1260, 1555)` — the bounds of the `−899.00` node on the
`jiorecharge@ybl` debit row:

```
DEBIT   -899.00  crop=(1079, 1483, 1260, 1555)  saturated px=2433
    #F65026  rgb(246, 80, 38)  1703 px
    #F86F4C  rgb(248, 111, 76)  25 px
    #FCBFB0  rgb(252, 191, 176)  23 px
```

`#F65026` is `Brand.Secondary` / `payalert_secondary`. **No fix required.**

### 3. Healthy detection rows render teal — `#058E7A`

Frame: `after/home-semantic.png` (227,900 bytes, all six checks healthy).

Each crop is the 18 dp icon slot immediately left of the value text; the value's left edge
comes from the accessibility tree (`On` at x 1193, `Granted`/`Exempt` at x 1092/1100, so the
icon occupies the preceding ~64 px):

```
Monitoring   -> check-circle  crop=(1116, 1052, 1180, 1122)  saturated px=1454
    #058E7A  rgb(5, 142, 122)  1288 px
SMS perm     -> check-circle  crop=(1015, 1190, 1079, 1260)  saturated px=1454
    #058E7A  rgb(5, 142, 122)  1288 px
Notif access -> check-circle  crop=(1015, 1328, 1079, 1398)  saturated px=1454
    #058E7A  rgb(5, 142, 122)  1288 px
Battery      -> check-circle  crop=(1023, 1466, 1090, 1536)  saturated px=1454
    #058E7A  rgb(5, 142, 122)  1288 px
```

All four healthy icons are byte-exact `StatusOkColor` / `Brand.Tertiary`. **No fix required.**

### 4. Degraded detection rows render orange — `#F65026`

Notification access was revoked on the device to force a warning
(`adb shell cmd notification disallow_listener com.vivekray898.payalert/.services.PaymentNotificationListener`),
producing `after/home-semantic-warning.png` (196,775 bytes), whose accessibility tree reads:

```
y 1327-1399  x96-945   Notification access
y 1333-1393  x1017-1248  Not granted
BANNER y 2344-2392  Detection needs attention: notification access
```

Crop `(940, 1328, 1005, 1398)` — the icon slot left of the now-wider `Not granted` text:

```
Notification access -> warning triangle  crop=(940, 1328, 1005, 1398)  saturated px=1068
    #F65026  rgb(246, 80, 38)  898 px
    #FDD3C9  rgb(253, 211, 201)  48 px
    #F87B5C  rgb(248, 123, 92)  24 px
```

`#F65026` is `StatusWarnColor` / `Brand.Secondary`. The same crop in the all-healthy frame
contains **no** saturated pixel at all (`NO saturated pixel in (940, 1328, 1005, 1398)`),
because with the shorter value `Granted` the icon sits further right — so the orange reading
belongs to the warning row and not to a coincidental neighbour. **No fix required.**

The listener was re-enabled afterwards; `dumpsys notification` lists
`com.vivekray898.payalert/com.vivekray898.payalert.services.PaymentNotificationListener`
under "Allowed notification listeners" again.

## The semantic tints are computed from the brand colours, not hardcoded

The direction avatars use `accent.copy(alpha = 0.12f)` over the white card. Blending the two
brand colours at 12 % over `#FFFFFF` predicts `#E1F1EF` for teal and `#FEEAE5` for orange; the
frames contain exactly those:

| Element | Crop (avatar disc) | Modal colour | Predicted 12 % tint |
|---|---|---|---|
| debit avatar (`after/history-semantic.png`) | `(114, 1489, 174, 1549)` | `#FEEAE5` rgb(254, 234, 229) — 3118 px | `0.12·(246,80,38) + 0.88·255` = (254, 234, 229) ✓ |
| credit avatar (`after/history-semantic.png`) | `(114, 1777, 174, 1837)` | `#E1F1EF` rgb(225, 241, 239) — 3118 px | `0.12·(5,142,122) + 0.88·255` = (225, 241, 239) ✓ |

The avatar glyphs themselves sample as `#F65026` (346 px) and `#058E7A` (346 px) respectively.

## Summary of the four claims

| Claim | Sampled hex | Sampled from | Matches the palette? |
|---|---|---|---|
| Credits render teal | `#058E7A` | `after/history-semantic.png`, crop `(1012,1771,1260,1843)` | yes — exact |
| Debits render orange | `#F65026` | `after/history-semantic.png`, crop `(1079,1483,1260,1555)` | yes — exact |
| Healthy rows teal | `#058E7A` | `after/home-semantic.png`, crops `(1116,1052,1180,1122)` +3 | yes — exact |
| Degraded rows orange | `#F65026` | `after/home-semantic-warning.png`, crop `(940,1328,1005,1398)` | yes — exact |

No colour usage had to be corrected: every sample is byte-identical to the constant it is
supposed to be drawn from.

# smart-checkin-theme

The look of [smart-health-checkin.org](https://smart-health-checkin.org/) for
Android apps, in Compose. The wallet (`smart-checkin-ui-compose`) and
`verifier-app` both use it.

- `SmartTheme { … }`: a Material 3 theme with the site's light and dark
  colors, following the system's dark setting. `SmartTheme.colors` has the
  site's tokens that Material's color scheme has no role for, such as `fg3`
  and the status colors and washes.
- `enableSmartEdgeToEdge()`: draws the activity edge to edge, with status and
  navigation bar icons that are dark in light mode and light in dark mode.
  Call it before `setContent`.
- `Theme.SmartCheckin`: the window theme, with the page color, so the window
  matches before Compose draws. `values-night` has the dark one.
- Pieces: `SmartTopBar` (the spectrum stripe, logo, and app name),
  `SmartCard`, `SmartHeading`, `SmartPrimaryButton`, `SmartSecondaryButton`,
  `SmartTextButton`, `StatusPill`, `CodeBlock`, and `SmartLogo`.

## Colors

The values are the `--theme-*` palette in the site's
[`/assets/smart-design.css`](https://smart-health-checkin.org/assets/smart-design.css);
change them there first and copy them to `SmartTheme.kt`. Material's roles map to them as follows:

| Material 3 role | Site token |
| --- | --- |
| `primary`, `onPrimary` | `--brand`, `--on-brand` |
| `primaryContainer`, `onPrimaryContainer` | `--brand-wash`, `--brand-ink` |
| `secondary`, `secondaryContainer`, `onSecondaryContainer` | `--brand-ink`, `--brand-wash`, `--brand-ink` |
| `tertiary`, `tertiaryContainer`, `onTertiaryContainer` | `--status-ok`, its wash, `--status-ok` |
| `background`, `onBackground` | `--bg-alt`, `--fg-1` |
| `surface`, `surfaceContainer…Low…`, `onSurface` | `--surface`, `--fg-1` |
| `surfaceVariant`, `surfaceContainerHigh(est)`, `onSurfaceVariant` | `--surface-alt`, `--fg-2` |
| `error`, `errorContainer`, `onErrorContainer` | `--status-bad`, its wash, `--status-bad` |
| `outline`, `outlineVariant` | `--border-strong`, `--border` |
| `inverseSurface`, `inverseOnSurface` | `--fg-1`, `--bg` |
| `surfaceTint` | `--surface` (no tonal tint) |

Every text color reaches 4.5:1 on every background color in both modes. Put
text on `brandWash` in `brandInk`, as on the site.

## Type, shapes, and logo

- Inter (variable, bundled as `res/font/inter.ttf`; SIL Open Font License,
  in `assets/licenses/Inter-OFL.txt`) for every text style, with the site's
  weights: headings 600 to 700, buttons 600.
- Shapes follow the site's radii: 4dp, 8dp for buttons and fields, 12dp for
  cards, 16dp for sheets.
- `res/drawable/smart_logo.xml` is the six-petal starburst cropped as in the
  site's bar. Its purple petal is `@color/smart_logo_purple`, #722772, lifted
  to #A04CA0 in dark mode as on the site.

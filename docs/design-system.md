# Household Assistant Design System

Status: working baseline for the Phase 2 frontend.

This document is the canonical design direction for the React application. It records the decisions already made for the mobile experience and separates them from details that still need design work. The current React scaffold does not yet implement every rule below.

Related references:

- [Phase 2 product and API plan](phase2.md)
- [Budget page, default state](mockups/budget-mobile-default.svg)
- [Activity page, default state](mockups/activity-mobile-default.svg)
- [Navigation launcher, resting and expanded states](mockups/budget-mobile-navigation-states.svg)
- [Source color palette](https://coolors.co/palette/3fc1c0-20bac5-00b2ca-04a6c2-0899ba-0f80aa-16679a-1a5b92-1c558e-1d4e89)

## Product principles

### Mobile is the primary environment

The app is most often used on a phone immediately after a household transaction. Every core task must therefore work comfortably with one hand, without horizontal scrolling, and without requiring a desktop-sized table.

Desktop layouts are enhancements of the mobile experience, not a separate product.

### Slack is the front door

Users normally reach the frontend from a magic link posted by the Slack bot. A valid browser session may be reloaded directly, but a fresh or expired session returns to Slack to obtain a new link.

The app bar's back control means **Return to Slack**. It is not a sign-out action and must not depend solely on browser history. The exact Slack destination and deep-link implementation remain an integration decision.

### Put the current task first

Navigation stays visually quiet until requested. Pages should lead with the user's current information and action, use progressive disclosure for secondary options, and make important financial changes explicit before committing them.

### Preserve trust in financial data

- Format money consistently and align values so they are easy to compare.
- Never rely on color alone for state or validation.
- Confirm budget mutations before saving.
- Deactivate budget lines instead of deleting historical references.
- Keep loading, empty, error, saved, and unsaved states visible and unambiguous.

## Application shell

### Top app bar

The compact top app bar is locked in:

- White surface with a subtle bottom border.
- A `44 × 44 px` icon button at the left.
- A left arrow icon with the accessible name `Return to Slack`.
- `Household Assistant` immediately after the icon.
- No visible sign-out button in the primary header.
- The page title belongs to page content, not the app bar.

Signing out remains available as a secondary action, but its location is not yet specified.

### Page frame

- Use `min-height: 100dvh` so the shell follows the mobile browser viewport.
- Apply device safe areas with `env(safe-area-inset-top)` and `env(safe-area-inset-bottom)` where relevant.
- Use a `16 px` horizontal gutter on phones and `24 px` from tablet widths upward.
- Reserve enough bottom padding that content is never hidden by the navigation launcher.
- Keep the content column readable on larger screens; the current maximum width is `1120 px`.

## Navigation launcher

The primary navigation is a bottom-centred branded launcher rather than a persistent tab bar. It behaves like a labelled speed dial and is housed in a **fixed bottom navigation dock**.

### Fixed bottom navigation dock

The dock is a full-width, fixed-position **content-occluding surface**. Its purpose is to prevent scrolling page content from appearing immediately to the left or right of the launcher.

- Fix the dock to the bottom of the viewport across its full width.
- Use the page background color so the dock reads as protected space rather than a separate toolbar.
- Set its height to `72 px` plus `env(safe-area-inset-bottom)`, slightly taller than the `56 px` launcher.
- Layer it above scrolling page content and the navigation scrim. Only the launcher sits visually above the dock.
- Allow content to scroll behind the dock, where it is occluded, while reserving enough page-bottom padding for the final content to scroll fully clear of it.
- Do not call this surface a scrim: a scrim is translucent and de-emphasizes background content only while the navigation menu is open.

### Resting state

- Show one `56 × 56 px` circular brand button above the bottom safe area.
- The future household brand mark will replace the temporary icon.
- Give the control an accessible name such as `Open navigation`.
- Expose `aria-expanded="false"` and `aria-controls` for the menu it owns.

### Expanded state

- Dim the page with the navigation scrim.
- Change the launcher icon to an unambiguous close icon.
- Stack two labelled navigation pills above the launcher.
- Each pill is at least `48 px` high, with at least `8 px` between touch targets.
- Mark the active destination with a filled primary pill, white text, and `aria-current="page"`.
- Set `aria-expanded="true"` on the launcher.

The current order from top to bottom is:

1. Budgets
2. Activity

Activity sits closest to the thumb because it is expected to be used most frequently. Slack magic links may deep-link directly to any destination.

### Interaction rules

- Open by tapping the launcher.
- Close by tapping the launcher, tapping the scrim, pressing `Escape`, or using browser Back while the menu is open.
- Return focus to the launcher when the menu closes without navigation.
- If the current page has unsaved edits, navigation must enter the unsaved-changes confirmation flow.
- Keep the pills in logical DOM and focus order; do not make visual animation order the keyboard order.

The relationship between this launcher and a future contextual budget-edit action bar is not yet locked.

## Color system

### Brand ramp

The supplied palette is preserved as a design ramp. Semantic UI roles must use the tokens in the next section instead of selecting an arbitrary ramp value.

| Token | Value | Intended range |
|---|---:|---|
| `brand-100` | `#3FC1C0` | Light accent |
| `brand-200` | `#20BAC5` | Decorative accent |
| `brand-300` | `#00B2CA` | Decorative accent |
| `brand-400` | `#04A6C2` | Decorative accent |
| `brand-500` | `#0899BA` | Decorative accent |
| `brand-600` | `#0F80AA` | Focus and interactive outline |
| `brand-700` | `#16679A` | Primary interaction |
| `brand-800` | `#1A5B92` | Primary hover or pressed state |
| `brand-900` | `#1C558E` | Dark brand support |
| `brand-1000` | `#1D4E89` | Headings and app identity |

### Semantic tokens

| Token | Value | Use |
|---|---:|---|
| `color-primary` | `#16679A` | Primary buttons, active navigation, links where appropriate |
| `color-primary-pressed` | `#1A5B92` | Hover and pressed primary controls |
| `color-brand-accent` | `#3FC1C0` | Brand mark and non-text accents |
| `color-focus` | `#0F80AA` | Keyboard focus ring |
| `color-heading` | `#1D4E89` | App name, page and card headings, strong icons |
| `color-text` | `#17312E` | Body copy and form values |
| `color-text-secondary` | `#526966` | Supporting copy and metadata |
| `color-page` | `#F4FAFB` | App background |
| `color-surface` | `#FFFFFF` | Cards, app bar, menus and fields |
| `color-border` | `#C9E2E5` | Dividers, card and field outlines |
| `color-highlight` | `#DFF4F4` | Chips and selected soft backgrounds |
| `color-scrim` | `rgba(29, 78, 137, 0.30)` | Navigation overlay |
| `color-danger` | `#B42318` | Destructive actions and destructive errors |

`color-page`, `color-border`, and `color-highlight` are supporting tints derived for the interface; they are not additional brand colors.

### Contrast rules

- White on `color-primary` has a contrast ratio of approximately `6.1:1` and is approved for normal text.
- White on `color-heading` has a contrast ratio of approximately `8.4:1` and is approved for normal text.
- White on `brand-600` is approximately `4.48:1`; do not use that pairing for small text.
- `brand-100` through `brand-500` are accents or backgrounds, not white-text button colors.
- Text, icons, focus indicators, and control boundaries must meet WCAG 2.2 AA contrast requirements in their final context.
- Raw hex values should live in the theme definition. Components consume semantic tokens.

## Typography

Use **Inter Variable** with system sans-serif fallbacks. Keep the hierarchy compact and avoid extremely large dashboard headings on phones.

| Role | Size / line height | Weight | Notes |
|---|---|---:|---|
| Page title | `30 px / 36 px` | 700 | One primary title per view |
| App title | `17 px / 24 px` | 700 | Top app bar only |
| Card title | `18 px / 24 px` | 700 | Budget names and section cards |
| Body | `16 px / 24 px` | 400 | Default readable copy |
| Supporting | `14 px / 21 px` | 400 | Descriptions and metadata |
| Label | `12 px / 16 px` | 650 | Chips and compact labels |

Form controls use at least `16 px` input text to avoid automatic zoom on iOS. Use tabular numerals for money and other values that users compare vertically.

## Spacing and sizing

Use a `4 px` base unit. Preferred spacing tokens are `4`, `8`, `12`, `16`, `24`, `32`, and `48 px`.

- Minimum general touch target: `44 × 44 px`.
- Navigation pills and primary mobile actions: at least `48 px` high.
- Mobile page gutter: `16 px`.
- Card internal padding: `20–24 px`.
- Section gap: `24–32 px`.
- Form control gap: `12–16 px`.
- Keep at least `8 px` of separation between independent touch targets.

## Shape and elevation

- Cards and grouped surfaces: `12 px` radius.
- Compact icon buttons: `10 px` radius unless circular by function.
- Navigation items and chips: fully rounded pills.
- Brand launcher: circle.
- Prefer borders and surface contrast over decorative shadows.
- Launcher elevation: `0 5px 14px rgba(29, 78, 137, 0.22)`.
- Cards use no shadow by default.

## Component guidance

### Budget page heading

Use `Budgeting` as the sole page heading. Do not add a persistent subtitle below it; the budget cards provide the necessary context.

### Budget card

On phones, render each budget line as a card rather than a compressed table row. Its read state contains:

- Name as the card title.
- Description as supporting text.
- Period as a compact chip.
- Amount as a prominent, right-aligned money value.

The complete Iteration 3 editor also needs `active`, `slack_loggable`, funder, destination account, and spend-vs-cap information. Their editing pattern and information hierarchy are still to be designed. Do not squeeze every field into the read-state card by default.

### Activity page

Use `Activity` as the page heading with one short supporting sentence explaining that the ledger contains household expenses recorded through Slack.

- Keep filters together in one bordered surface above the results. Category and sort span the available phone width; the From and To date fields may share a row.
- Show the visible result count beside the filter heading when space permits and directly below it on narrow screens.
- On phones, show one expense per card: merchant and amount form the primary row, category is a compact chip, and date is supporting metadata.
- From medium widths upward, present the same information as an accessible four-column table ordered Date, Merchant, Category, Amount.
- Amounts are right-aligned, use tabular numerals, and remain visually prominent without overpowering the merchant.
- Filters and sorting are client-side for this initial read-only view. Date ranges are inclusive.
- Loading, request failure, no recorded activity, and no filter matches are distinct states. A filtered empty state offers a direct Clear filters action.

### Buttons

- Use one clear primary action per local task area.
- Primary: `color-primary` background with white text.
- Secondary: white surface, `color-primary` text, and a visible border.
- Destructive actions require explicit wording and confirmation; do not style ordinary cancel actions as destructive.
- Disabled controls must remain legible and must not be the only explanation for why an action is unavailable.

### Forms and validation

- Labels remain visible; placeholders are examples, not labels.
- Put validation messages beside the relevant field and explain how to recover.
- Preserve entered values after validation or network errors.
- Use the correct mobile keyboard: numeric input for JPY amounts and appropriate input modes elsewhere.
- Budget edits require a review or confirmation step before the mutation is committed.

### Status and feedback

- Show a skeleton when loading is likely to be perceptible rather than flashing a spinner immediately.
- Empty states explain what is missing and, when possible, provide the next action.
- Errors appear near the failed content with a retry action when retrying is safe.
- Success messages are concise and announced through an appropriate live region.
- Never show an optimistic success state for a financial mutation that the server has rejected.

## Motion

Motion should explain spatial change, not decorate routine actions.

### Navigation motion

- Open over approximately `200 ms` using `transform`, `opacity`, and a subtle scale.
- Reveal items from bottom to top with an approximately `30 ms` stagger.
- Close in reverse over approximately `140 ms`.
- Animate only compositor-friendly properties such as `transform` and `opacity`.
- Under `prefers-reduced-motion: reduce`, use an opacity-only transition or update immediately.

Most other interface transitions should stay between `150–300 ms` and should not block input.

## Responsive behavior

- Start with the phone layout as the base CSS.
- Use a single-column budget-card list on narrow screens.
- Increase gutters and available card width progressively rather than changing interaction patterns at arbitrary device labels.
- Switch layouts only when content no longer fits comfortably; component-level container queries are preferred for reusable sections.
- Do not introduce horizontal scrolling for core forms or financial summaries.
- The permanent navigation treatment for wide desktop screens is still open; until it is designed, keep the same launcher behavior functional at all widths.

## Accessibility baseline

Every frontend contribution must meet these minimums:

- Semantic landmarks and heading order describe the page without visual position.
- All actions are usable by keyboard and have a visible focus indicator.
- Icon-only buttons have specific accessible names.
- The expanded launcher exposes its state and current destination to assistive technology.
- Focus is not trapped behind the navigation scrim.
- Touch targets and spacing meet the sizing rules above.
- Status, error, selection, and progress never rely on color alone.
- Text can enlarge without clipping, overlap, or horizontal scrolling.
- Motion respects the user's reduced-motion preference.
- Safe-area insets keep controls clear of mobile system UI.

## Implementation boundary

The React app uses Material UI, so these tokens should become semantic theme values and component variants rather than scattered CSS literals. The app shell, navigation launcher, and mobile budget cards should be shared components. Page-specific data fetching remains in page features through TanStack Query.

The following decisions are deliberately still open:

- Final brand icon artwork.
- Exact Slack return deep link and fallback behavior.
- Location of the secondary sign-out action.
- Budget card editing interaction, confirmation surface, and contextual action bar.
- Tablet and desktop navigation adaptation.
- Full success, warning, and informational status palette.
- Dark mode.

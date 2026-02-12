# Impossible Accent — Usage Rules

## The Impossible Accent Color

**Color**: Verdigris `#3DBFA0` (muted cyan-green)
**Alternative**: Magenta `#C850C0` (if verdigris doesn't work in testing)

This is the "weird" color channel - the visual element that makes unhinged mode feel slightly off.

---

## WHERE TO USE IT

The impossible accent is **ONLY** for:

### ✅ Focus Visual Borders
- Thin outline (1-2dp) around focused interactive elements
- Keyboard navigation indicators
- Accessibility focus rings

### ✅ Selected List Items
- Small edge highlight (2-3dp left/right border)
- **Never** a full background fill
- Just a subtle accent strip

### ✅ Progress Bars / Sliders
- Thin filament accent line layered over the gold
- 1-2dp overlay on the track
- Enhances the gold without replacing it

### ✅ Active Tab Indicators
- Small underline or side accent
- Combined with gold (not replacing it)

---

## WHERE TO NEVER USE IT

### ❌ Body Text Foreground
The impossible accent is **never** used as text color. Text is always:
- `ArchiveTextPrimary` (white)
- `ArchiveTextSecondary` (light gray)
- `ArchiveTextTertiary` (medium gray)
- `GoldFilament` for accent text (sparingly)

### ❌ Full Background Fills
Never use the impossible accent as a background color for:
- Cards
- Surfaces
- Panels
- Containers
- Any element larger than a thin border

### ❌ More Than ~5% Screen Area
The impossible accent should be **rare and surprising**. If it covers more than ~5% of the visible screen area, you're using too much.

### ❌ Large Interactive Elements
Buttons should use `GoldFilament` (primary accent). The impossible accent is for **selection and focus**, not primary actions.

---

## Gold vs Impossible Accent

**Gold** is the primary UI language:
- Buttons
- Icons
- Selected nav items
- Primary accents
- CTAs

**Impossible Accent** is the exception:
- Focus rings
- Selection indicators
- Progress overlays
- "This is active/selected" states

**Think of it like this:**
- Gold says "click here" or "this is important"
- Impossible accent says "you are here" or "this is selected"

---

## Visual Hierarchy

1. **Gold Filament** - Primary accent, warm, inviting
2. **Impossible Accent** - Secondary indicator, cool, subtle
3. **White/Gray Text** - Content, readable, clear

The impossible accent should **enhance** the gold, not compete with it.

---

## Accessibility

The impossible accent meets WCAG contrast requirements when used for:
- Focus borders (3:1 against background)
- Selection indicators (3:1 against background)

But it's intentionally **not suitable** for:
- Body text (insufficient contrast)
- Small text (would be hard to read)

This is by design - it's a **visual accent**, not a content color.

---

## Testing Checklist

When reviewing code that uses the impossible accent, verify:

- [ ] Used only for focus/selection states
- [ ] Never covers more than ~5% of screen area
- [ ] Never used as text foreground color
- [ ] Never used as a full background fill
- [ ] Gold remains the dominant accent color
- [ ] The accent feels deliberate, not accidental
- [ ] Screenshots still look premium

---

## Code Review Enforcement

These rules are **not enforced by code** - they're enforced by code review. When reviewing PRs:

1. Search for `ImpossibleAccent` usage
2. Verify each use case matches the allowed list above
3. If in doubt, ask: "Could this use gold instead?"
4. Reject any usage that violates the "never" rules

The impossible accent is a scalpel, not a paintbrush. Use it sparingly and deliberately.

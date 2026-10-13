---
name: LED Lamp 360 Design System
description: Neon cyberpunk ambient dark theme for the ESP32 360-degree LED Matrix companion app
colors:
  primary: "#00F2FE"
  secondary: "#4FACFE"
  background: "#090D16"
  surface: "#141B2D"
  surface-border: "#FFFFFF1F"
  text-primary: "#F8FAFC"
  text-muted: "#94A3B8"
  accent-pink: "#FF007F"
  accent-amber: "#FF9900"
  accent-jade: "#10B981"
  terminal-bg: "#040811"
typography:
  display:
    fontSize: "18sp"
    fontWeight: 700
    letterSpacing: "normal"
  headline:
    fontSize: "16sp"
    fontWeight: 700
    letterSpacing: "normal"
  title:
    fontSize: "14sp"
    fontWeight: 700
    letterSpacing: "normal"
  body:
    fontSize: "13sp"
    fontWeight: 400
    letterSpacing: "normal"
  label:
    fontSize: "11sp"
    fontWeight: 600
    letterSpacing: "0.02em"
rounded:
  sm: "12dp"
  md: "14dp"
  lg: "16dp"
  xl: "20dp"
  pill: "24dp"
  full: "9999dp"
spacing:
  xs: "6dp"
  sm: "10dp"
  md: "14dp"
  lg: "16dp"
  xl: "18dp"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "#051329"
    rounded: "{rounded.sm}"
    padding: "12dp 16dp"
  button-connect:
    backgroundColor: "rgba(0, 242, 254, 0.15)"
    textColor: "{colors.primary}"
    rounded: "{rounded.pill}"
    padding: "8dp 16dp"
  card-stat:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.md}"
    padding: "12dp 12dp"
  card-interactive:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.lg}"
    padding: "14dp 14dp"
---

# Design System: LED Lamp 360

## Overview

**Creative North Star: "Cyber Luminescence"**

A purposeful dark-first interface crafted for high-contrast nighttime device control. Designed specifically as the companion app for an ESP32-powered 360-degree LED Matrix cylinder lamp, the aesthetic balances the tactical clarity of an instrument console with the electric glow of neon light art.

Surfaces emerge softly from an ultra-deep obsidian canvas (`#090D16`), framed with subtle translucent borders (`#FFFFFF1F`) rather than hard lines. Vibrant chromatic accents—Cyan Neon (`#00F2FE`), Cyber Pink (`#FF007F`), and Warm Amber (`#FF9900`)—are deployed with surgical discipline to signify live state, signal telemetry changes, and celebrate hardware connectivity.

**Key Characteristics:**
- **Zero-Glare Nighttime Usability**: Low-luminance dark backgrounds prevent eye fatigue in dark rooms.
- **Luminous Telemetry Highlights**: Critical metrics (voltage, tilt angles, battery) glow with purpose against dark card containers.
- **Haptic Tactility**: Generous touch zones (48dp minimum) and smooth animated state transitions match the physicality of the physical lamp.

## Colors

Palette character: Electric neon lights punctuate a deep midnight atmospheric backdrop.

### Primary
- **Cyan Neon** (`#00F2FE`): The hero signature color. Represents active BLE connection, selected interactive modes, slider thumbs, and primary CTA triggers.

### Secondary
- **Blue Accent** (`#4FACFE`): Secondary gradient partner for buttons, header logos, and state transitions.

### Neutral
- **Deep Void Background** (`#090D16`): Root application canvas. Grounding plane that keeps contrast ratio maximal without true OLED black harshness.
- **Card Surface** (`#141B2D`): Structural elevation layer for grouped controls, settings panels, and telemetry boxes.
- **Translucent Border** (`#FFFFFF1F`): 12% white alpha stroke separating elevated surfaces from the background void.
- **Text Primary** (`#F8FAFC`): High-legibility crisp off-white for primary labels and values.
- **Text Muted** (`#94A3B8`): Subdued slate grey for supporting descriptions and secondary metrics.

### Status & Accents
- **Cyber Pink** (`#FF007F`): Warning states, roll gyro indicators, and abort commands.
- **Warm Amber** (`#FF9900`): Bluetooth scanning in progress and charging indicators.
- **Jade Green** (`#10B981`): Connected confirmation and full battery state.

**The Glowing Indicator Rule.** Bright neon colors are reserved strictly for active hardware states, interactive selections, and live telemetry. Inactive controls remain muted slate grey.

## Typography

**Display & Headline Font:** Roboto / System San-Serif
**Terminal Font:** Monospace

**Character:** Functional, high-density telemetry typography with strict sizing hierarchy designed for rapid scanability.

### Hierarchy
- **Display** (Bold 700, 18sp): App header branding title.
- **Headline** (Bold 700, 16sp): Section titles (Color Picker, OTA Firmware, Notification Bridge).
- **Title** (Bold 700, 14sp): Card headings, mode names, sensor parameter names.
- **Body** (Regular 400, 13sp): Primary descriptions, helper instructions, and parameter readouts.
- **Label / Subtitle** (Bold 600, 11sp): Telemetry stat cards, category tags, preset color swatches.
- **Monospace** (Regular 400, 11sp): BLE raw packet logs and UART diagnostic output.

**The Scale Fidelity Rule.** All typography must use `sp` units to honor Android system font scaling and prevent clipping when accessibility text scaling is active.

## Layout

- **Spatial Rhythm**: Multiples of 4dp and 8dp. Standard screen horizontal gutter is 16dp.
- **Grid Layout**: 2-column uniform grid for Mode selection cards (`LazyVerticalGrid`, 10dp spacing); 3-column grid for color swatches.
- **Vertical Flow**: Fixed header bar, horizontal telemetry bar, hero quick-action brightness card, followed by tabbed content container.
- **Bottom Navigation**: 5-tab Material 3 NavigationBar fixed to the bottom with 48dp touch targets and selected indicator pill.

## Elevation & Depth

Surfaces rely on tonal layering and luminous gradient borders rather than simulated drop shadows.

- **Canvas (Elevation 0)**: `#090D16` root container.
- **Panels & Cards (Elevation 1)**: `#141B2D` surface with 1dp border `#FFFFFF1F`.
- **Selected States (Elevation 2)**: 18% alpha Cyan gradient fill with bright 1dp Cyan border glow (`#00F2FE`).

**The Flat-Glow Rule.** Surfaces rest flat on the dark substrate. Depth is communicated by border luminescence and alpha tinting, not drop shadows.

## Shapes

- **12dp Radius**: Standard compact buttons, logo tile, terminal log console.
- **14dp Radius**: Stat cards, color presets, notification mapping list rows.
- **16dp Radius**: Interactive mode selection tiles and major section cards.
- **20dp Radius**: Brightness and master power card.
- **24dp (Pill) Radius**: Bluetooth connect status button and chip selectors.
- **Circle (Full)**: Master power toggle button, indicator status dots, and preview avatars.

## Components

### Buttons
- **Shape**: Pill (24dp) for connection status; Rounded (12dp) for action buttons.
- **Primary Action**: Cyan fill (`#00F2FE`), dark ink text (`#051329`), 48dp minimum touch target height.
- **Outlined / Tonal**: 15–20% alpha tint container with 1dp gradient outline.

### Cards
- **Corner Radius**: 14dp to 16dp.
- **Background**: `#141B2D` with 1dp subtle border.
- **Active Selection**: Animated border color transition to Cyan Neon (`#00F2FE`) with subtle gradient wash.

### Sliders
- **Active Track**: Neon Cyan (`#00F2FE`).
- **Inactive Track**: 20% white alpha (`0x33FFFFFF`).
- **Thumb**: Crisp white circle with smooth drag feedback.

### Status Chips & Badges
- **Shape**: Circle badge (8dp) for connection status (Green = Connected, Amber = Scanning, Cyan = Idle).

## Do's and Don'ts

### Do:
- **Do** maintain dark surface backgrounds (`#090D16` / `#141B2D`) across all screens to protect nighttime ambient comfort.
- **Do** ensure all interactive buttons and icons have a touch target of at least 48×48 dp.
- **Do** pair icon glyphs with explicit Vietnamese text labels for hardware modes and telemetry values.
- **Do** use `sp` units for all text to respect Android system accessibility font scaling.

### Don't:
- **Don't** use pure black (`#000000`) or harsh un-tinted grey for card surfaces.
- **Don't** use low-contrast text (grey text on dark blue surfaces must maintain at least 4.5:1 contrast).
- **Don't** introduce light theme surfaces without user configuration; dark appearance is the authoritative default.
- **Don't** use drop shadows that disappear on dark backgrounds; use luminous border accents instead.

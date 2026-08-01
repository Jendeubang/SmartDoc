# Morandi Preview Design QA

## Comparison target

- Source visual truth: `C:\Users\JENDEU~1\AppData\Local\Temp\codex-clipboard-4ed4a702-9624-402d-907a-17b00f212955.png`
- Implementation screenshot: `F:\DocAI\smartdoc-morandi-rendered.png`
- Source dimensions: 670 × 952 px
- Implementation viewport: 1440 × 1024 CSS px, device scale factor 1
- Implementation screenshot: 1440 × 1024 px
- State: desktop `/morandi-preview`, default “我的工作台” state

The reference is a palette card rather than a complete app screen. The review therefore compares palette, tone, hierarchy and surface treatment rather than attempting a 1:1 layout match.

## Evidence

- Source palette samples: pale green `#ECF3E1`, mist pink `#DECDD5`, muted lavender `#AB9FCD`.
- Implementation token mapping: `--sd-bg: #EDF4E2`, `--sd-pink: #DFCED6`, `--sd-lavender: #ACA0CE`.
- Browser-rendered implementation samples: page surface `#FEFDF7` / `#FFFDF9`, active navigation `#EEE8F5`, green state surface `#E8EFDF`, muted surface `#F7F3ED`.
- Primary interactions verified: navigation state switch, prompt-to-input action, AI task submission feedback, responsive CSS breakpoints present.
- Browser console: no runtime errors after adding the client-side `window.global` compatibility shim.

## Findings

- [Resolved P1] The first preview render was blank because SockJS requires a browser `global` alias.
  - Fix: replaced Vite's global text replacement with an early `window.global = window` shim in `index.html`.
  - Post-fix evidence: browser DOM contains `.preview-shell`; production build completes successfully; no console runtime error remains.

- [Resolved P2] The task sender arrow could lose contrast against the muted purple button.
  - Fix: the sender now uses a solid `#74698E` background, forced white icon color, a 42 px hit target and visible hover/focus ring.
  - Post-fix evidence: browser computed styles report button background `rgb(116, 105, 142)`, button text `rgb(255, 255, 255)` and icon `rgb(255, 255, 255)`.
No actionable P0/P1/P2 visual issues remain for this palette preview.

## Required fidelity surfaces

- Fonts and typography: product UI uses the existing system sans-serif stack for legibility. The source's decorative serif display type is intentionally not copied because it is artwork, not application UI typography.
- Spacing and layout rhythm: the preview uses a 238 px sidebar, 24 px surface radius, controlled 14–22 px grid gaps and generous page margins to preserve the quiet, airy reference mood.
- Colors and tokens: the three supplied palette anchors map directly to the global Morandi tokens and semantic status surfaces; no dark surface is used.
- Image quality and asset fidelity: the source is a color reference with no product imagery that needs reproduction. The preview uses the existing Element Plus icon set for UI icons.
- Copy and content: the screen uses SmartDoc product copy, document examples and Agent task language rather than reproducing the palette card's decorative text.

## Follow-up polish

- [P3] Apply the same token system to Login, Editor, Agent Workbench and AIOps after the user approves this direction.
- [P3] Add a logo asset and a refined Chinese display font only after selecting a brand direction and confirming licensing.

Final result: passed

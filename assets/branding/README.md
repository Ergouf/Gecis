# Gecis fox icon

`fox-master.png` is the shared transparent artwork. `app-icon.png` is the
rounded-square charcoal tile used by Windows and Android legacy launchers.

Regenerate all sizes from the repository root:

```sh
python windows/scripts/make_icons.py
```

Requires Pillow. The script generates Windows PNG/ICO sizes, Android density
variants, adaptive foregrounds inside the mask safe zone, and alpha-based
monochrome variants. Android's adaptive background is `#202126`, matching the
Windows tile. Do not redraw platform icons independently.

Artwork generated with the built-in image generation tool, using the previous
orange fox as the identity reference. Prompt:

> Redraw the attached previous fox logo for Gecis, a refined, calm study assistant. Use case: logo-brand. Reference image is identity and palette reference. Preserve front-facing symmetric fox head, long pointed ears, terracotta orange forehead, ivory cheeks, gently closed calm eyes, small dark nose. Simplify substantially into elegant precise flat vector-like shapes and flowing curves, no origami facets, no 3D bevel, no glossy highlights, no shadows, no text, no extra symbols. Quiet intelligent expression, sophisticated not childish, balanced iconic silhouette readable at 24px. Warm muted burnt-orange and ivory, charcoal eyes. Deliver one fox head on genuine TRANSPARENT background, no rounded square tile or background. Square 1024x1024 composition, fox centered occupying about 80% width/height, all ear tips in frame. This will be the common master for Android adaptive launcher and Windows app icon. Smooth crisp edges, clean professional app icon.

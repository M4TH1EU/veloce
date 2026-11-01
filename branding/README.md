# Veloce artwork

The rounded V is shared by the phone UI, car launcher, Android launcher icon,
splash screen, and connection notification. Graphite (`#101010`–`#36393E`) and
ivory (`#F3F2F0`) match the default cabin theme.

- `veloce-mark.svg`: transparent, scalable mark.
- `app-icon.svg` / `app-icon-512.png`: square, 512 × 512 store icon. No baked-in
  mask or shadow; the store applies its own treatment.
- `feature-graphic.svg` / `feature-graphic-1024x500.png`: 1024 × 500 listing art.

Android uses separate 108 dp foreground/background layers under
`app/src/main/res/`, with the mark inside the adaptive-icon safe zone. The
foreground also supplies the monochrome layer for Android themed icons.

Regenerate the PNG exports from this directory with ImageMagick:

```sh
magick -background none app-icon.svg -strip PNG32:app-icon-512.png
magick -background none feature-graphic.svg -strip PNG24:feature-graphic-1024x500.png
```
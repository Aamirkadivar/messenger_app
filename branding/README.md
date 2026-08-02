# Branding source artwork

Original, full-resolution icon artwork. Nothing here is loaded at runtime —
these are the masters that the per-platform icons were generated *from*, kept
so they can be regenerated at a different size or style later.

| File | What it is |
| --- | --- |
| `app-icon-source.png` | 1024px master of the shield mark. The current app icons on every platform come from this. |
| `app-icon-source-early.jpg` | Earlier variant of the same mark, kept for reference. Not used. |

## What was generated from these

- **Windows** — `windows_app/resources/icons/app_icon.png` (window + title bar)
  and `app_icon.ico` (executable icon via `resources/app_icon.rc`, and the tray
  icon, which uses the `.ico` so Windows can pick the size the notification
  area asks for).
- **Android launcher** — `application_android/app/src/main/res/mipmap-*/ic_launcher*.png`.
- **Android notification** — `application_android/app/src/main/res/drawable-*dpi/ic_notification.png`.
  Note this one is *not* a scaled-down app icon: a notification small icon keeps
  only its alpha channel and is repainted by the system, so it was built from a
  flat black-on-white silhouette of the mark, converted to white-on-transparent.
  Colour artwork fed in directly arrives as a solid white square.

`windows_app/resources/branding/*.svg` is an older speech-bubble logo that
predates this mark and no longer matches the shipped icons.

# Launcher icon

`streamshield-logo.png` is the app's logo (a red shield with a play symbol on a dark navy rounded
square). `make_icons.py` builds the launcher icons from it:

| File (in `app/src/main/res/mipmap-*/`) | Used for |
|---|---|
| `ic_launcher_foreground.png` | Adaptive icon (Android 8+): the logo's navy gradient filling the 108 dp layer, the shield at half its height, so every launcher mask (circle, squircle, rounded square) shows the whole shield |
| `ic_launcher_monochrome.png` | Themed icons (Android 13+): the shield's shape, play symbol cut out |
| `ic_launcher.png`, `ic_launcher_round.png` | Android 7.x launchers: the logo's rounded square, and a round version |

`mipmap-anydpi-v26/ic_launcher*.xml` combine the layers with `drawable/ic_launcher_background.xml`
(a navy fill, hidden behind the opaque foreground).

To change the logo, replace the PNG and run (Python 3 with Pillow and NumPy):

```sh
python3 tools/icons/make_icons.py tools/icons/streamshield-logo.png app/src/main/res /tmp/icon-preview.png
```

The measurements at the top of the script (the rounded square's edges and corner radius, the
shield's top and bottom) are for this logo; a different image needs new ones. The preview shows
the icon in the common launcher shapes, as a themed icon and at small sizes.

#!/bin/sh
# Renders the app icon for every platform from the SVGs in this folder.
# Needs rsvg-convert (librsvg) and ImageMagick. Run it again after changing an SVG.
set -eu
cd "$(dirname "$0")/.."

# Android: the adaptive icon's foreground at each density (108 dp); the background is a colour resource.
res=androidApp/src/main/res
for density in mdpi:108 hdpi:162 xhdpi:216 xxhdpi:324 xxxhdpi:432; do
    dir=$res/mipmap-${density%:*}
    mkdir -p "$dir"
    rsvg-convert -w "${density#*:}" -h "${density#*:}" art/android-foreground.svg -o "$dir/ic_launcher_foreground.png"
done

# iOS and the web's home-screen icon: square and opaque, as Apple rounds the corners and rejects transparency.
ios=iosApp/iosApp/Assets.xcassets/AppIcon.appiconset
mkdir -p "$ios"
rsvg-convert -w 1024 -h 1024 art/app-icon-square.svg | magick png:- -alpha off "PNG24:$ios/AppIcon.png"
rsvg-convert -w 180 -h 180 art/app-icon-square.svg | magick png:- -alpha off PNG24:webApp/src/wasmJsMain/resources/apple-touch-icon.png

# Desktop window and package icon, and the web favicon.
mkdir -p desktopApp/src/main/resources
rsvg-convert -w 512 -h 512 art/app-icon.svg -o desktopApp/src/main/resources/icon.png
cp art/app-icon.svg webApp/src/wasmJsMain/resources/favicon.svg

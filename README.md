# TV to Sonos

One-screen Google TV app: pick a Sonos room and the TV's sound plays there over Wi-Fi.

## Build
Push this folder to a GitHub repo. The workflow in `.github/workflows/build.yml` builds the APK
automatically: open the repo's **Actions** tab → latest run → download **TvToSonos-apk**.

## Install on the Google TV
1. Google TV: Settings → System → About → click *Android TV OS build* 7 times (turns on Developer options).
2. Install the free **Downloader** app (or **Send files to TV**) and open the APK with it.
3. Allow "Install unknown apps" for that app when asked.

## Use
1. Open **TV to Sonos** → it lists the Sonos rooms on the Wi-Fi.
2. Pick the room → allow the audio permission and the "start recording/casting" prompt.
3. Turn the TV's own volume down. Use the app's Vol −/+ (or the Sonos app) for Sonos volume.

## Limits
- Netflix, Disney+, Prime Video and other protected apps block audio capture: you'll hear silence.
- Sound runs a few seconds behind the picture (Sonos buffers live streams).
- The Google TV and Sonos must be on the same Wi-Fi network.

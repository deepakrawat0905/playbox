# Play Box Pro — Native Android TV

This project is a native Android TV conversion of the supplied `Samsung iptv` React/Tizen source.

**No WebView is used.** The UI is implemented with Kotlin + XML, RecyclerView, Android focus navigation and Media3/ExoPlayer.

## Source-of-truth features ported

- Play Box Pro dark TV design tokens: #05070D background, #0A0E18 elevated surfaces, #0E1420 panels, #4D8DFF accent.
- 64dp / 40dp TV safe-area geometry equivalent to the original 4rem / 2.5rem at 1920x1080.
- Collapsed/expanded navigation rail.
- Activation / MAC/device identity flow.
- Play Box Pro licensing API base: `https://api.proplaybox.com`.
- `getDevicePlaylistSubscription` route with the three source fallbacks.
- Direct M3U/M3U8/JSON playlist URL mode.
- Xtream Codes API authentication and Live/VOD/Series category and stream endpoints.
- Xtream movie and series detail endpoints.
- XMLTV URL generation.
- Robust M3U parser: EXTINF, group-title, tvg-logo, tvg-id, tvg-chno, EXTGRP, CRLF/LF, redirects, JSON playlists, URL query playlists, movie/series detection.
- Home, Live TV, Movies, Series, Search, Favorites, Continue Watching, Recently Watched, Playlists, Settings, Device Info and About screens.
- Native Media3 player for HLS/MP4/TS streams.
- Android TV D-pad focusable controls.
- Responsive 6-column TV grid and TV-safe spacing.
- Native persistent local state for account, favorites and recent playback.

## Build

Open the root folder in Android Studio. Use **JDK 21**.

Then Sync Project with Gradle Files and run `:app:assembleDebug`.

APK output:
`app/build/outputs/apk/debug/app-debug.apk`

## Direct playlist

On Activation choose **Xtream / M3U**.

For M3U paste the complete URL, including query parameters, for example a provider URL ending in `get.php?...type=m3u_plus...`. The URL does not need to end in `.m3u`.

For Xtream enter the server URL, username and password.

## Native architecture

React components → Kotlin Fragments/Adapters
CSS variables → Android resources/XML drawables
Axios → OkHttp
hls.js → Media3/ExoPlayer
localStorage → SharedPreferences (with native repositories)
Tizen device/network APIs → Android APIs
Tizen remote handling → Android TV focus/D-pad

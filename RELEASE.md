<!-- 1.3 - Copyright (C) 2026 Stouthart. All rights reserved. -->

# Release notes

Highlights per version, newest first.

## 1.3 - November 2026

- **New colours:** white and light-grey surfaces with one clear blue in light mode, near-black greys in dark; the icon on dark grey, with a touch of that blue.
- **Group rooms:** a new setting, off at first. When on, the Rooms card's checks work as check boxes: the checked rooms play together, in sync; checking a room adds it at their volume, after asking when it's playing something of its own, such as the TV; unchecking takes it out, and the others play on from where they were, also when it led them. When off, a tap chooses a room, as before, and its checks show the rooms grouped with it. Turning it off while rooms play together ends that: the leading room plays on, and the others go quiet.
- **Group volume:** with rooms together, such as Kitchen + 1, the volume slider, mute and the volume buttons act on all of them: every speaker to the same level, rather than the Sonos app's shares.
- **Grouped elsewhere:** when rooms are grouped or ungrouped outside the app, as in the Sonos app or by Spotify, every screen follows within a few seconds, Now Playing and the queue included, rather than staying on the group it opened with.
- **Now Playing at once:** it opens with the cover, the title and the volume the mini player showed, the cover already sharp, rather than after a second or two; so do the other screens' mini players.
- **A larger cover on taller screens:** Now Playing's controls and Queue stay at the bottom, and the cover takes the room above them.
- **Tiles show a track's length:** a tile is too narrow for the artist and format as well; the list keeps them.
- **Settings in view:** opening the Settings card scrolls all of it into view, its lower edge included.
- **Source on Now Playing:** the format line starts with where the music comes from, as the Sonos app names it, such as `MinimServer · FLAC 96 kHz 24-bit`, `Spotify · Lossless 44.1 kHz 16-bit` or on the TV `HDMI · Dolby Atmos (MAT)`.
- **Room icons:** each room shows the kind of its main speaker, from its model: soundbar, speaker, portable, Sub or Amp; and the model itself, as the Sonos app names it, such as Era 300, before the address. The app draws them, sharp at any size, so no picture is fetched; a room it hasn't read yet shows a speaker until it has. The Music library gets an icon of its own.

## 1.2 - November 2026

- **Music library:** the Sonos system's own library, the shared folders its speakers play from, next to the UPnP/DLNA servers, with the Sonos app's sections: Artists, Albums, Composers, Genres, Songs, Imported playlists and Folders. **Scan for new content** on its first level picks up new files, such as fresh podcast episodes.
- **Play in Browse:** on an album, or any level with tracks, **▶** in the top card plays them all from the first, the same as a tap on the first track.
- **Volume buttons:** while UPnP Cast is in front, the phone's volume buttons set the room's volume, by the new **Volume button step** setting, ±1 to ±10 (±2 by default).
- **The TV's input format** on Now Playing, such as `Dolby Atmos (MAT)` or `PCM 2.0`.
- **Long titles** in Browse's top card get a little smaller to fit their two lines before they're cut short.
- **Sturdier:** the volume stays with its room, also when you switch rooms mid-change or leave the screen; a tap in the Queue checks the queue hasn't changed elsewhere first; requests stay on the WiFi and go to addresses only; long or endless lists can't keep the app paging; nothing loads for a screen you left; seeking works in any language; Now Playing fits a small window; and the sliders follow a keyboard and TalkBack.
- **Settings fold away:** a tap on the title opens or closes them; closed at first, then as you left them. Inside, a new order: Volume button step, Polling interval, Appearance.
- **Buttons and choices at one height.**

## 1.1 - November 2026

- **Android 14 and 15** as well, besides 16 and 17; untested on those versions so far.
- **Home** in Browse goes back to the opening screen from any level, and servers show an arrow, as folders do.
- **Spotify on the speaker:** Now Playing shows its cover, which the speaker fetches, and whether the stream is lossless.
- **Sources without track details are named:** TV for the TV input, and Spotify for Spotify Connect while paused. A control the source doesn't offer, such as Pause on the TV, says "Not supported on this source".
- **Now Playing's progress** counts evenly at any polling interval, and stays put while a seek lands. Previous, Next, Shuffle and Repeat are bigger, and the Queue button is calmer.
- **The mini player appears with the first thing the room has loaded**, playing or paused, and then stays on every screen. Without WiFi or a Sonos room, it hides and Now Playing closes.
- **New icon:** a gradient in the logo's blues, with a light-blue note on the cast screen.

## 1.0 - November 2026

The first release: a UPnP/DLNA music library on Sonos speakers, browsed and played from Android. The APK is attached to the release.

- **Finds** the Sonos rooms and the UPnP/DLNA servers on the network by itself, in about half a second the first time and at once from then on, and again when the phone's WiFi comes back or changes.
- **Stays on the WiFi:** without it, the screens say so and nothing goes over mobile data; with the home internet down, it still plays from the server.
- **Browses** a server's own categories, as a list or as tiles, with covers and each track's format and length, such as `FLAC 44.1 kHz 16 bit · 5:40`.
- **Plays on Sonos:** a tap plays a track with the rest of the list queued around it, and **⋯** offers Play now, Play next, Add to end of queue and Replace queue. The speaker fetches the music from the server itself.
- **Mini player, Now Playing and Queue**, in the Sonos app's terms: Play/Pause, volume and mute on every screen; seek, Previous, Next, Shuffle and Repeat on Now Playing; the playing track marked in the queue, and Clear queue.
- **Files up to 48 kHz**, Sonos's limit over UPnP: a track above it is greyed out, unless the server offers a version within it.
- **Appearance** (System default, Light mode, Dark mode) and a **Polling interval** from 1 to 10 s.
- **Small and quiet:** 157 KB, no libraries, nothing running in the background, and a start in about 140 ms.

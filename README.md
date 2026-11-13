<!-- 3.0 - Copyright (C) 2026 Stouthart. All rights reserved. -->

# Play a UPnP music library on Sonos

A small Android app that browses a **UPnP/DLNA** media server, such as **MinimServer**, and plays its music on **Sonos** speakers, with the queue, the volume and Now Playing at hand. The Sonos system's own **Music library**, the shared folders its speakers play from, is there too. The Sonos app can't play from a UPnP server. UPnP Cast hands the server's own links to the speaker, which then fetches the music itself: the phone isn't in the audio path, so playback goes on when the phone sleeps. No cloud, no ads, no account, and nothing beyond Android itself: the app is 288 KB.

<p align="center"><img src="docs/screenshot-astra-home.png" width="240" alt="UPnP Cast on a RedMagic Astra: the Kitchen and the Living Room, the Living Room chosen, Favorites, MinimServer and the Music library, with Whispers of Rain playing in the mini player docked at the foot"> <img src="docs/screenshot-astra-minimserver.png" width="240" alt="UPnP Cast browsing MinimServer on a RedMagic Astra: its 12 top-level views as a grid of covers, with the mini player docked below"> <img src="docs/screenshot-astra-now-playing.png" width="240" alt="Now Playing in UPnP Cast on a RedMagic Astra: Whispers of Rain (Continuous Mix) by Alina Bzhezhinska and Tulshi from MinimServer in FLAC 48 kHz 24-bit, with the Queue, the Living Room and the heart at the foot"></p>

This is **version 3.0**. The [release notes](RELEASE.md) list what each version changed.

## What it does

| Screen         | What it shows                                                                                                         |
| -------------- | --------------------------------------------------------------------------------------------------------------------- |
| Opening screen | The Sonos rooms, each with an icon for its kind of speaker and its model, such as Era 300, Favorites, the UPnP/DLNA servers on the network and the Sonos Music library, and the settings |
| Browse         | A server's own categories, as a list or as tiles, with covers and each track's format, such as `FLAC 44.1 kHz 16-bit`, and in tiles just its length; in an album each track's number before its title |
| Mini player    | Docked at the foot of every screen but the Queue, edge to edge, whatever plays; not without WiFi or a Sonos room: the cover, the title, the room and artist, from 600dp wide the heart, Play/Pause with the track's progress on its edge, volume and mute; a title too long for its row slides to its end and back once |
| Media player   | While the room plays, also with the app in the background: Android's own media player in Quick Settings and on the lock screen, with the cover, the title and artist, the progress to drag, Play/Pause, Previous and Next; the phone's volume buttons set the room's volume |
| Now Playing    | A large cover, the source and the format, such as `MinimServer · FLAC 96 kHz 24-bit`, `Qobuz · MP3` or on the TV `HDMI · Dolby Atmos (MAT)`, the format in green when it's lossless, a progress bar to seek, Shuffle, Previous, Play/Pause, Next, Repeat, Quick skip if set, volume and mute; at the foot the Queue, the room and a heart to save what plays as a favourite, filled in green while it is one |
| Queue          | The room's queue with the playing track in blue; a tap plays a track, ✕ removes one, Clear queue empties it, and ‹ goes back to Now Playing |
| Room settings  | From the icon on the Rooms card, or the room's name in the mini player or on Now Playing: each room's own settings, as in the Sonos app: Trueplay, EQ, Volume limit, Sub audio, Home theater and Hardware |

## Requirements

- **Android 14** or newer
- **Sonos** speakers on the same network
- A **UPnP/DLNA media server** with your music, such as MinimServer, or shared folders in the Sonos **Music library**
- Music files up to **48 kHz**, see [Hi-res files](#hi-res-files)
- To build it yourself: **JDK 21** and the **Android SDK** command-line tools with `platforms;android-37.0` and `build-tools;37.0.0`. The project brings its own Gradle.

Developed and tested on a **Samsung Galaxy S25 Edge** (One UI 9, Android 17) and a **RedMagic Astra** (REDMAGIC OS 11, Android 16), with a **Sonos Beam Ultra** and a Sub Mini in one room and an **Era 300** in another, also grouped (Sonos 27.3), and **MinimServer 2.2**. Other Sonos speakers and UPnP/DLNA servers should work alike, and so should Android 14 and 15, but these are untested.

## Installation

Download `UPnP-Cast-3.0.apk` from the [latest release] and open it on the phone; Android asks to allow installing apps from that source first. Or install it from a computer with the phone connected over USB or wireless debugging:

```bash
adb install UPnP-Cast-3.0.apk
```

### Building it yourself

```bash
git clone https://github.com/Stouthart/UPnP-Cast.git
cd UPnP-Cast
echo "sdk.dir=$ANDROID_HOME" >local.properties   # the SDK's location
./gradlew assembleRelease
adb install app/build.nosync/outputs/apk/release/app-release.apk
```

`./gradlew test` runs the unit tests of the address, private range and subnet checks, the household's rooms and groups from a speaker's topology, a search answer that names its own address, reading a reply by its length, in chunks or up to the close, within its limits and deadline, and refusing a malformed one, whether a reply is the answer to what was asked, its values read in the same pass, the seek format, a track's length within bounds, the position clock as it counts on, follows the room and lands a seek, the cover's size on Now Playing and which of a server's covers it takes, the room icons, the source, the track format and whether it's lossless, a stream's link as its title and the speaker's connecting state, a music service's number and name, a favourite as the speaker saves it, a queue read whole from one revision, a server's entries, a track's format and cover however its metadata is spelt, Room settings' values and the Sub check, on the computer.

Your build is signed with your own debug key, the release APK with mine. Android won't update one with the other, so uninstall the downloaded app before installing your own build.

[latest release]: https://github.com/Stouthart/UPnP-Cast/releases/latest

## Setup

1. Open **UPnP Cast**. On Android 17, allow **local network access** when asked; older versions don't ask.
2. The Sonos rooms and the UPnP/DLNA servers appear within about a second, and at once from then on, with the **Music library** last when the Sonos system has a shared folder. With more than one room, each has a circle, as in the Sonos app: ✓ for the rooms that play together, ○ for the others. Tap a room to choose the one to play in: its name turns blue, and the player controls it, together with the rooms grouped with it. Tap a room's circle to group or ungroup it. A room you check joins the others and plays what they play, in sync and at their volume; when it's playing something of its own, such as the TV, the app asks first, as joining stops that. One you uncheck leaves and goes quiet, while the others play on, also when it was the one leading them. One room always stays checked.

## Usage

- **Browse:** tap a server. Its categories appear as the server names them. The button at the right of the top card switches between a list and tiles, the house beside it goes back to the opening screen, and Back goes up a level. Albums remember their own choice, a list at first; everything else shares one, tiles at first. A long title in the top card gets a little smaller to fit its two lines before it's cut short. The top card scrolls away with the list, as the opening screen's cards do.
- **Play:** tap a track. It plays in the room with the rest of the list queued around it, as in an album; this replaces the room's queue. The music starts as soon as that track is in the queue, and the rest go in while it plays; another tap meanwhile plays at once. On a level with tracks, such as an album, **▶** in the top card plays them all from the first, the same as a tap on the first track. The **⋯** on a track or an album offers **Play now**, **Play next**, **Add to end of queue** and **Replace queue**.
- **Favorites:** the favourites your Sonos system keeps, saved in the Sonos app or elsewhere, such as a radio station or a playlist. A tap on the Favorites panel opens them in Browse, as a list or as tiles; a tap on one plays it in the chosen room: a station starts, a playlist or album replaces the queue. A shortcut, to a page of a service such as Sonos Radio's, can't be played over UPnP, so it's greyed out. In the list, the **✕** at a favourite's end removes it at once. The panel shows while there's a favourite to play; they're read again each time the opening screen or Favorites shows, so one saved or removed meanwhile, on Now Playing or in the Sonos app, is there.
- **Music library:** the sections are the Sonos app's: **Artists**, **Albums**, **Composers**, **Genres**, **Songs**, **Imported playlists** and **Folders**. The refresh icon on its first level is the Sonos app's **Scan for new content**: the speaker scans the shared folders, and the icon turns blue and the top card says so until it's done, for half an hour at most.
- **Mini player:** docked at the foot of the screen, edge to edge, in the cards' colour, which runs on under the gesture or navigation bar. Tap it for Now Playing, or the room's name in it for Room settings. From 600dp wide, as on a tablet either way round, it also has Now Playing's heart, left of Play/Pause and as large; on a phone that's on Now Playing alone. The edge of the Play/Pause circle fills clockwise from the top as the track plays, white in dark mode and grey in light mode; a radio stream or the TV, which have no length, show none. The speaker icon mutes and unmutes. With more than one room checked, the player shows them as one, such as **Kitchen + 1**, and its controls act on all of them: the volume sets every speaker to the same level, unlike the Sonos app, which moves them all by the same share. When rooms are grouped or ungrouped elsewhere, as in the Sonos app or by playing Spotify on one of them, every screen follows within a few seconds.
- **Volume buttons:** while UPnP Cast is in front and the mini player, Now Playing or the Queue shows, the phone's volume buttons set the room's volume, by the **Volume button step**; held, they keep going. While the media player shows, they do so from anywhere, also in another app or with the screen off. Otherwise they're the phone's own; so they are right after choosing another room, until its volume has been read.
- **Media player:** as soon as a screen shows the room playing, Android's own media player follows it, in Quick Settings and on the lock screen, as for the Sonos app: the cover, the title and artist, the progress, which you can drag, and Play/Pause, Previous and Next, acting on the room. A tap on it opens the app as you left it. It stays while you use other apps or the phone sleeps, following what plays, until the room has been paused, stopped or out of reach for 10 minutes, or you swipe it away; then it's gone until a screen shows the room playing again. Whether its icon also shows in the status bar is the phone's choice: a Samsung's shows it, a phone closer to Android's own, as the RedMagic Astra, doesn't.
- **Now Playing:** drag the progress bar to seek. **Previous** goes back to the track's start once it has played more than 5 seconds, else to the track before, as in most players; on a radio stream or the TV it's the speaker's own. With **Quick skip** set in App settings, the buttons beside Play skip back or forward by its step, shown in their arrows; they're greyed out where the bar is, as on the TV or a radio stream. Shuffle and Repeat light up while on; Repeat goes from off to all to one. The row at the foot is the room's, as in the Sonos app: the queue icon at the left opens its queue, its name in the middle opens Room settings, and the **heart** at the right saves what plays as a Sonos favourite and fills in green; a tap on the filled heart removes it again. It shows for a track from a server or the Music library and for a music service's radio station; a server's track shows among the Favorites here, not in the Sonos app, which hides those. Close the screen with **⌄**, a swipe down, or Back.
- **Room settings:** the icon at the top right of the Rooms card, or the room's name in the mini player or on Now Playing, opens a sheet with the rooms, the one the player controls already open; a tap on another opens its own settings right under it and closes the one before. As the Sonos app has them: **Trueplay tuning**; **EQ** (Bass, Treble, Height level, Loudness); **Volume limit**; **Sub audio** with a bonded Sub (Sub, Sub level, and Phase control while Trueplay is off); **Home theater** on a soundbar (Speech Enhancement, Night Sound, TV dialog sync); and **Hardware** (Status Light, Touch controls). Only what the room's speaker has shows, and a change applies at once.
- **Queue:** tap a track to play it; its **✕** removes it at once. **Clear queue** asks first; the music stops only if the room plays its queue, not a favourite played on its own, a radio station or the TV. The top card scrolls away with the tracks, as in Browse. **‹**, or Back, goes back to Now Playing.

### App settings

The App settings card opens with a tap anywhere on it and closes with a tap on its title; it starts closed each time the app starts, and stays as you left it while the app runs. When it opens, all of it scrolls into view. Its last line is the app's version and copyright.

| Setting            | What it does                                                                                                                                                       |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Volume button step | How far a press of the phone's volume buttons moves the room's volume, from ±1 to ±10 (±2 by default)                                                             |
| Quick skip         | **Off**, at first, or 5 to 60 s in steps of 5: Now Playing then has a button on each side of Play that skips back or forward that far, from where the track is. A skip back stops at the start; one forward past the end goes on to the next track. Quick taps add up |
| Polling interval   | How often the player asks the room what is playing, from 1 to 10 s (2 s by default), for fewer requests on the network while you listen. Now Playing's progress and the mini player's still move every second |
| Appearance         | **System default** follows the phone; **Light mode** and **Dark mode** set the app's own                                                                           |

## Hi-res files

Sonos plays files up to 48 kHz from a UPnP server. A track above that is greyed out, its format in orange, and a tap says why, such as `Can't play Festival. It's encoded at 96 kHz; Sonos plays up to 48 kHz.` When the server offers a version of the track at 48 kHz or lower, UPnP Cast plays that version instead; MinimServer can offer one with its MinimStreamer extension. That path is untested so far.

## Permissions

| Permission                        | Why                                                                                                                                                       |
| --------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Local network access (Android 17) | Finding and controlling the speakers and servers                                                                                                          |
| Internet                          | Android requires it for any network connection. UPnP Cast only talks to devices on the local network: the ones that answer its search, at the addresses they give for themselves |
| Network state                     | Following the phone's WiFi as it comes, goes or changes; Android grants it without asking                                                                 |
| Foreground service (media)        | Keeping the media player while the room plays with the app in the background; Android grants it without asking. The media player's notification needs no notification permission |

No other permissions. The app asks the room what is playing, and watches WiFi, while one of its screens is open, and in the background only for the media player: while the room plays, at the **Polling interval**, until it has been quiet for 10 minutes.

## Notes

- The speaker fetches every track from the server itself; UPnP Cast only tells it which. Playback doesn't depend on the phone.
- UPnP Cast remembers the addresses of the speakers and servers, so they appear at once; a search on the network runs alongside and finds any that moved.
- Covers are kept on the phone, up to 64 MB, the ones shown least recently going first, so they show at once the next time. Android may clear them when it needs the space; they're fetched again as they show.
- It follows the phone's WiFi. Without it, the screens say **No WiFi connection** and nothing is sent over mobile data. When WiFi comes back, or the phone joins another network, the rooms and servers are searched again and whatever failed loads again. When the home internet is down, Android sends the phone's internet over mobile data, but UPnP Cast stays on the WiFi, so you can still play from the server.
- A link that points away from the device that sent it is refused, and so is a reply bigger or slower than a real device's, one that isn't the answer to what was asked, or one not in UTF-8. Requests go to IP addresses only, never through DNS, each on the WiFi it started on; a room's speaker and a server must be on the phone's own WiFi subnet. The one exception is the Music library's links to its shared folders, which only the speaker opens; the speaker reports no length or sample rate for those tracks, so their rows show just the format, such as `MP3`. Covers come from the server or the speaker only: for a cover on the internet, such as Spotify's, the speaker fetches it and hands it on, so the phone stays on the local network.
- Under a VPN that doesn't let apps bypass it, Android won't keep a request on the WiFi. It then takes the VPN's route, still only to an address on the local network, which the VPN may or may not let through.
- The format line on Now Playing starts with the source, in the Sonos app's words: the server's name, **Music library**, **Spotify**, **AirPlay**, **Sonos Radio**, **Radio** or **Line-in**, and for the TV its input, **HDMI** on a Beam, Arc or Amp, **SPDIF** on a Ray, Playbar or Playbase. Any other music service by the name the speakers give it, such as **Qobuz**, from their own list of services, read once and kept; a service they don't list shows the format alone. The format is what the speaker reports back for the track; for a stream, as from Spotify, also whether it's lossless, such as `Lossless 44.1 kHz 16-bit`. On the TV input it's the format the TV sends, such as `Dolby Atmos (MAT)`, `Dolby Digital 5.1` or `PCM 2.0`. A radio station shows its name after the room and its logo as the cover, as the Sonos app does, both from the speaker; the song, when the station sends it, is the title.
- On a phone the screens stay upright; on a tablet, from 600dp wide, they turn, and the tiles adapt their columns to the width. Now Playing keeps its controls at the bottom, and its cover takes the room above them, larger on a taller screen; in split screen or a small window it shrinks to fit. On a wide screen its controls stay together in the middle, at most 32dp apart; the progress bar and the volume keep the full width.
- The sliders also follow a keyboard and TalkBack: each step there counts as a drag let go.
- Colours mean the same throughout: blue is what's chosen, on or playing; green good news, such as what was found, a lossless format, a tuned room or a favourite's heart; orange a problem, such as no WiFi, a room or server that doesn't answer, or a track the speaker can't play.
- Build output goes to `build.nosync` folders, which iCloud Drive doesn't sync.

## Credits

The icons are Google's Material Icons `cast`, `check_circle`, `chevron_right`, `close`, `expand_more`, `favorite`, `favorite_border`, `folder`, `grid_view`, `home`, `library_music`, `more_vert`, `music_note`, `pause`, `play_arrow`, `queue_music`, `radio_button_unchecked`, `record_voice_over`, `refresh`, `repeat`, `repeat_one`, `replay`, `shuffle`, `skip_next`, `skip_previous`, `speaker`, `timer`, `tune`, `view_list`, `volume_off` and `volume_up`, under the Apache License 2.0.

## License

Copyright (C) 2026 Stouthart. All rights reserved.

_The source code in this repository is free for personal use. However, it is NOT published under a software license. This implies - as stated in the [GitHub Docs] - that standard copyright law applies, meaning the owner retains all rights to the source code and no one may reproduce, distribute, or create derivative works from this work._

[GitHub Docs]: https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository

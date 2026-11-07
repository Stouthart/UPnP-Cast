<!-- 2.1 - Copyright (C) 2026 Stouthart. All rights reserved. -->

# Release notes

Highlights per version, newest first, with 1.x condensed. 2.1 is the version distributed; the entries below it show how the app got there.

## 2.1 - November 2026

- **Group rooms on the Rooms card:** every room has its circle, as in the Sonos app: ✓ for the rooms that play together, ○ for the others. A tap on a room chooses the one the player controls, its name in blue; a tap on its circle groups or ungroups it. The **Group rooms** setting is gone. The ✓ is in the text's colour, so blue marks the chosen room alone.
- **Previous as in most players:** once a track has played more than 5 seconds, Previous goes back to its start; within 5 seconds, to the track before; on the first track, to its start again. On a radio stream or the TV it stays the speaker's own. Next on the last track does nothing, where it said the room couldn't be reached.
- **Clearer messages:** a room that answers but refuses, as Pause on the TV, says **Not supported on this source**; **Couldn't connect** is kept for a room that doesn't answer at all.
- **Touch feedback that fits:** every icon button lights up in the same 40dp circle, whole, also where its row or card cut it off before, as a room's circle and a tile's **⋯**. Previous and Next fill their grey circle, as Play does. Rows, tiles and choices light up within their rounded corners, and the rows and tiles in Browse, Favorites and the Queue now light up at all.
- **Bigger targets:** App settings and Favorites open with a tap anywhere on their card, as a room's row does; a room's circle takes a tap 48dp wide.
- **Upright on a phone:** the screens no longer turn on a phone; on a tablet, from 600dp wide, they turn as before.
- **More room between settings:** in App settings and Room settings, each setting stands as far from the next, switches included.
- **The playing track in blue:** the Queue marks it by its colour alone, without the bars before it; TalkBack reads it as **Current track**.
- **Remove a track from the queue:** a tap on the **✕** at a track's end removes it at once, as the Sonos app's swipe does. The track at that place is checked first, and the speaker removes it only if the queue hasn't changed since, as from another controller; otherwise the list is read again.
- **Sample rates in kHz:** a track Sonos can't play says so as **Can't play Festival. It's encoded at 96 kHz; Sonos plays up to 48 kHz.**, where it gave the rate in Hz. A rate between whole kHz shows exactly, as `22.05 kHz`, where it was rounded to `22.1 kHz`.
- **Sturdier:** a room's reply must be the answer to what was asked, one whole SOAP document: a web page, an empty or unfinished reply, another action's answer, or that answer's tag in a comment no longer counts as done, so what depends on it, such as Play after a new queue, isn't sent. A station's name that couldn't be read is asked again after a minute rather than at every poll, so it can't hold up the controls. A server shows as soon as it answers, its icon following, where a slow icon could hold it back up to 15 seconds.

## 2.0 - November 2026

- **Long titles slide:** in the mini player, a title too long for its row slides to its end and back once, as it changes and as the screen shows again, its edges fading while it moves, then ends in "…" again. Not with Android's animations off, as under **Remove animations**.
- **Sturdier:** a track whose title is its file name, as **Episode.mp3** on a share without tags, shows again: since 1.5 it showed nothing, not even its format or cover. A favourite playlist or album starts at its first track even when another controller queues something meanwhile. A change in Room settings no longer jumps back to the old value when a read that was already under way lands after it. A failed read of the speakers' list of music services waits a minute before it's tried again, so it can't hold up Play/Pause. Browsing one server while a track from another plays no longer leaves that track without its cover in the mini player and Now Playing. With shuffle on, a track tapped, or chosen with **Play now**, plays itself, not another of the queue's; shuffle stays on for the rest.
- **Track numbers in albums:** an album shows each track's number before its title, in grey, such as **3. Festival**, in its list and in its tiles: the number the server has from the file's tags, so an album with tracks missing keeps its own numbering. An album on several discs adds the disc, as **2-3.**. Other lists show titles alone.

## 1.x - November 2026

The first release, then the Music library, group play, Room settings and Favorites.

- **1.0** - A UPnP/DLNA music library on Sonos, browsed and played from Android. It finds the rooms and servers by itself and stays on the WiFi; Browse shows a server's own categories as a list or as tiles, with covers and each track's format; a tap plays a track with the rest of the list queued around it, and **⋯** offers Play now, Play next, Add to end of queue and Replace queue. The speaker fetches the music itself. A mini player, Now Playing and the Queue, in the Sonos app's terms; files up to 48 kHz, Sonos's limit over UPnP; Appearance and a Polling interval. 157 KB, no libraries, nothing running in the background.
- **1.1** - Android 14 and 15 as well; **Home** in Browse; Spotify on the speaker with its cover and whether it's lossless; a source without track details named, as TV, and "Not supported on this source" for a control it doesn't offer; Now Playing's progress counting evenly; the mini player from the room's first item; a new icon.
- **1.2** - The Sonos **Music library**, with **Scan for new content**; **▶** in Browse to play a level's tracks; the phone's volume buttons, by the new **Volume button step**; the TV's input format; settings that fold away; and sturdier requests: on the WiFi and to addresses only, with no endless paging, and sliders that follow a keyboard and TalkBack.
- **1.3** - **Group rooms**, with one volume for the rooms together; every screen following grouping done elsewhere, as in the Sonos app; Now Playing at once, its cover larger on taller screens; the source on Now Playing, such as `MinimServer · FLAC 96 kHz 24-bit`; room icons by speaker kind, with the model; new colours.
- **1.4** - **Room settings**, each room's own as in the Sonos app: Trueplay, EQ, Volume limit, Sub audio, Home theater and Hardware; **Quick skip**; Previous and Next in circles; colour that means something, green for good news and orange for problems; a progress bar that keeps moving; caps on a cover's size and a list's length.
- **1.5** - **Favorites**, played in the chosen room; radio as the Sonos app shows it, with the station's name and logo; music services by name, such as **Qobuz**; even margins and calmer corners; Room settings sliding open; and the largest of a server's covers.

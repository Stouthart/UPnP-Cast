package nl.stouthart.upnpcast

import android.animation.LayoutTransition
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val READS_AT_ONCE = 4 // A room's settings, read this many at a time.

// How a level shows its value: signed as the Sonos app shows Bass (-1, 0, +1), in percent, as it is, or by name.
private const val SIGNED = 0
private const val PERCENT = 1
private const val PLAIN = 2
private const val NAMED = 3

/** A level's value, signed as the Sonos app shows Bass: -1, 0, +1. */
fun signed(v: Int) = if (v > 0) "+$v" else v.toString()

/**
 * Room settings: the rooms, one open at a time with its own settings right under its row, as the Sonos app has them,
 * on the room's own player: Trueplay, EQ with Height level, Volume limit, Sub audio with a bonded Sub, Home theater on
 * a soundbar, and Hardware. Whatever the player hasn't got stays hidden. A change goes out as it's made.
 */
class RoomActivity : Activity() {
    companion object {
        private const val IDS = "ids"
        private const val NAMES = "names"
        private const val HOSTS = "hosts"
        private const val SUBS = "subs"
        private const val ROOM = "room"

        fun intent(c: Context, rooms: List<Room>): Intent = Intent(c, RoomActivity::class.java)
            .putExtra(IDS, Array(rooms.size) { rooms[it].id })
            .putExtra(NAMES, Array(rooms.size) { rooms[it].name })
            .putExtra(HOSTS, Array(rooms.size) { rooms[it].own })
            .putExtra(SUBS, BooleanArray(rooms.size) { rooms[it].sub })
    }

    // What a room's page reads. The Sub's only with a bonded Sub: a player answers for them without one.
    private val keys = arrayOf(
        "Trueplay", "Bass", "Treble", "HeightChannelLevel", "Loudness", "VolumeScalingFactor", "Speech", "NightMode",
        "AudioDelay", "LED", "Touch",
    )
    private val subKeys = arrayOf("SubEnable", "SubGain", "SubPolarity")

    private val io = Executors.newSingleThreadExecutor() // The changes, one after the other, and the reads' start.
    private val wanted = ConcurrentHashMap<String, Int>() // Per room and setting, the latest value still to send.
    private lateinit var rooms: List<Room>
    private val rows = ArrayList<View>() // The rooms' rows, in order; the settings go between two of them.
    private var room = -1 // The open room, its settings under its row; -1 while all are closed.
    @Volatile
    private var gen = 0 // Drops a read that another room's, a close, or leaving, overtook; and skips its requests left.
    private var filling = false // The controls are set from a read: not a change to send.
    private var edits = 0 // Changes made here, main thread only: a read from before one would show the old value.
    private var started = false // Once started: a start after a stop reads the open room again, in place.
    private val lan: () -> Unit = { if (room >= 0) open(room, again = true) } // WiFi came, went or changed.

    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var page: View
    private lateinit var status: TextView
    private lateinit var cards: Array<View>
    private lateinit var trueplay: Switch
    private lateinit var tuned: TextView
    private lateinit var loudness: Switch
    private lateinit var sub: Switch
    private lateinit var night: Switch
    private lateinit var led: Switch
    private lateinit var touch: Switch
    private lateinit var bass: Level
    private lateinit var treble: Level
    private lateinit var height: Level
    private lateinit var limit: Level
    private lateinit var subLevel: Level
    private lateinit var dialog: Level
    private lateinit var phase: Array<TextView>
    private lateinit var speech: Level

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val ids = intent.getStringArrayExtra(IDS) ?: return finish()
        val names = intent.getStringArrayExtra(NAMES) ?: return finish()
        val hosts = intent.getStringArrayExtra(HOSTS) ?: return finish()
        val subs = intent.getBooleanArrayExtra(SUBS) ?: return finish()
        rooms = List(ids.size) { Room(ids[it], names[it], hosts[it], sub = subs[it]) }
        if (rooms.isEmpty()) return finish()
        setContentView(R.layout.activity_room)
        scroll = findViewById(R.id.scroll)
        sheet(scroll, findViewById(R.id.close))
        list = findViewById(R.id.rooms)
        page = findViewById(R.id.page)
        // Its controls come from the room, never from a saved state: made anew, as for dark mode, a restored slider
        // would send its value (the seven share one id), and a switch its own, to whichever room opens first.
        page.isSaveFromParentEnabled = false
        status = findViewById(R.id.status)
        cards = arrayOf(
            findViewById(R.id.trueplay_card), findViewById(R.id.eq_card), findViewById(R.id.limit_card),
            findViewById(R.id.sub_card), findViewById(R.id.theater_card), findViewById(R.id.hardware_card),
        )
        tuned = findViewById(R.id.tuned)
        trueplay = toggle(R.id.trueplay, "Trueplay") { phased(!it) }
        loudness = toggle(R.id.loudness, "Loudness")
        sub = toggle(R.id.sub, "SubEnable")
        night = toggle(R.id.night, "NightMode")
        led = toggle(R.id.led, "LED")
        touch = toggle(R.id.touch, "Touch")
        // The ranges the speaker takes.
        bass = Level(R.id.bass, R.string.bass, R.drawable.ic_bars, "Bass", -10, 10, SIGNED)
        treble = Level(R.id.treble, R.string.treble, R.drawable.ic_bars, "Treble", -10, 10, SIGNED)
        height = Level(R.id.height, R.string.height_level, R.drawable.ic_bars, "HeightChannelLevel", -10, 10, SIGNED)
        limit = Level(R.id.limit, R.string.volume_limit, R.drawable.ic_volume, "VolumeScalingFactor", 1, 100, PERCENT)
        subLevel = Level(R.id.sub_level, R.string.sub_level, R.drawable.ic_sub, "SubGain", -15, 15, SIGNED)
        dialog = Level(R.id.dialog, R.string.tv_dialog_sync, R.drawable.ic_timer, "AudioDelay", 0, 5, PLAIN)
        phase = children(R.id.phase)
        // Off and the speaker's four levels, in the Sonos app's words.
        speech = Level(
            R.id.speech, R.string.speech_enhancement, R.drawable.ic_speech, "Speech", 0, 4, NAMED,
            arrayOf(getString(R.string.off), getString(R.string.low), getString(R.string.medium), getString(R.string.high), getString(R.string.max)),
        )
        for (r in rooms) list.addView(row(r).also { rows += it })
        // Open on the room the player controls, as chosen on the main screen (in a group, the one chosen last), or as
        // before the screen was made anew; onStart reads it, once the network is known.
        val chosen = rooms.indexOfFirst { it.id == Prefs.room(this) }
        room = b?.getInt(ROOM, -2)?.takeIf { it in -1 until rooms.size } ?: if (chosen >= 0) chosen else 0
    }

    override fun onStart() {
        super.onStart()
        Lan.start(this, lan) // Main stops under this sheet, and its watching with it.
        if (room >= 0) open(room, again = started) // Again on a return: changed meanwhile, as in the Sonos app.
        started = true
    }

    override fun onStop() {
        gen++
        Lan.stop(lan)
        super.onStop()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt(ROOM, room)
    }

    private fun children(id: Int): Array<TextView> {
        val g = findViewById<LinearLayout>(id)
        return Array(g.childCount) { g.getChildAt(it) as TextView }
    }

    /**
     * A room as on the main screen, its kind and model, with the App settings card's chevron: a tap opens its settings
     * under it, and closes the room that was open; a tap on the open one closes it.
     */
    private fun row(r: Room): View = layoutInflater.inflate(R.layout.row, list, false).apply {
        val model = Prefs.model(this@RoomActivity, r.id)
        findViewById<TextView>(R.id.name).text = r.name
        findViewById<TextView>(R.id.meta).text = roomMeta(r, model)
        findViewById<ImageView>(R.id.icon).apply {
            glyph(this, productIcon(product(model)))
            shown = true
        }
        findViewById<ImageView>(R.id.chevron).apply {
            setImageResource(R.drawable.ic_expand)
            // ⌄ stops 6dp inside its box, › 8dp: 2dp further in, so it too ends 20dp inside the row.
            (layoutParams as ViewGroup.MarginLayoutParams).marginEnd = -(2 * resources.displayMetrics.density).toInt()
            shown = true
        }
        stateDescription = getString(R.string.collapsed)
        setOnClickListener {
            val i = rows.indexOf(this)
            if (i == room) close() else open(i)
        }
    }

    /** Row [i] open or closed: its chevron up while open, as the App settings card's. */
    private fun mark(i: Int, open: Boolean) {
        rows[i].stateDescription = getString(if (open) R.string.expanded else R.string.collapsed)
        // The name alone marks the room whose settings show.
        rows[i].findViewById<TextView>(R.id.name).setTextColor(getColor(if (open) R.color.accent_text else R.color.text))
        rows[i].findViewById<View>(R.id.chevron).animate().rotation(if (open) 180f else 0f).duration = 200
    }

    private fun close() {
        if (room >= 0) mark(room, false)
        room = -1
        gen++
        page.shown = false
    }

    /**
     * Room [i] open: its settings under its row, read at once from its own player and shown as far as it answered.
     * [again]: the open room read anew, as on a return or another network; its controls stay while it reads.
     */
    private fun open(i: Int, again: Boolean = false) {
        val g = ++gen
        val r = rooms[i]
        if (!again) {
            if (room >= 0) mark(room, false)
            room = i
            mark(i, true)
            // The one set of settings goes under this room's row.
            (page.parent as ViewGroup).removeView(page)
            list.addView(page, list.indexOfChild(rows[i]) + 1)
            page.shown = true
            for (c in cards) c.shown = false
            status.setText(R.string.loading)
            status.setTextColor(getColor(R.color.text2))
            status.shown = true
        }
        val asked = if (r.sub) keys + subKeys else keys
        val made = edits
        later(io) {
            if (g != gen) return@later // Overtaken while a change went out: not a request for it.
            val pool = Executors.newFixedThreadPool(READS_AT_ONCE)
            val got: HashMap<String, Int?>? = try {
                val calls = ArrayList<Callable<Int?>>(asked.size)
                for (k in asked) calls += Callable { if (g == gen) Sonos.setting(r.own, k) else null }
                val answers = pool.invokeAll(calls)
                HashMap<String, Int?>().apply { for (n in asked.indices) put(asked[n], answers[n].get()) }
            } catch (e: Exception) {
                null // A failure other than a setting the player hasn't got: the room itself didn't answer.
            } finally {
                pool.shutdownNow()
            }
            // Not after a change made meanwhile: it could still hold the value from before. Only ever a read again:
            // the controls show, and can change, only once the first read has filled them.
            runOnUiThread { if (g == gen && made == edits) fill(r, got) }
        }
    }

    private fun fill(r: Room, v: Map<String, Int?>?) {
        if (v == null) {
            for (c in cards) c.shown = false // Read again too: nothing to set on a room that doesn't answer.
            status.text = unreachable(r.name)
            status.setTextColor(getColor(R.color.warning))
            status.shown = true
            return
        }
        val first = cards.none { it.shown } // Brought into view once they show, not on each read again.
        status.shown = false
        filling = true
        val calibration = v["Trueplay"]
        val tp = calibration?.and(1)
        if (calibration != null) {
            val ok = calibration >= 2
            trueplay.isChecked = tp == 1
            enable(trueplay, ok) // Only a room Trueplay has tuned: it has nothing to turn on otherwise.
            tuned.setText(if (ok) R.string.tuned else R.string.not_tuned)
            tuned.setTextColor(getColor(if (ok) R.color.success else R.color.text2)) // Not tuned isn't a fault.
        }
        cards[0].shown = tp != null
        bass.set(v["Bass"])
        treble.set(v["Treble"])
        height.set(v["HeightChannelLevel"])
        toggle(loudness, v["Loudness"])
        cards[1].shown = bass.row.shown || treble.row.shown || height.row.shown || loudness.shown
        limit.set(v["VolumeScalingFactor"])
        cards[2].shown = limit.row.shown
        toggle(sub, v["SubEnable"])
        subLevel.set(v["SubGain"])
        v["SubPolarity"].let { p ->
            findViewById<View>(R.id.phase_label).shown = p != null
            findViewById<View>(R.id.phase).shown = p != null
            if (p != null) segments(phase, p) { write("SubPolarity", it) }
        }
        phased(tp != 1)
        cards[3].shown = sub.shown
        speech.set(v["Speech"]?.coerceIn(0, 4))
        toggle(night, v["NightMode"])
        dialog.set(v["AudioDelay"])
        cards[4].shown = speech.row.shown || night.shown || dialog.row.shown
        toggle(led, v["LED"])
        toggle(touch, v["Touch"])
        cards[5].shown = led.shown || touch.shown
        filling = false
        if (first) reveal(rows[room])
    }

    /**
     * Once the cards are laid out: [row] and the first half screen of its settings in view, scrolled to only when
     * they aren't, so the title stays while they show already. After the list's opening, as the App settings card's:
     * while the list and the screen grow, animated, a scroll stops at their height so far.
     */
    private fun reveal(row: View) {
        page.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                v.removeOnLayoutChangeListener(this)
                v.post { after(list.layoutTransition) { show(v, row) } } // Not inside the layout pass.
            }
        })
    }

    private fun show(v: View, row: View) {
        val gap = (12 * resources.displayMetrics.density).toInt()
        v.requestRectangleOnScreen(Rect(0, -row.height - gap, v.width, minOf(v.height, scroll.height / 2)), false)
    }

    /**
     * [then] once [t] has ended, at once if it isn't running. Checked after each end, not in it: the last animation to
     * end may still count as running while its listeners are told; and a listener isn't removed while they are.
     */
    private fun after(t: LayoutTransition?, then: () -> Unit) {
        if (t == null || !t.isRunning) return then()
        t.addTransitionListener(object : LayoutTransition.TransitionListener {
            private var done = false

            override fun startTransition(t: LayoutTransition, c: ViewGroup?, v: View?, type: Int) = Unit

            override fun endTransition(t: LayoutTransition, c: ViewGroup?, v: View?, type: Int) {
                list.post {
                    if (done || t.isRunning) return@post
                    done = true
                    t.removeTransitionListener(this)
                    then()
                }
            }
        })
    }

    private fun toggle(s: Switch, v: Int?) {
        s.shown = v != null
        if (v != null) s.isChecked = v == 1
    }

    /** The switch [id] for [key]; [then] runs after a change the user made. */
    private fun toggle(id: Int, key: String, then: (Boolean) -> Unit = {}): Switch = findViewById<Switch>(id).apply {
        setOnCheckedChangeListener { _, on ->
            if (filling) return@setOnCheckedChangeListener
            write(key, if (on) 1 else 0)
            then(on)
        }
    }

    // Phase control only while Trueplay is off: Trueplay sets the Sub's phase itself.
    private fun phased(on: Boolean) {
        for (c in phase) c.isEnabled = on
        findViewById<View>(R.id.phase).alpha = if (on) 1f else 0.5f
        findViewById<View>(R.id.phase_label).alpha = if (on) 1f else 0.5f
    }

    private fun enable(v: View, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.5f
    }

    /**
     * Sends [key] = [v] to the room shown. Changes go one after the other; while one waits, a newer value for the
     * same setting takes its place, so a drag sends where it is, not every step on the way.
     */
    private fun write(key: String, v: Int) {
        val i = room
        if (i < 0) return
        val r = rooms[i]
        val k = "$i:$key"
        val g = gen
        edits++
        if (wanted.put(k, v) != null) return
        later(io) {
            val x = wanted.remove(k) ?: return@later
            try {
                Sonos.setting(r.own, key, x)
            } catch (e: Exception) {
                val text = if (e is HttpException && e.upnp > 0) getString(R.string.setting_refused, r.name) else unreachable(r.name)
                runOnUiThread {
                    Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
                    // Read again, so the controls show what the room has; not once closed or left, its start reads.
                    if (g == gen && room == i) open(i, again = true)
                }
            }
        }
    }

    /**
     * A level row (level.xml) at [id] for [key], [min]..[max], [icon] before its slider, its value shown as [kind]
     * says; NAMED shows [names], from [min] on. A change the user makes, by drag, key or TalkBack, goes out.
     */
    private inner class Level(
        id: Int, name: Int, icon: Int, val key: String, val min: Int, max: Int, val kind: Int,
        private val names: Array<String> = emptyArray(),
    ) {
        val row: View = findViewById(id)
        private val bar: SeekBar = row.findViewById(R.id.level_bar)
        private val value: TextView = row.findViewById(R.id.level_value)

        init {
            val label = getString(name)
            row.findViewById<TextView>(R.id.level_name).text = label
            row.findViewById<ImageView>(R.id.level_icon).let {
                it.setImageResource(icon)
                // level.xml starts a glyph drawn 3dp into its box on the name's line; the Sub's is drawn 4dp in.
                if (icon == R.drawable.ic_sub) (it.layoutParams as ViewGroup.MarginLayoutParams).marginStart =
                    -(12 * resources.displayMetrics.density).toInt()
            }
            // Names at one size, the largest from 15 sp down at which the longest fits the value's 48dp, as fit()
            // sizes Sonos's labels in a row of segments: the slider keeps the mini player's width.
            if (names.isNotEmpty()) {
                val paint = Paint(value.paint)
                val room = value.layoutParams.width
                var sp = 15f
                while (sp > 11f) {
                    paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
                    if (names.all { paint.measureText(it) <= room }) break
                    sp -= 0.5f
                }
                value.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            }
            bar.max = max - min
            bar.contentDescription = label
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                    show(p + min)
                    if (user) write(key, p + min) // Not a read setting it.
                }

                override fun onStartTrackingTouch(s: SeekBar) = Unit

                override fun onStopTrackingTouch(s: SeekBar) = Unit
            })
        }

        private fun show(v: Int) {
            value.text = when (kind) {
                SIGNED -> signed(v)
                PERCENT -> getString(R.string.percent, v)
                NAMED -> names[v - min]
                else -> v.toString()
            }
            bar.stateDescription = value.text // TalkBack says "+2", not "60 percent".
        }

        /** Shows [v], or hides the row when the room hasn't got this level. */
        fun set(v: Int?) {
            row.shown = v != null
            if (v == null) return
            bar.progress = v - min
            show(v)
        }
    }

    override fun onDestroy() {
        gen++
        io.shutdown() // A change still out goes through; nothing new starts.
        super.onDestroy()
    }
}

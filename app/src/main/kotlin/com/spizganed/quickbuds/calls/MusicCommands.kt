package com.spizganed.quickbuds.calls

/**
 * A spoken music command, from what the recognizer heard: play, pause, next, previous. Pure (MusicCommandsTest).
 * No song search, by the developer's choice (2026-10-19; RVX Music took no searches anyway).
 *
 * Listening never stops while Bike mode is on, so ordinary talk reaches it too. Only exact command shapes count:
 * the utterance must BE a command ("next song"), not merely contain one ("the next song was good" is nothing).
 */
object MusicCommands {

    enum class Command { Play, Pause, Next, Previous, EndCall }

    private val PLAY = setOf("play", "play song", "play the song", "play music", "resume", "play a song")
    private val PAUSE = setOf("stop", "stop song", "stop the song", "stop music", "pause", "pause song", "pause the song",
        "pause music")
    // "play next" and "play previous" too: 2026-10-19, "play next" was taken for something else.
    private val NEXT = setOf("next", "next song", "play next", "play next song", "play the next song", "next track",
        "skip", "skip song")
    private val PREVIOUS = setOf("previous", "previous song", "play previous", "play previous song",
        "play the previous song", "previous track", "go back", "last song", "play last song")
    /** During a call: "Hey Jarvis, cut the call". */
    private val END_CALL = setOf("cut the call", "cut call", "end the call", "end call", "hang up")

    /** Fillers some people put first or last: "please play next song", "next song please". */
    private val FILLER = setOf("please", "ok", "okay", "hey", "now")

    fun parse(text: String): Command? {
        var words = VoicePhrases.normalize(text).split(' ').filter { it.isNotEmpty() }
        while (words.isNotEmpty() && words.first() in FILLER) words = words.drop(1)
        while (words.isNotEmpty() && words.last() in FILLER) words = words.dropLast(1)
        return when (words.joinToString(" ")) {
            in NEXT -> Command.Next
            in PREVIOUS -> Command.Previous
            in PLAY -> Command.Play
            in PAUSE -> Command.Pause
            in END_CALL -> Command.EndCall
            else -> null
        }
    }

    /** Several hypotheses: the first that is a command (the recognizer's best guess first). */
    fun parseAll(hypotheses: List<String>): Command? = hypotheses.firstNotNullOfOrNull { parse(it) }

    // --- "Hey Jarvis": the wake phrase before every command (developer's choice, 2026-10-19) ---

    const val WAKE = "hey jarvis"

    /** What one recognized phrase means. */
    sealed class Heard {
        /** "Hey Jarvis" alone: a beep, then a command within the window. */
        object Wake : Heard()
        data class Do(val command: Command) : Heard()
        object Nothing : Heard()
    }

    /**
     * Two ways to give a command (developer's choice, 2026-10-19): together ("hey jarvis next song"), or "hey jarvis",
     * a beep, then the command alone within the window ([awaiting] true). A command alone outside the window is
     * nothing: ordinary talk does not skip songs.
     */
    fun interpret(text: String, awaiting: Boolean): Heard {
        val t = VoicePhrases.normalize(text)
        if (t == WAKE) return Heard.Wake
        if (t.startsWith("$WAKE ")) return parse(t.removePrefix("$WAKE "))?.let { Heard.Do(it) } ?: Heard.Nothing
        if (awaiting) return parse(t)?.let { Heard.Do(it) } ?: Heard.Nothing
        return Heard.Nothing
    }

    /** "hey jarvis next song" is Next; "next song" alone is nothing. */
    fun afterWake(text: String): Command? = (interpret(text, awaiting = false) as? Heard.Do)?.command

    /**
     * The only phrases the offline recognizer (Vosk) listens for: the wake phrase alone, the wake phrase with each
     * command, each command alone (used only within the window), and "[unk]" for everything else. A recognizer
     * limited to these hears them reliably and turns all other talk into "[unk]".
     */
    fun grammar(): List<String> {
        val all = PLAY + PAUSE + NEXT + PREVIOUS + END_CALL
        return listOf(WAKE) + all.map { "$WAKE $it" } + all + "[unk]"
    }
}

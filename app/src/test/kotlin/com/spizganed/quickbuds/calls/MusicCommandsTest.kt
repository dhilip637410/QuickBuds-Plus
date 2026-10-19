package com.spizganed.quickbuds.calls

import com.spizganed.quickbuds.calls.MusicCommands.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicCommandsTest {

    private fun p(t: String) = MusicCommands.parse(t)

    @Test fun theCommands() {
        assertEquals(Command.Play, p("play"))
        assertEquals(Command.Play, p("Play song"))
        assertEquals(Command.Next, p("next song"))
        assertEquals(Command.Next, p("play next song"))
        assertEquals(Command.Next, p("play next"))
        assertEquals(Command.Previous, p("previous song"))
        assertEquals(Command.Previous, p("play previous song"))
        assertEquals(Command.Previous, p("play previous"))
    }

    @Test fun stopAndPause() {
        for (t in listOf("stop", "Stop song", "stop music", "pause", "pause the song", "please stop")) assertEquals(t, Command.Pause, p(t))
        assertNull(p("stop it now you two"))     // talk, not a command
        assertNull(p("bus stop"))
    }

    @Test fun noSongSearch() {
        // By the developer's choice: "play <name>" is no command.
        assertNull(p("play Vaathi Coming"))
        assertNull(p("play song Enjoy Enjaami"))
    }

    @Test fun politeFillers() {
        assertEquals(Command.Next, p("please next song"))
        assertEquals(Command.Next, p("next song please"))
    }

    @Test fun ordinaryTalkIsNotACommand() {
        assertNull(p("the next song was good"))     // contains "next song", is not one
        assertNull(p("did you play cricket"))
        assertNull(p("previously"))
        assertNull(p(""))
        assertNull(p("please"))
    }

    @Test fun onlyAfterHeyJarvis() {
        assertEquals(Command.Next, MusicCommands.afterWake("hey jarvis next song"))
        assertEquals(Command.Pause, MusicCommands.afterWake("Hey Jarvis, stop"))
        assertEquals(Command.Previous, MusicCommands.afterWake("hey jarvis play previous"))
        assertNull(MusicCommands.afterWake("next song"))              // no wake phrase: nothing
        assertNull(MusicCommands.afterWake("hey jarvis"))             // wake phrase alone: nothing
        assertNull(MusicCommands.afterWake("hey jarvis how are you"))
        assertNull(MusicCommands.afterWake("[unk]"))
    }

    @Test fun wakeAloneThenTheCommandWithinTheWindow() {
        assertEquals(MusicCommands.Heard.Wake, MusicCommands.interpret("hey jarvis", awaiting = false))
        assertEquals(MusicCommands.Heard.Do(Command.Next), MusicCommands.interpret("next song", awaiting = true))
        assertEquals(MusicCommands.Heard.Nothing, MusicCommands.interpret("next song", awaiting = false))   // window over
        assertEquals(MusicCommands.Heard.Do(Command.Pause), MusicCommands.interpret("hey jarvis stop", awaiting = false))
    }

    @Test fun cutTheCall() {
        assertEquals(Command.EndCall, MusicCommands.afterWake("hey jarvis cut the call"))
        assertEquals(Command.EndCall, MusicCommands.afterWake("Hey Jarvis, hang up"))
        assertEquals(MusicCommands.Heard.Do(Command.EndCall), MusicCommands.interpret("end the call", awaiting = true))
        assertNull(MusicCommands.afterWake("cut the call"))      // no wake phrase, no window: nothing
    }

    @Test fun theGrammarHoldsEveryWayToSayIt() {
        val g = MusicCommands.grammar()
        assertEquals("[unk]", g.last())
        assertEquals("hey jarvis", g.first())
        for (p in g.drop(1).dropLast(1)) {
            // Together it is a command at once; alone, only within the window.
            val heard = MusicCommands.interpret(p, awaiting = true)
            assertEquals(p, true, heard is MusicCommands.Heard.Do)
        }
    }

    @Test fun firstHypothesisThatIsACommand() {
        assertEquals(Command.Next, MusicCommands.parseAll(listOf("next on", "next song", "play next song")))
        assertNull(MusicCommands.parseAll(listOf("hello", "yellow")))
    }
}

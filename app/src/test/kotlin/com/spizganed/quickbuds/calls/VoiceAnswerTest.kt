package com.spizganed.quickbuds.calls

import com.spizganed.quickbuds.calls.VoiceAnswer.Action
import com.spizganed.quickbuds.calls.AutoAnswer.Filter
import com.spizganed.quickbuds.calls.VoicePhrases.Answer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAnswerTest {

    private val ashraf = AutoAnswer.Contact("Ashraf", "+91 98765 43210")

    @Test fun strangersAreNeverPrompted() {
        assertFalse(VoiceAnswer.promptFor("9000000000", null, Filter.ALL, emptyList()))   // not a contact
        assertFalse(VoiceAnswer.promptFor(null, "Ashraf", Filter.ALL, emptyList()))       // hidden number
        assertFalse(VoiceAnswer.promptFor("9000000000", null, Filter.EXCEPT, listOf(ashraf)))
    }

    @Test fun allContacts() {
        assertTrue(VoiceAnswer.promptFor("9876543210", "Ashraf", Filter.ALL, emptyList()))
    }

    @Test fun autoAnswersContactFilter() {
        assertTrue(VoiceAnswer.promptFor("09876543210", "Ashraf", Filter.ONLY, listOf(ashraf)))
        assertFalse(VoiceAnswer.promptFor("9111111111", "Mom", Filter.ONLY, listOf(ashraf)))
        // All except the chosen: never asked about them, asked about every other contact.
        assertFalse(VoiceAnswer.promptFor("09876543210", "Ashraf", Filter.EXCEPT, listOf(ashraf)))
        assertTrue(VoiceAnswer.promptFor("9111111111", "Mom", Filter.EXCEPT, listOf(ashraf)))
    }

    @Test fun whatAReplyDoes() {
        assertEquals(Action.ANSWER, VoiceAnswer.action(Answer.YES, stillRinging = true))
        assertEquals(Action.DECLINE, VoiceAnswer.action(Answer.NO, stillRinging = true))
        assertEquals(Action.NONE, VoiceAnswer.action(Answer.UNCLEAR, stillRinging = true))   // auto-answer's turn, or it rings on
    }

    @Test fun nothingOnceTheCallStoppedRinging() {
        // Answered by hand or ended while listening: a late YES or NO must not touch the call.
        assertEquals(Action.NONE, VoiceAnswer.action(Answer.YES, stillRinging = false))
        assertEquals(Action.NONE, VoiceAnswer.action(Answer.NO, stillRinging = false))
    }

    @Test fun thePromptNamesTheCaller() {
        assertEquals("Ashraf அழைக்கிறார். எடுக்கட்டுமா?", VoiceAnswer.prompt("Ashraf"))
    }
}

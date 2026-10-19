package com.spizganed.quickbuds.calls

import com.spizganed.quickbuds.calls.VoicePhrases.Answer.NO
import com.spizganed.quickbuds.calls.VoicePhrases.Answer.UNCLEAR
import com.spizganed.quickbuds.calls.VoicePhrases.Answer.YES
import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePhrasesTest {

    private fun check(text: String, expected: VoicePhrases.Answer) = assertEquals(text, expected, VoicePhrases.classify(text))

    // --- The required cases ---

    @Test fun aRefusalThatContainsTakeIsNo() = check("calla edukatha", NO)
    @Test fun iDontKnowMustNotDecline() = check("i dont know", UNCLEAR)
    @Test fun nowIsNotNo() = check("now", UNCLEAR)
    @Test fun nothingIsNotNo() = check("nothing", UNCLEAR)
    @Test fun silenceIsUnclear() = check("", UNCLEAR)

    // --- More of the same rules ---

    @Test fun englishYes() {
        for (t in listOf("yes", "Yeah.", "OK", "okay sure", "pick up", "answer it", "take it", "go ahead", "Accept")) check(t, YES)
    }

    @Test fun englishNo() {
        for (t in listOf("no", "Nope!", "reject", "not now", "cancel", "don't pick", "do not answer", "cut the call", "leave it"))
            check(t, NO)
    }

    @Test fun phrasesAddedOn20261018() {
        check("callah edu", YES)
        check("edu", YES)
        check("illa edukatha", NO)
        check("callah edukatha", NO)    // "callah edu" must not fire inside the refusal
        check("illa", UNCLEAR)          // "illa" alone is not in the list
    }

    @Test fun theRecognizersOwnSpellingOfTheRefusal() {
        // 2026-10-18, en-IN on-device, the user saying "call edukatha": the recognizer writes a double k.
        assertEquals(NO, VoicePhrases.classifyAll(listOf("call edukkatha", "call edukkara", "call edukkad")))
    }

    @Test fun romanisedTamil() {
        for (t in listOf("calla edu", "edu", "edukalam", "aama", "sari", "seri", "pick pannu")) check(t, YES)
        for (t in listOf("vendaam", "venda", "edukatha", "kandukkadha", "call edukatha")) check(t, NO)
    }

    @Test fun tamilScript() {
        for (t in listOf("ஆமா", "ஆம்", "சரி", "எடு")) check(t, YES)
        for (t in listOf("வேண்டாம்", "எடுக்காதே")) check(t, NO)            // "எடு" inside "எடுக்காதே" is part of the NO
        check("சரி எடுங்க", YES)                                           // a substring inside an inflected word
    }

    @Test fun wholeWordsForLatin() {
        check("i know", UNCLEAR)       // "no" inside "know"
        check("nobody", UNCLEAR)
        check("ahmed", UNCLEAR)        // "edu" is not inside other words either
        check("education", UNCLEAR)
    }

    @Test fun bareDontIsNotNo() {
        check("dont", UNCLEAR)
        check("do not", UNCLEAR)
        check("dont pick up", NO)      // "pick up" sits inside the refusal
    }

    @Test fun aContradictionIsUnclear() {
        check("no yes", UNCLEAR)
        check("no wait yes take it", UNCLEAR)
    }

    @Test fun hypothesesMustAgree() {
        assertEquals(YES, VoicePhrases.classifyAll(listOf("yes", "yes please", "s")))
        assertEquals(UNCLEAR, VoicePhrases.classifyAll(listOf("yes", "no")))
        assertEquals(UNCLEAR, VoicePhrases.classifyAll(emptyList()))
    }
}

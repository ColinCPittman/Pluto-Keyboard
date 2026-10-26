package org.futo.voiceinput.context

import org.futo.voiceinput.shared.context.EmojiNames
import org.futo.voiceinput.shared.context.EmojiNickname
import org.futo.voiceinput.shared.context.SpokenEdits
import org.futo.voiceinput.shared.context.TranscriptGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmojiNamesTest {
    private val index = EmojiNames.builtIn(File("java/res/raw/gemoji.json").readText())
    private val names = EmojiNames.nameMap(index, emptyList())

    /** The name map with your changes [list] (the saved format). */
    private fun mine(list: String) = EmojiNames.nameMap(index, EmojiNames.parse(list, index))

    /** What "<[name]> emoji" gives (the emoji, or null for none). */
    private fun gives(name: String, map: Map<List<String>, String> = names) = EmojiNames.lookup(map, name)?.takeIf { it.isNotEmpty() }

    /** Applies the emoji groups of [text] (each checked by the guard) with the name map [map]. */
    private fun applyEmoji(text: String, map: Map<List<String>, String> = names): String {
        var out = text
        for (g in SpokenEdits.emojiGroups(SpokenEdits.Words(text), map).sortedByDescending { it.start }) {
            assertTrue(g.certain)
            val e = g.alternatives.first()
            assertTrue("guard: ${e.replacement}", TranscriptGuard.checkEdit(text, e, emojiNames = map).accepted)
            out = out.replaceRange(e.start, e.end, e.replacement)
        }
        return out
    }

    @Test
    fun officialNamesStillWork() {
        assertTrue(index.emojis.size > 1800)
        assertEquals("👍", gives("thumbs up"))
        assertEquals("😂", gives("face with tears of joy"))
        assertEquals("❤️", gives("red heart"))
        assertEquals("🙃", gives("upside-down face"))
        assertEquals("🇻🇳", gives("flag: Vietnam"))
        assertEquals("1️⃣", gives("keycap: 1"))
        // "keycap: #" and "keycap: *" sound the same, so "keycap" gives neither
        assertEquals("", EmojiNames.lookup(names, "keycap"))
        // your changes survive being saved and read back
        val changes = EmojiNames.parse("laughing\t😆\nflag: Vietnam\t🇻🇳\t0", index)
        assertEquals(changes, EmojiNames.parse(EmojiNames.serialize(changes), index))
        assertEquals(2, changes.size)
    }

    /** Nicknames, keywords and their spoken versions, so you don't need the official name. */
    @Test
    fun spokenNames() {
        for ((name, emoji) in listOf(
            "laughing" to "😂", "lol" to "😂", "crying laughing" to "😂", "laughing crying" to "😂", "tears of joy" to "😂",
            "crying" to "😭", "sad" to "😢", "heart" to "❤️", "love" to "❤️", "hearts" to "💕", "broken heart" to "💔",
            "thumbs up" to "👍", "thumb up" to "👍", "thumbs down" to "👎", "mind blown" to "🤯", "mind-blown" to "🤯",
            "exploding head" to "🤯", "shrug" to "🤷", "shrugging" to "🤷", "eye roll" to "🙄", "rolling eyes" to "🙄",
            "fire" to "🔥", "lit" to "🔥", "skull" to "💀", "dead" to "💀", "facepalm" to "🤦",
            "face palm" to "🤦", "heart eyes" to "😍", "smiling heart eyes" to "😍", "thinking" to "🤔", "party" to "🥳",
            "tada" to "🎉", "clap" to "👏", "pray" to "🙏", "thank you" to "🙏", "100" to "💯", "hundred" to "💯",
            "ok" to "👌", "okay" to "👌", "wink" to "😉", "cool" to "😎", "sunglasses" to "😎", "dog" to "🐶",
            "puppy" to "🐶", "cat" to "🐱", "rocket" to "🚀", "sparkles" to "✨", "check mark" to "✔️", "checkmark" to "✅",
            // plurals, the other word order, without "face"/"with"
            "skulls" to "💀", "rockets" to "🚀", "thinking face" to "🤔", "upside down" to "🙃", "vietnam flag" to "🇻🇳",
            "smiling face with heart eyes" to "😍", "grinning big eyes" to "😃", "heart red" to "❤️",
            // aliases and keywords of the emoji data
            "joy" to "😂", "sob" to "😭", "roll eyes" to "🙄", "dunno" to "🤷", "thumbsup" to "👍",
        )) assertEquals(name, emoji, gives(name))
    }

    /** A name that fits several emoji gives none, and words that talk about emoji in general are no names. */
    @Test
    fun ambiguousNamesGiveNone() {
        // keywords of many emoji
        for (name in listOf("keycap")) assertEquals(name, "", EmojiNames.lookup(names, name))
        for (name in listOf("like", "new", "use", "see", "send", "up", "two", "one", "it", "us", "a", "all")) {
            assertEquals(name, null, EmojiNames.lookup(names, name))
        }
        // a name that gives none doesn't fall back to a shorter one
        assertEquals("Nice keycap emoji", applyEmoji("Nice keycap emoji"))
        // every name the page shows gives that emoji
        assertTrue(index.names.size > 5000)
        for (info in index.emojis) for (n in info.names) assertEquals(n, info.emoji, gives(n))
        val joy = index.emojis.first { it.emoji == "😂" }
        assertTrue(joy.names.toString(), joy.names.containsAll(listOf("laughing", "lol", "crying laughing", "face with tears of joy", "joy")))
    }

    @Test
    fun lookAlikesStayAsTheyAre() {
        for (s in listOf(
            "I was laughing so hard.", "My heart is full.", "That was fire.", "He's dead serious.", "I'm crying.",
            "I love emoji.", "I really love emoji.", "Do you like emoji?", "I use emoji a lot.", "Do you see emoji on your phone?",
            "She sent me emoji.", "Lots of new emoji.", "I need more emoji.", "Too many emoji.", "I sent two emoji.",
            "Send us emoji.", "Kids love emoji.", "Do you love emoji?", "You love emoji.", "I hate emoji.", "Up emoji?",
            "The laughing emoji is my favorite.", "Send a heart emoji to mom.", "Which crying emoji did you mean?",
            "Is it a crying laughing emoji?", "What does the skull emoji mean?", "My dog emoji collection.",
        )) assertEquals(s, applyEmoji(s))
    }

    @Test
    fun spokenNamesInSentences() {
        assertEquals("That's hilarious 😂", applyEmoji("That's hilarious laughing emoji."))
        assertEquals("That's hilarious 😂", applyEmoji("That's hilarious crying laughing emoji."))
        assertEquals("That's hilarious 😂", applyEmoji("That's hilarious laughing crying emoji"))
        assertEquals("Love you ❤️", applyEmoji("Love you heart emoji."))
        assertEquals("Love you, ❤️", applyEmoji("Love you, love emoji."))
        assertEquals("Miss you ❤️", applyEmoji("Miss you love emoji."))
        assertEquals("I'm 🤯", applyEmoji("I'm mind blown emoji."))
        assertEquals("No idea 🤷", applyEmoji("No idea shrug emoji."))
        assertEquals("Whatever 🙄", applyEmoji("Whatever eye roll emoji"))
        assertEquals("That was 🔥 🔥", applyEmoji("That was fire emoji fire emoji"))
        assertEquals("I'm 💀", applyEmoji("I'm dead emoji."))
        assertEquals("🙏", applyEmoji("Thank you emoji."))
        assertEquals("So cute 😍", applyEmoji("So cute smiling heart eyes emoji"))
        assertEquals("Good night 🌙", applyEmoji("Good night moon emoji"))
        // the punctuation model splits the nickname
        assertEquals("What are you up to? 😂", applyEmoji("What are you up to? Crying? Laughing emoji?"))
        assertEquals("What are you up to now? 😂", applyEmoji("What are you up to now? Crying, laughing at Mojie?"))
    }

    @Test
    fun saysTheEmoji() {
        assertEquals("That's great 👍", applyEmoji("That's great thumbs up emoji"))
        assertEquals("That's great 👍", applyEmoji("That's great thumbs up emoji."))
        assertEquals("That's great 👍. See you.", applyEmoji("That's great thumbs up emoji. See you."))
        assertEquals("That's hilarious 😂", applyEmoji("That's hilarious face with tears of joy emoji."))
        assertEquals("I love you ❤️ see you soon.", applyEmoji("I love you red heart emoji see you soon."))
        assertEquals("👍", applyEmoji("Thumbs up emoji."))
        assertEquals("Party time 🎉 🎉", applyEmoji("Party time party popper emoji party popper emoji"))
        // the longest name wins ("smiling face with heart eyes", not "heart eyes")
        assertEquals("So cute 😍", applyEmoji("So cute smiling face with heart eyes emoji"))
    }

    @Test
    fun talkingAboutAnEmojiStaysAsWords() {
        for (s in listOf(
            "The thumbs up emoji is my favorite.",
            "Send a red heart emoji to mom.",
            "Which fire emoji did you mean?",
            "My favorite fire emoji",
            "The fire emoji keyboard is broken.",
            "Fire emoji for the win.",
            "I use the skull emoji too much.",
            "What does that emoji mean?",
            "I love emoji.",
            "Emoji are fun.",
            "There's a new emoji update.",
        )) assertEquals(s, applyEmoji(s))
    }

    /** No sentence of the Smart corrections test sets (none asks for an emoji) gets one. */
    @Test
    fun testSetsStayAsTheyAre() {
        val files = ((File("tools/context-bench").listFiles() ?: emptyArray<File>()) + (File("tools/context-bench/stress").listFiles() ?: emptyArray<File>()))
            .filter { it.name.endsWith(".jsonl") }
        var n = 0
        for (f in files) for (line in f.readLines()) {
            val input = Regex("\"input\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(line)?.groupValues?.get(1) ?: continue
            n++
            assertTrue("${f.name}: $input", SpokenEdits.emojiGroups(SpokenEdits.Words(input), names).isEmpty())
        }
        assertTrue(n > 5000)
    }

    @Test
    fun yourOwnNames() {
        val map = mine("laughing\t😆\nthumbs up\t👍\t0\nflag\t🏁")
        // your names come first
        assertEquals("So funny 😆", applyEmoji("So funny laughing emoji", map))
        assertEquals("Finish 🏁", applyEmoji("Finish flag emoji", map))
        // the punctuation model writes hyphens ("mind-blown"), and names match with or without them
        val blown = mine("mind blown\t🤯")
        assertEquals("I'm 🤯", applyEmoji("I'm mind-blown emoji", blown))
        assertEquals("I'm 🤯", applyEmoji("I'm mind blown emoji", blown))
        assertEquals("Wow 🙃", applyEmoji("Wow upside-down face emoji"))
        // the punctuation model puts commas (or a full stop) between the words
        assertEquals("That's great, 👍", applyEmoji("That's great, thumbs up, emoji."))
        assertEquals("That's great 👍", applyEmoji("That's great thumbs, up, emoji."))
        assertEquals("I'm 🤯", applyEmoji("I'm mind, blown, emoji.", blown))
        assertEquals("I'm 🤯", applyEmoji("I'm mind-blown. Emoji.", blown))
        // from your dictations (the voice model's question mark goes, "at Mojie" is "emoji")
        val cl = mine("crying laughing	😂")
        assertEquals("What are you up to now? 😂", applyEmoji("What are you up to now? Crying, laughing emoji?", cl))
        assertEquals("What are you up to now? 😂", applyEmoji("What are you up to now? Crying, laughing at Mojie?", cl))
        assertEquals("Now? 😂 See you.", applyEmoji("Now? Crying laughing emoji? See you.", cl))
        assertEquals("What are you up to? 😂", applyEmoji("What are you up to? Crying? Laughing emoji?", cl))
        assertEquals("Is it a crying laughing emoji?", applyEmoji("Is it a crying laughing emoji?", cl))
        assertEquals("Are you coming tonight? 👍", applyEmoji("Are you coming tonight thumbs up emoji?"))
        assertEquals("Coming? 👍 See you.", applyEmoji("Coming thumbs up emoji? See you."))
        assertEquals("Wow, 👍, see you.", applyEmoji("Wow, thumbs up. Emoji, see you."))
        assertEquals("Thumbs up. Emoji keyboard is great.", applyEmoji("Thumbs up. Emoji keyboard is great."))
        assertEquals("I like the thumbs up. Emoji are fun.", applyEmoji("I like the thumbs up. Emoji are fun."))
        // a switched-off name stays as words
        assertEquals("Okay thumbs up emoji", applyEmoji("Okay thumbs up emoji", map))
        // and so does every shorter name in it, so no "thumbs 🆙"
        assertEquals("Okay thumbs up emoji.", applyEmoji("Okay thumbs up emoji.", map))
        // the built-in names stay with your list
        assertEquals("Nice 🔥", applyEmoji("Nice fire emoji", map))
        assertTrue(EmojiNames.isValidEmoji("😂"))
        assertTrue(EmojiNames.isValidEmoji("👨‍👩‍👧‍👦"))
        assertTrue(!EmojiNames.isValidEmoji("lol"))
        assertTrue(!EmojiNames.isValidEmoji(":)"))
    }
}

class EmojiNamesListTest {
    private val index = EmojiNames.builtIn(File("java/res/raw/gemoji.json").readText())

    /** A list saved before the built-in names grew held every official name, but only your changes are kept. */
    @Test
    fun oldSavedListsKeepWorking() {
        // the old list left out names two emoji share ("keycap: #" and "keycap: *")
        val official = index.emojis.filter { it.official != "keycap: #" && it.official != "keycap: *" }.map { EmojiNickname(it.official, it.emoji) }
        // you renamed "face with tears of joy" to "laughing", switched off "flag: Vietnam" and added "yay"
        val old = official.map {
            when (it.phrase) {
                "face with tears of joy" -> it.copy(phrase = "laughing")
                "flag: Vietnam" -> it.copy(enabled = false)
                else -> it
            }
        } + EmojiNickname("yay", "🥳")
        val changes = EmojiNames.parse(EmojiNames.serialize(old), index)
        assertEquals(
            setOf(EmojiNickname("laughing", "😂"), EmojiNickname("flag: Vietnam", "🇻🇳", false), EmojiNickname("yay", "🥳"),
                EmojiNickname("face with tears of joy", "😂", false)),
            changes.toSet()
        )
        val map = EmojiNames.nameMap(index, changes)
        assertEquals("🥳", map[listOf("yay")])
        assertEquals("", map[listOf("flag", "vietnam")])
        assertEquals("👍", map[listOf("thumbs", "up")])
        // a short list is your changes as they are
        assertEquals(listOf(EmojiNickname("yay", "🥳")), EmojiNames.parse("yay\t🥳", index))
    }

    /** With the built-in names off, only your own names give emoji, and your changes stay as they are. */
    @Test
    fun builtInNamesOff() {
        val stored = "lol\t😆\nthumbs up\t👍\t0\nyay\t🥳"
        val changes = EmojiNames.parse(stored, index)
        val off = EmojiNames.nameMap(index, changes, builtIn = false)
        assertEquals(mapOf(listOf("lol") to "😆", listOf("yay") to "🥳"), off)
        assertEquals(null, off[listOf("heart")])
        assertEquals(null, off[listOf("face", "with", "tears", "of", "joy")])
        // the switch doesn't touch the saved list, so back on, everything is as it was
        assertEquals(stored, EmojiNames.serialize(changes))
        val on = EmojiNames.nameMap(index, changes, builtIn = true)
        assertEquals(EmojiNames.nameMap(index, changes), on)
        assertEquals("❤️", on[listOf("heart")])
        assertEquals("", on[listOf("thumbs", "up")])
        // your names still win over the built-in ones
        assertEquals("😆", on[listOf("lol")])
        assertEquals("😂", on[listOf("laughing")])
        // the page's search still finds every emoji, to add a name to
        assertEquals("❤️", EmojiNames.search(index, changes, off, "red heart").firstOrNull()?.emoji)
        assertEquals("🥳", EmojiNames.search(index, changes, off, "yay").firstOrNull()?.emoji)
    }

    /** The list of every built-in name, and its switched-off part. */
    @Test
    fun builtInRows() {
        val all = EmojiNames.builtInRows(index, emptyList())
        val (count, emoji) = EmojiNames.builtInCounts(index)
        assertEquals(count, all.count { it.name != null })
        assertEquals(emoji, all.count { it.name == null })
        assertTrue(all.all { it.on })
        val joy = all.indexOfFirst { it.name == null && it.emoji == "😂" }
        assertTrue(all[joy + 1].name == "laughing")
        val changes = EmojiNames.parse("lol\t😂\t0\nthumbs up\t👍\t0\nzzqx\t🥳", index)
        val rows = EmojiNames.builtInRows(index, changes)
        assertEquals(false, rows.first { it.name == "lol" }.on)
        assertEquals(true, rows.first { it.name == "laughing" }.on)
        // your own names aren't built in
        assertTrue(rows.none { it.name == "zzqx" })
        val off = EmojiNames.builtInRows(index, changes, onlyOff = true)
        assertEquals(listOf(null to "😂", "lol" to "😂", null to "👍", "thumbs up" to "👍"), off.map { it.name to it.emoji })
    }

    /** The numbers (emoji with names, names as written and with their variants). */
    @Test
    fun counts() {
        val withNames = index.emojis.count { it.names.isNotEmpty() }
        val written = index.emojis.sumOf { it.names.size }
        val giving = index.names.values.count { it.isNotEmpty() }
        val none = index.names.values.count { it.isEmpty() }
        println("EMOJI COUNTS: ${index.emojis.size} emoji, $withNames with names; $written names as written; " +
            "${index.names.size} spoken names with variants ($giving give an emoji, $none fit several)")
        assertTrue(withNames > 1800)
    }

    /** Searching is quick, because every key in the search box runs one. */
    @Test
    fun searchIsFast() {
        val names = EmojiNames.nameMap(index, emptyList())
        val changes = listOf(EmojiNickname("zzqx", "🥳"), EmojiNickname("lol", "😂", false))
        val queries = listOf("l", "la", "lau", "laug", "laughing", "h", "he", "hea", "heart", "zz", "q", "smiling heart eyes", "😂", "flag")
        EmojiNames.search(index, changes, names, "warm up")
        val t = System.nanoTime()
        var n = 0
        repeat(100) { n += EmojiNames.search(index, changes, names, queries[it % queries.size]).size }
        val ms = (System.nanoTime() - t) / 1_000_000
        println("EMOJI SEARCH: 100 searches in $ms ms ($n results)")
        assertTrue("100 searches took $ms ms", ms < 500)
    }

    @Test
    fun search() {
        val names = EmojiNames.nameMap(index, emptyList())
        fun first(q: String) = EmojiNames.search(index, emptyList(), names, q).firstOrNull()?.emoji
        assertEquals("😂", first("laughing"))
        assertEquals("😂", first("crying laughing"))
        assertEquals("😍", first("smiling heart eyes"))
        assertEquals("😂", first("😂"))
        assertEquals("❤️", first("heart"))
        assertEquals("🤯", first("mind blown"))
        assertTrue(EmojiNames.search(index, emptyList(), names, "heart").size > 10)
        assertTrue(EmojiNames.search(index, emptyList(), names, "zzqx").isEmpty())
        // your own names are found too
        val changes = listOf(EmojiNickname("zzqx", "🥳"))
        assertEquals("🥳", EmojiNames.search(index, changes, EmojiNames.nameMap(index, changes), "zzqx").firstOrNull()?.emoji)
    }
}

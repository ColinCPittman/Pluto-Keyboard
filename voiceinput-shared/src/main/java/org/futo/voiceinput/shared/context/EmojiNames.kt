package org.futo.voiceinput.shared.context

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One of your changes on the "Emoji" page, which is a name of your own and the emoji it becomes, or
 * (switched off) a built-in name that should give no emoji.
 */
data class EmojiNickname(val phrase: String, val emoji: String, val enabled: Boolean = true)

/** One emoji as the "Emoji" page shows it, with its official name and the built-in names that give it, best first. */
data class EmojiInfo(val emoji: String, val official: String, val names: List<String>)

/** One row of the list of every built-in name. It is an emoji's heading ([name] null) or one of its names, switched on or off. */
data class BuiltInNameRow(val emoji: String, val official: String, val name: String?, val on: Boolean)

/**
 * The built-in names ([EmojiNames.builtIn]), meaning every spoken name -> its emoji, or "" for a name
 * that fits several emoji (it gives none), and the emoji as the "Emoji" page lists them.
 */
class EmojiNameIndex(val emojis: List<EmojiInfo>, val names: Map<List<String>, String>) {
    /** For the page's search (made once), each emoji's names and their words in lower case. */
    internal val searchable: List<EmojiNames.Searchable> by lazy { emojis.map { EmojiNames.searchable(it.emoji, it.names + it.official) } }
}

/**
 * Emoji by name. "<name> emoji" -> the emoji ("thumbs up emoji" -> 👍, "laughing emoji" -> 😂).
 * Every emoji answers to several names, made from the keyboard's emoji data, namely common nicknames
 * ([NICKNAMES]), the official Unicode CLDR name, the gemoji aliases and tags, each also in plural,
 * in the other word order and without "face"/"with". Your own names (the "Emoji" page) come on top.
 */
object EmojiNames {
    /** The longest name, in words (the longest official one, "face with open eyes and hand over mouth", has 8). */
    const val MAX_WORDS = 8

    /**
     * Spoken nicknames of the most used emoji, one emoji per line. A nickname beats the official
     * names and keywords of other emoji ("laughing" is 😂, not 😆 whose alias it is). Each
     * nickname is here once. Not words that also talk about emoji in general ("like", "new").
     */
    private const val NICKNAMES = """
😂 laughing, lol, lmao, crying laughing, laughing crying, cry laughing, laugh crying, crying with laughter, laughing tears, tears of joy, haha, hahaha
🤣 rofl, rolling on the floor laughing, rolling laughing, dying laughing
😭 crying, sobbing, bawling, loudly crying, cry
😢 sad, sad crying, tear, single tear, teary
😊 smiling, smile, blushing, happy, blush, smiley face
🙂 slight smile, slightly smiling, small smile
😀 grinning, grin, big grin
😁 beaming, big smile, cheesy grin
😃 smiley
😅 sweat smile, nervous laugh, nervous smile, nervous, phew
😆 xd, squinting laughing
😉 wink, winking, winky
😇 angel, halo, innocent
🥰 in love, hearts face, smiling hearts, adoring, loved
😍 heart eyes, love eyes, heart eyed
🤩 star struck, star eyes, starry eyed, star eyed
😘 kiss, blowing a kiss, blow a kiss, blowing kiss, kissy face, kissy
😋 yum, yummy, delicious, tasty
😛 tongue out, sticking out tongue, tongue sticking out
😜 winking tongue, cheeky
🤪 crazy, zany, goofy, silly
🤑 money face, money mouth
🤗 hug, hugging, hugs
🤭 giggle, giggling, oops, hand over mouth
🤫 shush, shh, quiet
🤔 thinking, hmm, hmmm, wondering
🤐 zipper mouth, lips sealed, zip it
🤨 raised eyebrow, suspicious, skeptical, eyebrow
😐 neutral, straight face, meh, poker face
😑 expressionless
😶 no mouth, speechless
😏 smirk, smirking
😒 unamused, unimpressed
🙄 eye roll, eyeroll, rolling eyes, eyes rolling, rolling my eyes
😬 grimace, grimacing, yikes, cringe, awkward
😮‍💨 exhale, exhaling, sigh, sighing
🤥 lying, liar, pinocchio
😌 relieved
😔 pensive
😪 sleepy
🤤 drooling, drool
😴 sleeping, asleep, snoring
😷 mask, face mask, medical mask
🤒 sick, thermometer
🤕 hurt, injured, bandage
🤢 nauseous, nauseated, gross, disgusted
🤮 vomiting, vomit, puke, puking, throwing up, barf
🤧 sneezing, sneeze
🥵 too hot, overheated
🥶 cold, freezing, frozen
🥴 woozy, drunk, tipsy
😵 knocked out, dazed
🤯 mind blown, mind blowing, minds blown, blown away, exploding head, head exploding
🤠 cowboy
🥳 party, partying, party face, celebrating
🥸 disguise, disguised
😎 cool, sunglasses, shades
🤓 nerd, nerdy, geek
🧐 monocle
😕 confused
😟 worried
🙁 frown, frowning, frowny, frowny face
😮 wow, surprised, open mouth, gasp
😯 hushed
😲 astonished, shocked
😳 flushed, embarrassed
🥺 pleading, puppy eyes, puppy dog eyes, begging, please
🥹 holding back tears, touched, teary eyed
😨 scared, fearful
😰 anxious
😱 screaming, scream, horrified, terrified
😞 disappointed
😓 downcast
😩 weary, ugh
😫 tired, exhausted
🥱 yawn, yawning, bored
😤 huffing, frustrated, triumph
😡 angry, mad, furious, rage, pouting
😠 annoyed, grumpy
🤬 swearing, cursing
😈 devil, evil, smiling devil, evil grin
👿 angry devil, imp
💀 skull, dead, dying
💩 poop, poo, pile of poop, shit
🤡 clown
👹 ogre
👻 ghost, boo
👽 alien
🤖 robot
🙈 see no evil, see no evil monkey, monkey covering eyes
🙉 hear no evil, hear no evil monkey
🙊 speak no evil, speak no evil monkey
💋 kiss mark, lipstick kiss
💯 hundred, 100, one hundred, a hundred, hundred points, keep it 100
💥 boom, explosion
💦 splash, water drops, sweat drops
💨 dash, zoom, fast
🔥 lit, flame, flames
✨ sparkle, sparkly, magic, shiny, glitter
🌟 glowing star, gold star
❤️ heart, love, love heart, red heart
💔 heartbreak, heartbroken, broken heart
💕 hearts, two hearts
💖 sparkling heart
💓 beating heart, heartbeat
💘 cupid, heart with arrow
❤️‍🔥 heart on fire, burning heart
❤️‍🩹 mending heart, healing heart
🫶 heart hands
👍 thumb up, approve, thumbs up
👎 thumb down, dislike, thumbs down
👌 ok, okay, ok hand, okay hand, perfect
✌️ peace, peace sign, victory
🤞 fingers crossed, crossed fingers, good luck
🤟 love you gesture
🤘 rock on, horns, metal, devil horns
🤙 call me, shaka, hang loose
🤌 chef's kiss, chefs kiss, italian hand
👋 wave, waving, hi, hello, bye, goodbye
👏 clap, clapping, applause
🙌 raising hands, raised hands, praise, yay, hallelujah
🙏 pray, praying, prayer, praying hands, thank you, thanks
✋ high five, raised hand
👊 fist bump, punch
🤝 handshake, deal, shake hands
💪 muscle, flex, flexing, strong, bicep, biceps, strength
🫡 salute, saluting
🫠 melting, melt
🤦 facepalm, face palm, face palming, facepalming
🤷 shrug, shrugging, idk, i don't know, dunno, who knows
🙋 hand up
🙇 bowing
💅 nail polish, nails, sassy
🤳 selfie
👀 eyes, looking, shifty eyes
👅 tongue
👄 lips, mouth
🧠 brain
👉 pointing right, point right
👈 pointing left, point left
👆 pointing up, point up
👇 pointing down, point down
🖕 middle finger, flipping off
✍️ writing
👶 baby
👑 crown
💍 ring, engagement ring
💎 diamond, gem
💰 money, money bag
💵 dollar, dollars, cash
💸 money flying, money with wings
🎉 tada, confetti, congrats, congratulations, hooray, celebration, party popper
🎊 confetti ball
🎂 birthday, happy birthday, birthday cake, cake
🎁 gift, present
🎈 balloon
🎄 christmas, christmas tree, xmas
🎃 pumpkin, halloween, jack o lantern
🎅 santa
🍀 four leaf clover, lucky, luck
🌹 rose
🌸 flower, cherry blossom, blossom, pink flower
💐 bouquet, flowers
🌻 sunflower
🌈 rainbow
☀️ sun, sunny, sunshine
🌙 moon, crescent moon
⚡ lightning, zap, lightning bolt
❄️ snowflake, snow
☃️ snowman
🌧️ rain, rainy
🌊 ocean, sea, water wave
🌍 earth, world, globe, planet
🍕 pizza
🍔 burger
🍟 fries
🌮 taco
🍿 popcorn
🍩 donut
🍪 cookie
☕ coffee, hot drink
🍺 beer, beers
🥂 cheers, toast, clinking glasses
🍷 wine
🍾 champagne, popping cork
🐶 dog, puppy, doggy, doggo
🐱 cat, kitty, kitten
🐼 panda
🦄 unicorn
🐍 snake
🐐 goat
🐢 turtle
🐝 bee
🦋 butterfly
🐸 frog
🐷 pig
🐵 monkey
🦁 lion
🦖 dinosaur, t rex
🐉 dragon
🚀 rocket
✈️ airplane, plane, flight
🚗 car
🏠 house, home
🏃 running, runner
💃 dancing, dance, dancer
🕺 man dancing, dancing man
🎶 music, music notes
🎤 microphone, mic, karaoke
🎧 headphones
📷 camera
📸 photo, camera flash
📱 phone, cell phone, mobile phone
💻 laptop, computer
⏰ alarm clock, alarm
📌 pin, pushpin
📍 location, location pin, map pin
💡 idea, light bulb, lightbulb
🔔 bell, notification
📈 stonks, chart up, trending up, graph up
📉 chart down, trending down, graph down
✅ checkmark, green check, tick, done
❌ x, cross, wrong, red x
⚠️ warning
🚨 siren, alert, emergency, police light
❓ question mark, question
❗ exclamation mark, exclamation
💬 speech bubble, chat
💭 thought bubble
🗣️ speaking head, talking
🏆 trophy, winner, champion
🥇 gold medal, first place
🎯 bullseye, target, direct hit
⚽ soccer, soccer ball
🏀 basketball
🏈 football
🎮 video game, gaming, controller
🎲 dice
🇺🇸 american flag, us flag, usa flag
🏳️‍🌈 pride, pride flag, rainbow flag
🚩 red flag
🫣 peeking
🫨 shaking, shaking face
🌚 new moon face
"""

    /** Filler words of the official names, left out in a shorter version ("smiling face with heart eyes" -> "smiling heart eyes"). */
    private val FILLER = setOf("face", "with")

    /**
     * One-word built-in names that also talk about emoji in general or are everyday words before
     * "emoji" ("I use emoji", "new emoji", "two emoji") are not names. Words of 1 or 2 letters
     * (flag codes like "us", "it") aren't either, except these.
     */
    private val NOT_NAMES = setOf(
        "accept", "attack", "back", "ban", "blow", "block", "burn", "bury", "call", "calling", "chop", "cook", "cut",
        "down", "eat", "end", "explode", "fly", "free", "get", "help", "hear", "hope", "ignore", "investigate", "keyboard",
        "launch", "left", "letters", "lick", "like", "link", "listen", "look", "make", "minus", "more", "multiply",
        "divide", "need", "new", "number", "numbers", "off", "on", "part", "pick", "piece", "play", "plus", "press",
        "refuse", "repeat", "research", "respect", "return", "rewind", "right", "save", "screen", "search", "see",
        "send", "sing", "small", "smell", "shoot", "soon", "sound", "stop", "symbols", "taste", "test", "time", "top",
        "up", "use", "volume", "want", "watch", "wish", "all", "some", "many", "other", "same",
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
    )
    private val SHORT_NAMES = setOf("ok", "tv", "uk", "eu", "x")

    private fun isNotName(words: List<String>): Boolean = words.size == 1 && words[0].let {
        it in NOT_NAMES || (it.length <= 2 && it !in SHORT_NAMES)
    }

    /** [emoji] without the emoji variation selector, to match emoji written with or without it. */
    private fun bare(emoji: String) = emoji.replace("️", "")

    /** The plural of a name's last word, or its singular ("heart" <-> "hearts"). */
    private fun otherNumber(words: List<String>): List<String>? {
        val w = words.last()
        if (w.length < 3 || !w.all { it.isLetter() }) return null
        val other = when {
            w.endsWith("ies") && w.length > 4 -> w.dropLast(3) + "y"
            w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes") || w.endsWith("sses") -> w.dropLast(2)
            w.endsWith("ss") || w.endsWith("us") -> w + "es"
            w.endsWith("s") -> w.dropLast(1)
            w.endsWith("y") && w[w.length - 2] !in "aeiou" -> w.dropLast(1) + "ies"
            w.endsWith("ch") || w.endsWith("sh") || w.endsWith("x") -> w + "es"
            else -> w + "s"
        }
        return words.dropLast(1) + other
    }

    /**
     * The built-in names, from the keyboard's emoji data (gemoji JSON with "emoji", "description" =
     * the CLDR name, "aliases", "tags") and [NICKNAMES]. When several emoji share a name, the
     * best source wins (nicknames, then official names, aliases and tags, each before its
     * shorter/plural/reordered versions), and a name two emoji share at the same level gives none.
     */
    fun builtIn(gemojiJson: String): EmojiNameIndex {
        val items = Json.parseToJsonElement(gemojiJson) as? JsonArray ?: return EmojiNameIndex(emptyList(), emptyMap())
        class Raw(val emoji: String, val official: String, val aliases: List<String>, val tags: List<String>)
        fun strings(o: JsonObject, key: String) = (o[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.content.replace('_', ' ') } ?: emptyList()
        val raws = items.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val emoji = o["emoji"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val name = o["description"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (!isValidEmoji(emoji)) return@mapNotNull null
            Raw(emoji, name, strings(o, "aliases"), strings(o, "tags"))
        }
        val byBare = raws.associateBy { bare(it.emoji) }
        val nicknames = HashMap<String, List<String>>()  // emoji -> nicknames
        for (line in NICKNAMES.lines()) {
            val sp = line.indexOf(' ')
            if (sp <= 0) continue
            val raw = byBare[bare(line.substring(0, sp))] ?: continue
            nicknames[raw.emoji] = line.substring(sp + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }

        // the same word strings for every name (less memory because the names share their words)
        val pool = HashMap<String, String>()
        fun words(phrase: String) = VoiceCommands.phraseWords(phrase).map { pool.getOrPut(it) { it } }
        // name -> (best rank, the emoji at that rank), where even ranks are names as written and odd ones their variants
        val best = HashMap<List<String>, Pair<Int, MutableSet<String>>>()
        fun add(words: List<String>, emoji: String, rank: Int) {
            if (words.size !in 1..MAX_WORDS || isNotName(words)) return
            val cur = best[words]
            when {
                cur == null || rank < cur.first -> best[words] = rank to mutableSetOf(emoji)
                rank == cur.first -> cur.second.add(emoji)
            }
        }
        // in order of their rank, as (rank, emoji, phrase as written)
        val exact = ArrayList<Triple<Int, String, String>>()
        for (r in raws) {
            nicknames[r.emoji]?.forEach { exact.add(Triple(0, r.emoji, it)) }
            exact.add(Triple(2, r.emoji, r.official))
            r.aliases.forEach { exact.add(Triple(4, r.emoji, it)) }
            r.tags.forEach { exact.add(Triple(6, r.emoji, it)) }
        }
        for ((rank, emoji, phrase) in exact) {
            val w = words(phrase)
            if (w.isEmpty()) continue
            add(w, emoji, rank)
            val variants = ArrayList<List<String>>()
            if (rank == 2) {
                // "smiling face with heart eyes" -> "smiling heart eyes", "thinking face" -> "thinking"
                w.filter { it !in FILLER }.let { if (it.isNotEmpty() && it.size < w.size) variants.add(it) }
                // "flag: Vietnam" -> "Vietnam flag"
                if (w.size >= 2 && w[0] == "flag") variants.add(w.drop(1) + w[0])
            }
            // the other word order of two words ("laughing crying", "heart red")
            if (rank == 2 || rank == 4) for (v in listOf(w) + variants) if (v.size == 2) variants.add(listOf(v[1], v[0]))
            for (v in variants.toList()) otherNumber(v)?.let { variants.add(it) }
            otherNumber(w)?.let { variants.add(it) }
            for (v in variants) add(v, emoji, rank + 1)
        }
        val names = HashMap<List<String>, String>(best.size * 2)
        for ((w, p) in best) names[w] = if (p.second.size == 1) p.second.first() else ""

        // for the page, each emoji's names as written that give it
        val shown = HashMap<String, LinkedHashMap<List<String>, String>>()
        for ((_, emoji, phrase) in exact.sortedBy { it.first }) {
            val w = VoiceCommands.phraseWords(phrase)
            if (names[w] == emoji) shown.getOrPut(emoji) { LinkedHashMap() }.putIfAbsent(w, phrase)
        }
        val emojis = raws.map { EmojiInfo(it.emoji, it.official, shown[it.emoji]?.values?.toList() ?: emptyList()) }
        return EmojiNameIndex(emojis, names)
    }

    /** "emoji" as the voice model writes it. */
    private val EMOJI_WORDS = setOf("emoji", "emojis", "emojie", "emojies", "emojii")

    /** "emoji" misheard as two words ("a moji", "at Mojie"). */
    private val MOJI_WORDS = setOf("moji", "mojis", "mojie", "mojies", "mojee")
    private val MOJI_BEFORE = setOf("a", "at", "e")

    /** How many of the words ending at [k] say "emoji" (0 means they don't). */
    fun keywordLength(norm: List<String>, k: Int): Int = when {
        norm[k] in EMOJI_WORDS -> 1
        k >= 1 && norm[k] in MOJI_WORDS && norm[k - 1] in MOJI_BEFORE -> 2
        else -> 0
    }

    /** Whether [phrase] can be a name (1 to [MAX_WORDS] words). */
    fun isValidName(phrase: String): Boolean = VoiceCommands.phraseWords(phrase).size in 1..MAX_WORDS

    /** Whether [emoji] can be an entry's emoji (a few characters, not plain text, so no letters or spaces). */
    fun isValidEmoji(emoji: String): Boolean {
        val e = emoji.trim()
        return e.isNotEmpty() && e.length <= 32 && e.any { it.code > 0x7F } && e.none { it.isLetter() || it.isWhitespace() }
    }

    /** One entry per line, as `phrase<TAB>emoji`, plus `<TAB>0` when it is switched off. */
    fun serialize(entries: List<EmojiNickname>): String =
        entries.joinToString("\n") {
            // the name as written ("flag: Vietnam"), so the list still shows it that way
            "${it.phrase.replace(Regex("\\s+"), " ").trim()}\t${it.emoji.trim()}" + if (it.enabled) "" else "\t0"
        }

    /**
     * Your changes, from [serialize]'s format (blank means none). Invalid or repeated names are skipped.
     * Lists saved before the built-in names grew held every official name, and those are read as
     * your changes only (an official name left out of such a list was deleted, so it is off).
     */
    fun parse(text: String, index: EmojiNameIndex? = null): List<EmojiNickname> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<EmojiNickname>()
        val seen = HashSet<List<String>>()
        for (line in text.lines()) {
            val parts = line.split('\t')
            if (parts.size < 2) continue
            val words = VoiceCommands.phraseWords(parts[0])
            if (words.size !in 1..MAX_WORDS || !isValidEmoji(parts[1]) || !seen.add(words)) continue
            out.add(EmojiNickname(parts[0].trim(), parts[1].trim(), enabled = parts.getOrNull(2) != "0"))
        }
        // only a list of at least half the official names can be an old one (yours is a few lines)
        if (index == null || index.emojis.isEmpty() || out.size < index.emojis.size / 2) return out
        val official = index.emojis.associate { bare(it.emoji) to VoiceCommands.phraseWords(it.official) }
        fun isOfficial(e: EmojiNickname) = official[bare(e.emoji)] == VoiceCommands.phraseWords(e.phrase)
        if (out.count { isOfficial(it) } < index.emojis.size / 2) return out
        // names two emoji share ("keycap: #", "keycap: *") were never in such a list, so they are not deleted
        val shared = index.emojis.groupingBy { VoiceCommands.phraseWords(it.official) }.eachCount()
        val deleted = index.emojis
            .filter { VoiceCommands.phraseWords(it.official).let { w -> w !in seen && shared[w] == 1 } }
            .map { EmojiNickname(it.official, it.emoji, enabled = false) }
        return out.filter { !(it.enabled && isOfficial(it)) } + deleted
    }

    /**
     * The name words -> emoji map the rules use, made of the built-in names with your changes ("" = no
     * emoji). With [builtIn] off, only your own names give emoji, and your changes are kept as they are.
     */
    fun nameMap(index: EmojiNameIndex, changes: List<EmojiNickname>, builtIn: Boolean = true): Map<List<String>, String> {
        if (!builtIn) {
            val out = HashMap<List<String>, String>()
            for (e in changes) if (e.enabled) out[VoiceCommands.phraseWords(e.phrase)] = e.emoji.trim()
            return out
        }
        if (changes.isEmpty()) return index.names
        val out = HashMap(index.names)
        for (e in changes) out[VoiceCommands.phraseWords(e.phrase)] = if (e.enabled) e.emoji.trim() else ""
        return out
    }

    /** How many built-in names (as written) there are, and for how many emoji. */
    fun builtInCounts(index: EmojiNameIndex): Pair<Int, Int> =
        index.emojis.sumOf { it.names.size } to index.emojis.count { it.names.isNotEmpty() }

    /**
     * The list of every built-in name, by emoji, with a heading row, then each name, off when you
     * switched it off. With [onlyOff], only the switched-off names (and their emoji's heading).
     */
    fun builtInRows(index: EmojiNameIndex, changes: List<EmojiNickname>, onlyOff: Boolean = false): List<BuiltInNameRow> {
        val off = changes.filter { !it.enabled }.mapTo(HashSet()) { VoiceCommands.phraseWords(it.phrase) }
        val out = ArrayList<BuiltInNameRow>()
        for (info in index.emojis) {
            val names = info.names.map { it to (VoiceCommands.phraseWords(it) !in off) }.filter { !onlyOff || !it.second }
            if (names.isEmpty()) continue
            out.add(BuiltInNameRow(info.emoji, info.official, null, true))
            for ((n, on) in names) out.add(BuiltInNameRow(info.emoji, info.official, n, on))
        }
        return out
    }

    /** What "<[phrase]> emoji" gives with [names]. It is the emoji, "" for none (several fit, or switched off), or null (not a name). */
    fun lookup(names: Map<List<String>, String>, phrase: String): String? = names[VoiceCommands.phraseWords(phrase)]

    /**
     * The "Emoji" page's search. It gives the emoji that [query] (a word, a name or an emoji) finds, best
     * first (the one "<query> emoji" gives, then names that start with it, then names that contain it).
     */
    fun search(index: EmojiNameIndex, changes: List<EmojiNickname>, names: Map<List<String>, String>, query: String): List<EmojiInfo> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val qBare = bare(query.trim())
        val said = names[VoiceCommands.phraseWords(q)]?.takeIf { it.isNotEmpty() }?.let { bare(it) }
        // your own names (a few, made searchable per search)
        val mine = changes.filter { it.enabled }.groupBy({ bare(it.emoji) }, { it.phrase }).mapValues { (b, n) -> searchable(b, n) }
        val scored = ArrayList<Pair<Int, EmojiInfo>>()
        for ((i, info) in index.emojis.withIndex()) {
            val s = index.searchable[i]
            val score = if (s.bare == qBare || s.bare == said) 0 else minOf(s.score(q), mine[s.bare]?.score(q) ?: 5)
            if (score < 5) scored.add(score to info)
        }
        return scored.sortedBy { it.first }.map { it.second }
    }

    /** An emoji's names in lower case, and their words, for [search]. */
    internal class Searchable(val bare: String, val lower: Array<String>, val words: Array<String>) {
        /** Scores 1 if a name is [q], 2 if a name starts with it, 3 if a word does, 4 if a name contains it, and 5 for none. */
        fun score(q: String): Int = when {
            lower.any { it == q } -> 1
            lower.any { it.startsWith(q) } -> 2
            words.any { it.startsWith(q) } -> 3
            lower.any { it.contains(q) } -> 4
            else -> 5
        }
    }

    internal fun searchable(emoji: String, names: List<String>) = Searchable(
        bare(emoji),
        names.map { it.lowercase() }.toTypedArray(),
        names.flatMap { VoiceCommands.phraseWords(it) }.distinct().toTypedArray()
    )
}

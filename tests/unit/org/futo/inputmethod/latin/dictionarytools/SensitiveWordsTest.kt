package org.futo.inputmethod.latin.dictionarytools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveWordsTest {
    private val shouldFlag = listOf(
        // letters and digits mixed in one word
        "Tr0ub4dor", "a8f2k9", "hunter2", "password123", "Summer2024", "Welcome1", "letmein99",
        "monkey123", "1qaz2wsx", "q1w2e3r4", "passw0rd", "s3cr3t", "7h3Qu1ckBr0wnF0x", "xK9mP2vL",
        "8d3f9a1c", "a1b2c3d4", "R2D2C3PO", "ABCD1234EFGH", "iPhone15",
        // symbols inside a word
        "P@ssw0rd", "P@ssw0rd!", "Pa\$\$word", "pass#word", "user:pass", "my_secret_key", "Zx9#mQ2!",
        "abc!defg",
        // hex, base64 and random-looking strings
        "9f86d081884c7d659a2feaa0c55ad015", "deadbeefcafebabe", "3f2a7c9e1b4d6f80",
        "dGhpcyBpcyBhIHNlY3JldA==", "aBcDeFgHiJkLmN", "qzxwvbnmkplrtd", "bcdfghjklmnpq",
        "550e8400-e29b-41d4-a716-446655440000", "a8-f2-k9",
        // known key prefixes
        "sk-proj-abcdefghijklmnop", "sk_live_abcdefghijklmn", "ghp_16C7e42F292c6912E7710c838347Ae178B4a",
        "gho_abcdefghijklmnopqrstu", "github_pat_11ABCDEFG0123", "glpat-abcdefghijklmnopqrst",
        "AKIAIOSFODNN7EXAMPLE", "xoxb-123456789012-abcdef", "xoxp-abcdefghijklmn",
        "AIzaSyA1b2C3d4E5f6G7h8", "ya29.a0AfH6SMBx", "hf_abcdefghijklmnopqrstu",
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9", "npm_abcdefghijklmnopqrstuvwxyz",
        "SG.abcdefghijklmnopqrstuv",
        // e-mail addresses
        "colin@example.com", "john.doe+test@gmail.com", "user@mail.co.uk",
        // long digit runs and grouped numbers
        "12345678", "4111111111111111", "4111-1111-1111-1111", "555-123-4567", "20240115",
    )

    private val mustNotFlag = listOf(
        // ordinary words, names and capitalisation
        "hello", "keyboard", "Colin", "Xiomara", "McKenna", "McDonald's", "iPhone", "JavaScript",
        "PowerPoint", "YouTube", "WiFi", "WordPerfectOffice", "PowerPointPresentation", "OK",
        "antidisestablishmentarianism", "Pneumonoultramicroscopicsilicovolcanoconiosis",
        "rhythms", "strengths", "crwth",
        // contractions and apostrophes
        "don't", "can't", "it's", "O'Neil", "rock'n'roll", "y'all", "don’t", "L'Oréal", "aujourd'hui",
        // hyphenated words
        "well-known", "mother-in-law", "x-ray", "e-mail", "T-shirt", "Wi-Fi", "co-op", "self-esteem",
        "COVID-19", "21-year-old", "F-150", "Saint-Étienne",
        // numbers people type
        "1st", "2nd", "3rd", "4th", "22nd", "101st", "1990s", "80s", "'90s", "10am", "5pm", "1080p",
        "64GB", "5km", "2024", "12345", "3.14", "1,000", "1,000,000", "\$1,000", "100%", "10:30",
        "24/7", "12/25/2024", "555-1234", "v1.2.3",
        // short codes and model numbers
        "4K", "3D", "MP3", "PS5", "B2B", "2FA", "H2O", "x86", "gr8", "4x4", "A380",
        // abbreviations, slashes and domains
        "e.g.", "U.S.A.", "Ph.D.", "Mr.", "and/or", "his/her", "km/h", "AT&T", "R&D", "google.com", "node.js",
        // other scripts and accents
        "café", "naïve", "Zoë", "über", "résumé", "straße", "Dvořák", "Привет", "こんにちは", "你好",
        // handles, hashtags, emoji, punctuation
        "@colin", "#blessed", "😀😀😀😀", "👍🏽", "...", "hello!", "(hello)",
    )

    @Test
    fun flagsMachineLikeStrings() {
        val missed = shouldFlag.filterNot { SensitiveWords.looksSensitive(it) }
        assertEquals("not flagged: $missed", emptyList<String>(), missed)
    }

    @Test
    fun leavesNormalWordsAlone() {
        val wrong = mustNotFlag.filter { SensitiveWords.looksSensitive(it) }
        assertEquals("wrongly flagged: $wrong", emptyList<String>(), wrong)
    }

    @Test
    fun listsAreLargeEnough() {
        assertTrue(shouldFlag.size >= 40)
        assertTrue(mustNotFlag.size >= 60)
    }

    @Test
    fun nullAndShortTokens() {
        assertEquals(false, SensitiveWords.looksSensitive(null))
        assertEquals(false, SensitiveWords.looksSensitive(""))
        assertEquals(false, SensitiveWords.looksSensitive("a1"))
        assertEquals(false, SensitiveWords.looksSensitive("C++"))
    }
}

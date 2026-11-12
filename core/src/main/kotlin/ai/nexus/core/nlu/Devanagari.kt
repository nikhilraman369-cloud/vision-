package ai.nexus.core.nlu

/**
 * Rough Devanagari → Latin (Hinglish-style) transliteration, so that Hindi
 * speech-recognition output such as "इंस्टाग्राम खोलो" is understood by the
 * same rules as "instagram kholo". It is intentionally simple: it does not
 * need to be linguistically perfect, only consistent with how people type
 * Hinglish, because matching downstream is fuzzy.
 */
object Devanagari {
    private val vowels = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ऋ' to "ri",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o", 'ऍ' to "e",
    )
    private val matras = mapOf(
        'ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "u", 'ृ' to "ri",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ॅ' to "e",
    )
    private val consonants = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh",
        'स' to "s", 'ह' to "h",
    )
    private const val VIRAMA = '्'
    private const val NUKTA = '़'
    private val nuktaMap = mapOf('ज' to "z", 'फ' to "f", 'ड' to "d", 'क' to "q", 'ख' to "kh", 'ग' to "g")
    private val digits = "०१२३४५६७८९"

    /** Whole-word fixes where char-level output differs from common Hinglish spelling. */
    private val wordFixes = mapOf(
        "vhatsep" to "whatsapp", "vhatsaep" to "whatsapp", "vhatsaip" to "whatsapp", "vatsap" to "whatsapp", "vhatsap" to "whatsapp",
        "yutyub" to "youtube", "yootyoob" to "youtube", "yutub" to "youtube",
        "gugal" to "google", "krom" to "chrome", "spotiphai" to "spotify", "jimel" to "gmail",
        "vidiyo" to "video", "vidio" to "video", "kament" to "comment",
        "sev" to "save", "laik" to "like", "sharch" to "search", "sarch" to "search", "serch" to "search",
        "mesej" to "message", "maisej" to "message", "baik" to "back", "bek" to "back", "hom" to "home",
        "rils" to "reels", "ril" to "reel", "shorts" to "shorts", "vaiphai" to "wifi", "blututh" to "bluetooth",
        "tarch" to "torch", "volyum" to "volume", "brightnes" to "brightness",
        "mai" to "main", "mein" to "mein", "me" to "me",
    )

    fun containsDevanagari(s: String): Boolean = s.any { it in 'ऀ'..'ॿ' }

    fun toLatin(input: String): String {
        if (!containsDevanagari(input)) return input
        val out = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            val next = input.getOrNull(i + 1)
            when {
                c in consonants -> {
                    var base = consonants.getValue(c)
                    var j = i + 1
                    if (next == NUKTA) { base = nuktaMap[c] ?: base; j++ }
                    val after = input.getOrNull(j)
                    when {
                        after == VIRAMA -> { out.append(base); j++ }
                        after != null && after in matras -> { out.append(base).append(matras.getValue(after)); j++ }
                        else -> {
                            out.append(base)
                            // Schwa deletion: no inherent 'a' at the end of a word.
                            val endOfWord = after == null || !(after in consonants || after in vowels)
                            if (!endOfWord) out.append('a')
                        }
                    }
                    i = j
                    continue
                }
                c in vowels -> out.append(vowels.getValue(c))
                c in matras -> out.append(matras.getValue(c))
                c == 'ं' || c == 'ँ' -> out.append('n')
                c == 'ः' -> out.append('h')
                c == VIRAMA || c == NUKTA -> {}
                c == '।' || c == '॥' -> out.append('.')
                digits.indexOf(c) >= 0 -> out.append(digits.indexOf(c))
                else -> out.append(c)
            }
            i++
        }
        return out.toString().split(' ').joinToString(" ") { w ->
            val lw = w.lowercase()
            wordFixes[lw] ?: w
        }
    }
}

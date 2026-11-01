package com.jkdc.app.core

/** Typed writing drill over due words. Deliberately strict on purpose:
 *  spelling practice means diacritics are significant in all eight languages. */
object WordPractice {
    /** Accepts the observed form or its lemma, ignoring case and surrounding punctuation. */
    fun isCorrect(answer:String, form:String, lemma:String): Boolean {
        val normalized = normalize(answer)
        if (normalized.isEmpty()) return false
        return normalized == normalize(form) || normalized == normalize(lemma)
    }

    fun normalize(value:String): String {
        var text = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC).lowercase().trim()
        while (text.isNotEmpty() && isPunctuation(text.first())) text = text.substring(1)
        while (text.isNotEmpty() && isPunctuation(text.last())) text = text.substring(0, text.length - 1)
        return text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** The example sentence with every occurrence of the target form blanked out; empty when the form is not in it. */
    fun maskedExample(example:String, form:String): String {
        if (form.isEmpty()) return ""
        val masked = Regex(Regex.escape(form), RegexOption.IGNORE_CASE).replace(example, "___")
        return if (masked == example) "" else masked
    }

    private fun isPunctuation(c: Char): Boolean = when (Character.getType(c)) {
        Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt() -> true
        else -> false
    }
}

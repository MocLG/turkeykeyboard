package com.tercuman.keyboard.engine

enum class Lang(val code: String, val englishName: String, val label: String) {
    TURKISH("tr", "Turkish", "TR"),
    ENGLISH("en", "English", "EN"),
}

data class Direction(val from: Lang, val to: Lang) {
    val label: String get() = "${from.label}→${to.label}"

    companion object {
        val TR_TO_EN = Direction(Lang.TURKISH, Lang.ENGLISH)
        val EN_TO_TR = Direction(Lang.ENGLISH, Lang.TURKISH)
    }
}

object Prompts {
    const val MAX_INPUT_CHARS = 2500

    fun translateGemma(text: String, direction: Direction): String {
        val s = direction.from
        val t = direction.to
        return buildString {
            append("<start_of_turn>user\n")
            append("You are a professional ${s.englishName} (${s.code}) to ${t.englishName} (${t.code}) translator. ")
            append("Your goal is to accurately convey the meaning and nuances of the original ${s.englishName} text ")
            append("while adhering to ${t.englishName} grammar, vocabulary, and cultural sensitivities.\n")
            append("Produce only the ${t.englishName} translation, without any additional explanations or commentary. ")
            append("Please translate the following ${s.englishName} text into ${t.englishName}:\n\n\n")
            append(text.trim())
            append("<end_of_turn>\n")
            append("<start_of_turn>model\n")
        }
    }

    fun maxOutputTokens(text: String): Int = (text.length / 2 + 48).coerceIn(48, 1024)

    fun cleanOutput(raw: String): String =
        raw.replace("<end_of_turn>", "")
            .replace("<start_of_turn>", "")
            .trim()
}

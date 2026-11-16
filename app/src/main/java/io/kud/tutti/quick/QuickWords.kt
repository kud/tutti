package io.kud.tutti.quick

import android.content.Context

/** The headline's parts in this locale, joined as the now-showing card joins them: "Netflix · Cinema". */
fun QuickHeadline.text(context: Context): String = words.joinToString(" · ") { word ->
    when (word) {
        is QuickWord.Plain -> word.text
        is QuickWord.Res -> if (word.arg != null) context.getString(word.id, word.arg) else context.getString(word.id)
    }
}

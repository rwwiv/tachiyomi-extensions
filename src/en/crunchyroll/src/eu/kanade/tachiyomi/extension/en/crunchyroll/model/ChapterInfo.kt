package eu.kanade.tachiyomi.extension.en.crunchyroll.model

data class ChapterInfo(
    val chapterId: String,
    val number: Float,
    val availabilityStart: String,
    val locale: ResponseLocale?
)

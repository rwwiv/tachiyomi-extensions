package eu.kanade.tachiyomi.extension.en.crunchyroll.model

data class SeriesInfo(
    val authors: String?,
    val artist: String?,
    val url: String,
    val seriesId: String,
    val locale: ResponseLocale?
)

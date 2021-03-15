package eu.kanade.tachiyomi.extension.en.crunchyroll.model

import com.google.gson.annotations.SerializedName

data class PageLocale(
    @SerializedName(value = "enUS")
    val enUS: LocaleLinks
)

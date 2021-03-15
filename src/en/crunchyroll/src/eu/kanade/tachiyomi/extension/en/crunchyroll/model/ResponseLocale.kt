package eu.kanade.tachiyomi.extension.en.crunchyroll.model

import com.google.gson.annotations.SerializedName

data class ResponseLocale(
    @SerializedName(value = "enUS")
    val enUS: Map<String, String>
)

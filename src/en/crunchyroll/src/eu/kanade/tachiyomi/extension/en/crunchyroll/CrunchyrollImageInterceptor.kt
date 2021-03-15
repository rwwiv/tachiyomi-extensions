package eu.kanade.tachiyomi.extension.en.crunchyroll

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.experimental.xor

class CrunchyrollImageInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        if (chain.request().headers()["X-Requested-With"] == null)
            return response

        val image = decodeImage(response.body()!!.byteStream())
        val body = ResponseBody.create(MediaType.parse("image/jpeg"), image)

        return response.newBuilder()
            .body(body)
            .build()
    }

    private fun decodeImage(image: InputStream): ByteArray {
        val byteArrayOutputStream = ByteArrayOutputStream()
        image.copyTo(byteArrayOutputStream)

        val imageArr = byteArrayOutputStream.toByteArray()

        for (i in imageArr.indices) {
            imageArr[i] = imageArr[i].xor(66)
        }

        return imageArr
    }
}

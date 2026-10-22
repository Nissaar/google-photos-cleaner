package xyz.photocleaner.net

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The session cookie must reach Google's media hosts over HTTPS, and nothing else. */
class GoogleSessionInterceptorTest {

    private val interceptor = GoogleSessionInterceptor { "SID=secret" }

    @Test
    fun `google media hosts get the cookie`() {
        for (url in listOf(
            "https://lh3.googleusercontent.com/abc=w100",
            "https://video.ggpht.com/x",
            "https://photos.google.com/",
        )) {
            val sent = send(url)
            assertEquals(url, "SID=secret", sent.header("Cookie"))
            assertEquals(url, "https://photos.google.com/", sent.header("Referer"))
        }
    }

    @Test
    fun `other hosts never get it, even if the request already carried one`() {
        // What a redirect hop looks like: a header set upstream, a non-Google host.
        for (url in listOf(
            "https://example.com/",
            "https://googleusercontent.com.evil.test/",
            "https://notgoogle.com/",
        )) {
            val sent = send(url, Request.Builder().header("Cookie", "SID=secret").header("Referer", "x"))
            assertNull(url, sent.header("Cookie"))
            assertNull(url, sent.header("Referer"))
        }
    }

    @Test
    fun `cleartext never gets it`() {
        assertNull(send("http://lh3.googleusercontent.com/abc").header("Cookie"))
    }

    /**
     * Runs the interceptor in a real client whose last interceptor records the request
     * instead of touching the network, so the test does not depend on the shape of
     * OkHttp's Interceptor.Chain (which grew many members in OkHttp 5).
     */
    private fun send(url: String, builder: Request.Builder = Request.Builder()): Request {
        var sent: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor { chain ->
                sent = chain.request()
                Response.Builder().request(sent!!).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").build()
            }
            .build()
        client.newCall(builder.url(url).build()).execute().close()
        return sent!!
    }
}

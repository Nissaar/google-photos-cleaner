package xyz.photocleaner.net

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

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

    private fun send(url: String, builder: Request.Builder = Request.Builder()): Request {
        val chain = CapturingChain(builder.url(url).build())
        interceptor.intercept(chain)
        return chain.sent!!
    }

    private class CapturingChain(private val request: Request) : Interceptor.Chain {
        var sent: Request? = null

        override fun request() = request

        override fun proceed(request: Request): Response {
            sent = request
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").build()
        }

        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis() = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
        override fun readTimeoutMillis() = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
        override fun writeTimeoutMillis() = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
    }
}

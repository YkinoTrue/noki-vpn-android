package com.noki.vpn.data

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidUpdateDownloadTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun declaredBodyAboveHardLimitIsRejectedBeforeWriting() {
        val destination = seededDestination()

        val failure = download(
            destination = destination,
            body = responseBody(
                bytes = "small-body".toByteArray(),
                declaredLength = MAX_APK_BYTES + 1L,
            ),
            expectedSizeBytes = null,
        )

        assertFailedDownloadPreservesDestination(destination, failure)
        assertEquals("apk_file_too_large", failure?.message)
    }

    @Test
    fun streamedBodyLargerThanMetadataIsRejectedAndPartIsRemoved() {
        val destination = seededDestination()
        val apkBytes = "nine-byte".toByteArray()

        val failure = download(
            destination = destination,
            body = responseBody(apkBytes, declaredLength = -1L),
            expectedSizeBytes = apkBytes.size.toLong() - 1L,
        )

        assertFailedDownloadPreservesDestination(destination, failure)
        assertEquals("apk_size_mismatch", failure?.message)
    }

    @Test
    fun unknownLengthBodyAboveHardLimitIsRejectedWhileStreaming() {
        val destination = seededDestination()

        val failure = download(
            destination = destination,
            body = streamingResponseBody(MAX_APK_BYTES + 1L),
            expectedSizeBytes = null,
        )

        assertFailedDownloadPreservesDestination(destination, failure)
        assertEquals("apk_file_too_large", failure?.message)
    }

    @Test
    fun truncatedBodyIsRejectedAndPartIsRemoved() {
        val destination = seededDestination()
        val apkBytes = "truncated".toByteArray()

        val failure = download(
            destination = destination,
            body = responseBody(apkBytes, declaredLength = -1L),
            expectedSizeBytes = apkBytes.size.toLong() + 1L,
        )

        assertFailedDownloadPreservesDestination(destination, failure)
        assertEquals("apk_size_mismatch", failure?.message)
    }

    @Test
    fun validBodyIsPublishedOnlyAfterSizeAndChecksumVerification() {
        val destination = seededDestination()
        val apkBytes = "verified-apk".toByteArray()

        val failure = download(
            destination = destination,
            body = responseBody(apkBytes),
            expectedSizeBytes = apkBytes.size.toLong(),
            expectedSha256 = sha256Hex(apkBytes),
        )

        assertEquals(null, failure)
        assertArrayEquals(apkBytes, destination.readBytes())
        assertFalse(partFile(destination).exists())
    }

    private fun download(
        destination: File,
        body: ResponseBody,
        expectedSizeBytes: Long?,
        expectedSha256: String? = null,
    ): Throwable? = runCatching {
        runBlocking {
            backendApi(body).downloadAndroidUpdateApk(
                token = "token",
                apkUrl = APK_URL,
                expectedSha256 = expectedSha256,
                expectedSizeBytes = expectedSizeBytes,
                destination = destination,
            )
        }
    }.exceptionOrNull()

    private fun backendApi(body: ResponseBody): BackendApiClient =
        BackendApiClient(
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body)
                        .build()
                }
                .build(),
            baseUrl = BASE_URL,
        )

    private fun responseBody(bytes: ByteArray, declaredLength: Long = bytes.size.toLong()): ResponseBody {
        val content = Buffer().write(bytes)
        return object : ResponseBody() {
            override fun contentType(): MediaType = APK_MIME_TYPE.toMediaType()

            override fun contentLength(): Long = declaredLength

            override fun source(): BufferedSource = content
        }
    }

    private fun streamingResponseBody(byteCount: Long): ResponseBody {
        val content = object : Source {
            private var remaining = byteCount

            override fun read(sink: Buffer, byteCount: Long): Long {
                if (remaining == 0L) return -1L
                val count = minOf(remaining, byteCount, STREAM_CHUNK.size.toLong()).toInt()
                sink.write(STREAM_CHUNK, 0, count)
                remaining -= count
                return count.toLong()
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() {
                remaining = 0L
            }
        }.buffer()
        return object : ResponseBody() {
            override fun contentType(): MediaType = APK_MIME_TYPE.toMediaType()

            override fun contentLength(): Long = -1L

            override fun source(): BufferedSource = content
        }
    }

    private fun seededDestination(): File = temporaryFolder.newFile("noki.apk").apply {
        writeBytes(OLD_APK)
    }

    private fun assertFailedDownloadPreservesDestination(destination: File, failure: Throwable?) {
        assertNotNull("download must fail", failure)
        assertTrue(destination.exists())
        assertArrayEquals(OLD_APK, destination.readBytes())
        assertFalse(partFile(destination).exists())
    }

    private fun partFile(destination: File): File =
        File(destination.parentFile, "${destination.name}.part")

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }

    private companion object {
        const val BASE_URL = "https://example.test"
        const val APK_URL = "$BASE_URL/v1/app/android-releases/noki.apk"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val MAX_APK_BYTES = 256L * 1024L * 1024L
        val OLD_APK = "old-apk".toByteArray()
        val STREAM_CHUNK = ByteArray(DEFAULT_BUFFER_SIZE)
    }
}

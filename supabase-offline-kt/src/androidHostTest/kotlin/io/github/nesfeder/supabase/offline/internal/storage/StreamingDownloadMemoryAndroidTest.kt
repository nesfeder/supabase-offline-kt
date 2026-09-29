package io.github.nesfeder.supabase.offline.internal.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.FakeStreamingBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * A channel download streams through the cache without holding the object in memory: a download
 * far larger than any margin leaves the retained heap flat.
 */
@RunWith(AndroidJUnit4::class)
class StreamingDownloadMemoryAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.cacheDir, "offline-streaming-${Random.nextLong().toULong()}")

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun retainedHeap(): Long {
        val runtime = Runtime.getRuntime()
        repeat(2) { System.gc() }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    @Test
    fun a_large_channel_download_streams_with_flat_memory() = runTest(timeout = 5.minutes) {
        // Given — a 256 MiB object the fake server generates as it goes
        directory.mkdirs()
        val baseline = retainedHeap()
        var peak = baseline
        var nextSample = 0L
        val bucket = newTestBucketApi(
            delegate = FakeStreamingBucketApi(FakeBucketApi(bucketId = "media"), TOTAL_BYTES) { written ->
                if (written >= nextSample) {
                    peak = maxOf(peak, retainedHeap())
                    nextSample += SAMPLE_EVERY_BYTES
                }
            },
            baseDirectory = directory.toOkioPath(),
        )

        // When — the caller drains its channel as the bytes arrive
        val channel = ByteChannel()
        val received = withContext(Dispatchers.Default) {
            val reader = async {
                val buffer = ByteArray(64 * 1024)
                var count = 0L
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read < 0) break
                    count += read
                }
                count
            }
            bucket.downloadAuthenticated("big.bin", channel) { }
            reader.await()
        }

        // Then — every byte arrived, and the heap never held more than a small fraction of them
        val growthMiB = (peak - baseline) / MIB
        assertEquals(TOTAL_BYTES, received)
        assertTrue(growthMiB < 64, "retained heap grew by $growthMiB MiB for a ${TOTAL_BYTES / MIB} MiB download")
    }

    private companion object {
        const val MIB = 1024L * 1024L
        const val TOTAL_BYTES = 256L * MIB
        const val SAMPLE_EVERY_BYTES = 32L * MIB
    }
}

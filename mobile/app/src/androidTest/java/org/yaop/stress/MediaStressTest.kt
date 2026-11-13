package org.yaop.stress

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.yaop.YaopApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.system.measureTimeMillis

private const val TAG = "StressTest:Media"

@RunWith(AndroidJUnit4::class)
class MediaStressTest {

    private lateinit var app: YaopApp

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        app = context.applicationContext as YaopApp
    }

    private fun getUsedMemoryMb(): Double {
        System.gc()
        System.gc()
        val runtime = Runtime.getRuntime()
        val usedBytes = runtime.totalMemory() - runtime.freeMemory()
        return usedBytes / (1024.0 * 1024.0)
    }

    @Test
    fun testStress_100_Images_Encryption_Decryption_And_Decoding() = runBlocking {
        val imageCount = 100
        Log.i(TAG, "=== Starting Stress Test: 100 Images Encrypted Storage ===")

        val memBefore = getUsedMemoryMb()
        val mediaStore = app.mediaStore

        // 1. Generate and encrypt 100 images
        val assetIds = mutableListOf<String>()
        val totalBytes = mutableListOf<Int>()

        val encryptTimeMs = measureTimeMillis {
            for (i in 1..imageCount) {
                val assetId = "stress_img_${UUID.randomUUID()}"
                val bytes = StressTestDataGenerator.generateSyntheticImageBytes(
                    width = 400,
                    height = 300,
                    label = "IMG_$i",
                )
                mediaStore.saveMedia(assetId, bytes, "image/webp")
                assetIds += assetId
                totalBytes += bytes.size
            }
        }

        val totalMb = totalBytes.sum().toDouble() / (1024.0 * 1024.0)
        Log.i(TAG, "Encrypted and wrote $imageCount WebP images (Total: %.2f MB) in %d ms (%.1f ms/image)".format(
            totalMb, encryptTimeMs, encryptTimeMs.toDouble() / imageCount
        ))

        // 2. Read and decrypt 100 images sequentially (simulating list scroll or gallery load)
        var decryptedCount = 0
        val decryptTimeMs = measureTimeMillis {
            for (assetId in assetIds) {
                val data = mediaStore.readMediaAny(assetId)
                assertNotNull("Failed to decrypt asset $assetId", data)
                decryptedCount++
            }
        }
        Log.i(TAG, "Read & Decrypted $decryptedCount images in $decryptTimeMs ms (%.1f ms/image)".format(
            decryptTimeMs.toDouble() / imageCount
        ))

        // 3. Measure Bitmap decoding & downsampling latency (what rememberDecodedThumbnail does)
        var decodedBitmaps = 0
        val decodeTimeMs = measureTimeMillis {
            for (assetId in assetIds) {
                val data = mediaStore.readMediaAny(assetId) ?: continue
                // Bounds inspection
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)

                // Downsample to max 256px
                var sample = 1
                while ((bounds.outWidth / sample) > 256 || (bounds.outHeight / sample) > 256) {
                    sample *= 2
                }
                val bmp = BitmapFactory.decodeByteArray(
                    data,
                    0,
                    data.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
                assertNotNull(bmp)
                bmp.recycle()
                decodedBitmaps++
            }
        }

        val memAfter = getUsedMemoryMb()
        Log.i(TAG, "Decoded $decodedBitmaps bitmaps into RAM in $decodeTimeMs ms (%.1f ms/thumbnail)".format(
            decodeTimeMs.toDouble() / imageCount
        ))
        Log.i(TAG, "Memory delta after 100 image decodes: %.2f MB".format(memAfter - memBefore))

        assertEquals(imageCount, decodedBitmaps)
    }

    @Test
    fun testStress_20_AudioRecordings_Storage_And_Metadata() = runBlocking {
        val audioCount = 20
        Log.i(TAG, "=== Starting Stress Test: 20 Audio Files ===")

        val mediaStore = app.mediaStore
        val repository = app.repository

        val audioAssetIds = mutableListOf<String>()
        val audioObjectIds = mutableListOf<String>()

        val totalTimeMs = measureTimeMillis {
            for (i in 1..audioCount) {
                val duration = 5 + (i * 3) // 8s to 65s
                val audioBytes = StressTestDataGenerator.generateSyntheticAudioBytes(durationSeconds = duration)
                val assetId = "stress_aud_${UUID.randomUUID()}"

                mediaStore.saveMedia(assetId, audioBytes, "audio/m4a")
                audioAssetIds += assetId

                val audioObj = StressTestDataGenerator.createAudioObject(
                    assetId = assetId,
                    title = "Meeting recording #$i",
                    durationSeconds = duration,
                    byteSize = audioBytes.size,
                )
                repository.saveObject(audioObj)
                audioObjectIds += audioObj.id
            }
        }

        Log.i(TAG, "Generated, encrypted and saved $audioCount audio objects in $totalTimeMs ms")

        // Verify retrieval
        for (objId in audioObjectIds) {
            val obj = repository.getObject(objId)
            assertNotNull("Audio object $objId not found", obj)
            assertEquals("audio", obj?.typeId)
        }
    }
}

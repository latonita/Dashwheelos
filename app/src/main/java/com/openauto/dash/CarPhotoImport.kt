package com.openauto.dash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import android.provider.OpenableColumns
import com.openauto.dash.carphoto.Argb
import com.openauto.dash.carphoto.CarPhotoKit
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * EXPERIMENTAL: the driver's car from photos picked on the unit itself (a USB
 * stick), the same way the phone app builds it: the shared car photo kit makes
 * the side picture and the view from above (or uses a fourth photo
 * taken from above, as the Gemini app draws it, and a fifth with its doors
 * open), then [MyCarLook] imports them as a car pack. Which photo is which
 * comes from their names, else their shape ([CarPhotoKit.sortShots]).
 */
internal object CarPhotoImport {
    /** Photos are decoded about this long a side; the kit works at [CarPhotoKit.MAX_SIDE]. */
    private const val DECODE_SIDE = 2000

    /** Blocking, call off the main thread. */
    fun import(context: Context, uris: List<Uri>): Result<CarLook> = runCatching {
        val photos = uris.take(5).map { decode(context, it) }
        val names = uris.take(5).map { nameOf(context, it) }
        val shots = CarPhotoKit.sortShots(names, photos.map { it.w.toFloat() / it.h })
        val result = CarPhotoKit.build(
            photos[shots.side], shots.front?.let { photos[it] }, shots.back?.let { photos[it] },
            name = CarProfileStore.current.name, above = shots.above?.let { photos[it] }, opened = shots.opened?.let { photos[it] }
        )
        val files = LinkedHashMap<String, ByteArray>()
        files["car.json"] = result.toCarJson().toByteArray()
        for ((name, picture) in result.packEntries()) files[name] = png(picture)
        MyCarLook.importBytes(context, CarPhotoKit.zip(files))
    }

    /** Upright (a camera's rotation applied) and no bigger than needed. */
    private fun decode(context: Context, uri: Uri): Argb {
        val bitmap =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) decodeWithImageDecoder(context, uri)
            else decodeWithBitmapFactory(context, uri)
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Argb(bitmap.width, bitmap.height, px).also { bitmap.recycle() }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(context: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > DECODE_SIDE) {
                val scale = DECODE_SIDE.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
        }
    }

    /**
     * API 27 and below have no ImageDecoder. BitmapFactory does the same work in
     * three steps: measure, decode down to a power of two, then the exact size —
     * and the camera's rotation, which ImageDecoder applies on its own, is read
     * from the file's Exif and applied here.
     */
    private fun decodeWithBitmapFactory(context: Context, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = max(bounds.outWidth, bounds.outHeight)
        // The biggest power of two that still leaves the photo at or above the side we want.
        var sample = 1
        while (longest > 0 && longest / (sample * 2) >= DECODE_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("cannot read $uri")
        val upright = applyExif(context, uri, decoded)
        val side = max(upright.width, upright.height)
        if (side <= DECODE_SIDE) return upright
        val scale = DECODE_SIDE.toFloat() / side
        val scaled = Bitmap.createScaledBitmap(
            upright, (upright.width * scale).toInt().coerceAtLeast(1),
            (upright.height * scale).toInt().coerceAtLeast(1), true
        )
        if (scaled !== upright) upright.recycle()
        return scaled
    }

    /** The camera's rotation, turned into the pixels. Unreadable Exif leaves the photo as it came. */
    private fun applyExif(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }

    private fun nameOf(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    private fun png(picture: Argb): ByteArray {
        val bitmap = Bitmap.createBitmap(picture.px, picture.w, picture.h, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            .also { bitmap.recycle() }
    }
}

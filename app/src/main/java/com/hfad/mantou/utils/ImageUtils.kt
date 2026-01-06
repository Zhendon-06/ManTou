package com.hfad.mantou.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.min

/**
 * 图片处理工具类
 */
object ImageUtils {

    private const val TAG = "ImageUtils"
    private const val MAX_IMAGE_SIZE = 1024 // 最大宽度/高度
    private const val JPEG_QUALITY = 85 // JPEG 压缩质量

    /**
     * 将图片 URI 转换为 Base64
     * @param context 上下文
     * @param uri 图片 URI
     * @return Base64 字符串，格式：data:image/jpeg;base64,xxx
     */
    fun uriToBase64(context: Context, uri: Uri): String? {
        return try {
            val bitmap = loadAndCompressBitmap(context, uri)
            bitmapToBase64(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "图片转 Base64 失败", e)
            null
        }
    }

    /**
     * 将多张图片合并为一张，并转换为 Base64
     * @param context 上下文
     * @param uris 图片 URI 列表
     * @return Base64 字符串
     */
    fun mergeImagesToBase64(context: Context, uris: List<Uri>): String? {
        if (uris.isEmpty()) return null
        
        // 如果只有一张图片，直接转换
        if (uris.size == 1) {
            return uriToBase64(context, uris[0])
        }

        return try {
            // 加载所有图片
            val bitmaps = uris.mapNotNull { uri ->
                try {
                    loadAndCompressBitmap(context, uri)
                } catch (e: Exception) {
                    Log.e(TAG, "加载图片失败: $uri", e)
                    null
                }
            }

            if (bitmaps.isEmpty()) return null

            // 合并图片（垂直排列）
            val mergedBitmap = mergeBitmapsVertically(bitmaps)
            
            // 转换为 Base64
            bitmapToBase64(mergedBitmap)
        } catch (e: Exception) {
            Log.e(TAG, "合并图片失败", e)
            null
        }
    }

    /**
     * 加载并压缩图片
     */
    private fun loadAndCompressBitmap(context: Context, uri: Uri): Bitmap {
        val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
        
        // 先获取图片尺寸
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeStream(inputStream, null, options)
        inputStream?.close()

        // 计算缩放比例
        val scale = calculateInSampleSize(options.outWidth, options.outHeight, MAX_IMAGE_SIZE)

        // 加载压缩后的图片
        val inputStream2: InputStream? = context.contentResolver.openInputStream(uri)
        val bitmap = BitmapFactory.decodeStream(inputStream2, null, BitmapFactory.Options().apply {
            inSampleSize = scale
        })
        inputStream2?.close()

        return bitmap ?: throw IllegalArgumentException("无法加载图片")
    }

    /**
     * 计算缩放比例
     */
    private fun calculateInSampleSize(width: Int, height: Int, maxSize: Int): Int {
        var inSampleSize = 1
        if (width > maxSize || height > maxSize) {
            val halfWidth = width / 2
            val halfHeight = height / 2
            while (halfWidth / inSampleSize >= maxSize && halfHeight / inSampleSize >= maxSize) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * 垂直合并多张图片
     */
    private fun mergeBitmapsVertically(bitmaps: List<Bitmap>): Bitmap {
        if (bitmaps.isEmpty()) throw IllegalArgumentException("图片列表为空")
        if (bitmaps.size == 1) return bitmaps[0]

        // 计算合并后的尺寸
        val maxWidth = bitmaps.maxOf { it.width }
        val totalHeight = bitmaps.sumOf { it.height }

        // 限制最大高度
        val scale = if (totalHeight > MAX_IMAGE_SIZE * 2) {
            MAX_IMAGE_SIZE * 2f / totalHeight
        } else {
            1f
        }

        val finalWidth = (maxWidth * scale).toInt()
        val finalHeight = (totalHeight * scale).toInt()

        // 创建合并后的 Bitmap
        val mergedBitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(mergedBitmap)

        // 绘制每张图片
        var currentY = 0f
        bitmaps.forEach { bitmap ->
            val scaledWidth = (bitmap.width * scale).toInt()
            val scaledHeight = (bitmap.height * scale).toInt()
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
            canvas.drawBitmap(scaledBitmap, 0f, currentY, null)
            currentY += scaledHeight
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
        }

        return mergedBitmap
    }

    /**
     * Bitmap 转 Base64
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        val base64String = Base64.encodeToString(byteArray, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64String"
    }

    /**
     * 保存图片到本地（用于调试）
     */
    fun saveBitmapToFile(context: Context, bitmap: Bitmap, fileName: String): String? {
        return try {
            val file = java.io.File(context.cacheDir, fileName)
            val outputStream = java.io.FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            outputStream.flush()
            outputStream.close()
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "保存图片失败", e)
            null
        }
    }
}


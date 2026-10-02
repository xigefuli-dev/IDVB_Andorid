package com.idvb.android.alignment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor

/** A user-recorded game UI patch, independent of map identity and alignment transforms. */
data class AutoMapOpenReference(
    val id: String,
    val landscape: Boolean,
    val screenWidth: Int,
    val screenHeight: Int,
    val region: FloatArray,
    val signaturePixels: IntArray,
    val targetPackage: String,
    val createdAtMillis: Long,
    val pngSha256: String,
    val sidebarAspectRatio: Double? = null,
)

/** Private, replayable references. Invalid or incomplete files never enable the detector. */
class AutoMapOpenReferenceStore(private val context: Context) {
    private val directory = File(context.filesDir, "idvb/auto-map-open")
    private val ownPackage = context.packageName

    fun load(landscape: Boolean): AutoMapOpenReference? = synchronized(lock) {
        val metadata = file(landscape, "json")
        if (!metadata.exists()) return@synchronized null
        runCatching {
            require(metadata.length() in 1..MAX_JSON_BYTES)
            val json = JSONObject(AtomicFile(metadata).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            require(json.getInt("schemaVersion") == SCHEMA_VERSION)
            require(json.getString("featureFormat") == FEATURE_FORMAT)
            require(json.getInt("sampleWidth") == SAMPLE_WIDTH && json.getInt("sampleHeight") == SAMPLE_HEIGHT)
            val id = json.getString("id")
            require(runCatching { UUID.fromString(id) }.isSuccess)
            val screenWidth = json.getInt("screenWidth")
            val screenHeight = json.getInt("screenHeight")
            validateScreen(screenWidth, screenHeight)
            require(json.getBoolean("landscape") == landscape && (screenWidth > screenHeight) == landscape)
            val regionJson = json.getJSONArray("region")
            require(regionJson.length() == 4)
            val region = FloatArray(4) { regionJson.getDouble(it).toFloat() }
            val bounds = cropBounds(region, screenWidth, screenHeight)
            val targetPackage = json.getString("targetPackage")
            validatePackage(targetPackage)
            val createdAtMillis = json.getLong("createdAtMillis")
            require(createdAtMillis > 0)
            val pixelJson = json.getJSONArray("signaturePixels")
            require(pixelJson.length() == SAMPLE_WIDTH * SAMPLE_HEIGHT)
            val pixels = IntArray(pixelJson.length()) {
                val value = pixelJson.getLong(it)
                require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                value.toInt()
            }
            require(pixels.all { (it ushr 24) == 255 })
            require(AutoMapOpenDetector.isUsableReference(AutoMapOpenDetector.signature(pixels)))
            val expectedHash = json.getString("pngSha256")
            require(Regex("[0-9a-f]{64}").matches(expectedHash))
            val imageFile = file(landscape, "png")
            require(imageFile.length() in 1..MAX_PNG_BYTES)
            val png = AtomicFile(imageFile).openRead().use { it.readBytes() }
            require(sha256(png) == expectedHash)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(png, 0, png.size, options)
            require(options.outWidth == bounds[2] - bounds[0] && options.outHeight == bounds[3] - bounds[1])
            val patch = requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
            try {
                require(samplePixels(patch).contentEquals(pixels))
            } finally { patch.recycle() }
            AutoMapOpenReference(id, landscape, screenWidth, screenHeight, region, pixels,
                targetPackage, createdAtMillis, expectedHash)
        }.onFailure { Log.w("IDVB-AutoMap", "开图参照无效，自动检测保持停止：${it.message}") }.getOrNull()
    }

    /**
     * 加载内置侧边栏开图参照。
     * 用户无需手动截取参照，使用内置的标准侧边栏图像（位于 assets/recognition/map_open_sidebar.png），
     * 识别区域从用户已校准区域的最右侧边延伸至屏幕本身的最右侧边（高度为屏幕横放高度）。
     */
    fun loadBuiltin(screenWidth: Int, screenHeight: Int, calibratedRightRatio: Float, targetPackage: String? = null): AutoMapOpenReference? = synchronized(lock) {
        if (screenWidth <= screenHeight || screenHeight <= 0 || !calibratedRightRatio.isFinite() ||
            calibratedRightRatio <= 0f || calibratedRightRatio >= 1f || targetPackage.isNullOrBlank()) return@synchronized null
        val pixels = getOrLoadBuiltinSignature() ?: return@synchronized null
        val hash = getOrLoadBuiltinHash()
        val region = floatArrayOf(calibratedRightRatio, 0f, 1f, 1f)
        AutoMapOpenReference(
            id = "builtin-sidebar",
            landscape = true,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            region = region,
            signaturePixels = pixels,
            targetPackage = targetPackage ?: "",
            createdAtMillis = 0L,
            pngSha256 = hash,
            sidebarAspectRatio = cachedBuiltinAspectRatio,
        )
    }

    private var cachedBuiltinSignature: IntArray? = null
    private var cachedBuiltinHash: String = ""
    private var cachedBuiltinAspectRatio: Double? = null

    private fun getOrLoadBuiltinSignature(): IntArray? {
        cachedBuiltinSignature?.let { return it }
        return runCatching {
            val bytes = context.assets.open(BUILTIN_SIDEBAR_ASSET).use { it.readBytes() }
            cachedBuiltinHash = sha256(bytes)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            try {
                val pixels = samplePixels(bitmap)
                require(AutoMapOpenDetector.isUsableReference(AutoMapOpenDetector.signature(pixels))) {
                    "内置侧边栏参照缺少可辨识细节"
                }
                cachedBuiltinAspectRatio = bitmap.width.toDouble() / bitmap.height
                cachedBuiltinSignature = pixels
                pixels
            } finally {
                bitmap.recycle()
            }
        }.onFailure { Log.w("IDVB-AutoMap", "无法加载内置开图参照：${it.message}") }.getOrNull()
    }

    private fun getOrLoadBuiltinHash(): String {
        if (cachedBuiltinHash.isEmpty()) {
            getOrLoadBuiltinSignature()
        }
        return cachedBuiltinHash
    }

    /** Borrows the full captured frame; only the selected native patch is persisted. */
    fun save(bitmap: Bitmap, region: FloatArray, targetPackage: String): AutoMapOpenReference = synchronized(lock) {
        require(!bitmap.isRecycled) { "参照截图已释放" }
        validateScreen(bitmap.width, bitmap.height)
        validatePackage(targetPackage)
        val normalized = region.copyOf()
        val bounds = cropBounds(normalized, bitmap.width, bitmap.height)
        val patch = Bitmap.createBitmap(bitmap, bounds[0], bounds[1], bounds[2] - bounds[0], bounds[3] - bounds[1])
        try {
            val pixels = samplePixels(patch)
            require(pixels.all { (it ushr 24) == 255 }) { "参照截图不完整，请重新捕获游戏画面" }
            require(AutoMapOpenDetector.isUsableReference(AutoMapOpenDetector.signature(pixels))) {
                "参照区域缺少可辨识的固定界面，请重新框选含图标或边框的区域"
            }
            val png = ByteArrayOutputStream().use { output ->
                check(patch.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法保存参照区域" }
                output.toByteArray()
            }
            require(png.size.toLong() <= MAX_PNG_BYTES) { "参照区域过大，请缩小框选区域" }
            val reference = AutoMapOpenReference(UUID.randomUUID().toString(), bitmap.width > bitmap.height,
                bitmap.width, bitmap.height, normalized, pixels, targetPackage, System.currentTimeMillis(), sha256(png))
            val json = JSONObject().apply {
                put("schemaVersion", SCHEMA_VERSION)
                put("featureFormat", FEATURE_FORMAT)
                put("sampleWidth", SAMPLE_WIDTH); put("sampleHeight", SAMPLE_HEIGHT)
                put("id", reference.id); put("landscape", reference.landscape)
                put("screenWidth", reference.screenWidth); put("screenHeight", reference.screenHeight)
                put("region", JSONArray().apply { normalized.forEach { put(it.toDouble()) } })
                put("signaturePixels", JSONArray().apply { pixels.forEach { put(it) } })
                put("targetPackage", reference.targetPackage); put("createdAtMillis", reference.createdAtMillis)
                put("pngSha256", reference.pngSha256)
            }.toString(2).toByteArray(Charsets.UTF_8)
            check(directory.isDirectory || directory.mkdirs()) { "无法创建私有参照目录" }
            // Publish metadata last. A crash between the two writes fails the hash check on load.
            atomicWrite(file(reference.landscape, "png"), png)
            atomicWrite(file(reference.landscape, "json"), json)
            reference
        } finally {
            if (patch !== bitmap) patch.recycle()
        }
    }

    fun clear(landscape: Boolean) = synchronized(lock) {
        AtomicFile(file(landscape, "json")).delete()
        AtomicFile(file(landscape, "png")).delete()
    }

    private fun file(landscape: Boolean, extension: String) = File(directory,
        "reference-${if (landscape) "landscape" else "portrait"}.$extension")

    private fun validatePackage(packageName: String) {
        require(packageName.length in 3..256 && Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(packageName))
        require(packageName != ownPackage && packageName != "android" && !packageName.startsWith("com.android.")) {
            "请返回游戏后设置开图参照"
        }
    }

    private fun atomicWrite(destination: File, bytes: ByteArray) {
        val file = AtomicFile(destination)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    }

    companion object {
        const val BUILTIN_SIDEBAR_ASSET = "recognition/map_open_sidebar.png"
        const val SAMPLE_WIDTH = 32
        const val SAMPLE_HEIGHT = 24
        private const val SCHEMA_VERSION = 1
        private const val FEATURE_FORMAT = "argb32x24-spatial-v1"
        private const val MAX_JSON_BYTES = 128L * 1024L
        private const val MAX_PNG_BYTES = 8L * 1024L * 1024L
        private const val MAX_PATCH_PIXELS = 4_194_304L
        private val lock = Any()

        private fun validateScreen(width: Int, height: Int) {
            require(width in 32..16_384 && height in 32..16_384 && width.toLong() * height <= 40_000_000L)
        }

        private fun cropBounds(region: FloatArray, width: Int, height: Int): IntArray {
            require(region.size == 4 && region.all { it.isFinite() && it in 0f..1f }) { "参照区域无效" }
            require(region[2] > region[0] && region[3] > region[1]) { "参照区域为空" }
            val left = floor(region[0].toDouble() * width).toInt().coerceIn(0, width - 1)
            val top = floor(region[1].toDouble() * height).toInt().coerceIn(0, height - 1)
            val right = ceil(region[2].toDouble() * width).toInt().coerceIn(left + 1, width)
            val bottom = ceil(region[3].toDouble() * height).toInt().coerceIn(top + 1, height)
            require(right - left >= 16 && bottom - top >= 16 &&
                (right - left).toLong() * (bottom - top) <= MAX_PATCH_PIXELS) { "请框选至少 16 像素、包含固定界面的适当大小区域" }
            return intArrayOf(left, top, right, bottom)
        }

        /** The same sample format used by the detector, retained verbatim for replay. */
        fun samplePixels(patch: Bitmap): IntArray {
            val reduced = Bitmap.createScaledBitmap(patch, SAMPLE_WIDTH, SAMPLE_HEIGHT, true)
            return try {
                IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT).also {
                    reduced.getPixels(it, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT)
                }
            } finally { if (reduced !== patch) reduced.recycle() }
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

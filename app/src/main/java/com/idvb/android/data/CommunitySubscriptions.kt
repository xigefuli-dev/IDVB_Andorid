package com.idvb.android.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import android.util.LruCache
import com.idvb.android.AppServices
import com.idvb.android.idvm.IdvmImporter
import com.idvb.android.idvm.ImportResult
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val COMMUNITY = "https://community.idvb.xgflee.com"
private const val DOWNLOAD = "https://download.xgflee.com"
private const val MAX_PACKAGE = 256L * 1024 * 1024
private const val OFFICIAL_KEY = "54CC7C58D919EFD0390BCE8D8CFC6586997F17D6134899B1DB17FA129B5FE6AD"
private val publicationPath = Regex("/api/maps/subscriptions/([0-9a-fA-F-]{36})/feed\\.json")

data class CommunityEntry(
    val id: String,
    val name: String,
    val publisher: String,
    val version: String,
    val link: String?,
    val downloadUrl: String?,
    val size: Long,
    val coverUrl: String? = null,
    val updatedAt: String = "",
)

data class SavedSubscription(
    val id: String,
    val name: String,
    val publisher: String,
    val version: String,
    val link: String,
    val publishedAt: String,
    val plaintextHash: String,
    val mapIds: List<String>,
)

data class CommunityDownloadProgress(val phase: String, val fraction: Float? = null)

/** Official community protocol. Links contain a secret content key and stay in private app storage. */
class CommunitySubscriptions(private val context: Context) {
    private val storeFile = File(context.filesDir, "idvb/subscriptions.json")
    private val coverCache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun saved(): List<SavedSubscription> = runCatching {
        if (!storeFile.isFile) return emptyList()
        val root = JSONObject(AtomicFile(storeFile).openRead().bufferedReader().use { it.readText() })
        val items = root.getJSONArray("subscriptions")
        (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val ids = item.getJSONArray("mapIds")
            SavedSubscription(
                item.getString("id"), item.getString("name"), item.getString("publisher"),
                item.getString("version"), item.getString("link"), item.getString("publishedAt"),
                item.getString("plaintextHash"), (0 until ids.length()).map(ids::getString),
            )
        }
    }.getOrDefault(emptyList())

    private fun save(items: List<SavedSubscription>) {
        storeFile.parentFile?.mkdirs()
        val array = org.json.JSONArray()
        items.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("name", item.name)
                .put("publisher", item.publisher).put("version", item.version)
                .put("link", item.link).put("publishedAt", item.publishedAt)
                .put("plaintextHash", item.plaintextHash)
                .put("mapIds", org.json.JSONArray(item.mapIds)))
        }
        val atomic = AtomicFile(storeFile)
        val stream = atomic.startWrite()
        try {
            stream.write(JSONObject().put("subscriptions", array).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    fun unsubscribe(id: String) = save(saved().filterNot { it.id == id })

    fun catalog(): List<CommunityEntry> {
        val body = JSONObject(String(fetch("$COMMUNITY/api/maps", 2L * 1024 * 1024), Charsets.UTF_8))
        val result = mutableListOf<CommunityEntry>()
        val publications = body.optJSONArray("publications") ?: org.json.JSONArray()
        require(publications.length() <= 500) { "社区订阅列表过大" }
        for (i in 0 until publications.length()) {
            val item = publications.getJSONObject(i)
            val link = parseLink(item.getString("subscriptionLink"))
            require(item.getString("id").equals(link.id, true)) { "目录发布 ID 不一致" }
            val coverUrl = item.optString("coverUrl").takeIf { it.isNotBlank() }?.let {
                val url = checkedUrl(it, COMMUNITY, "/api/maps/covers/")
                require(url.path == "/api/maps/covers/${link.id}") { "封面地址无效" }
                url.toString()
            }
            result += CommunityEntry(link.id, item.getString("name"), item.getString("publisherName"),
                item.getString("version"), item.getString("subscriptionLink"), null, 0, coverUrl,
                item.getString("publishedAt"))
        }
        val maps = body.getJSONArray("maps")
        require(maps.length() <= 4096) { "社区地图列表过大" }
        for (i in 0 until maps.length()) {
            val item = maps.getJSONObject(i)
            val url = checkedUrl(item.getString("downloadUrl"), DOWNLOAD, "/maps/")
            require(url.path.endsWith(".idvm", true) && !url.path.contains("..")) { "普通地图地址无效" }
            val size = item.getLong("size")
            require(size in 1..MAX_PACKAGE) { "地图包大小无效" }
            result += CommunityEntry("package:${url}", item.getString("name"), "社区文件",
                item.optString("uploaded"), null, url.toString(), size)
        }
        require(result.map { it.id }.distinct().size == result.size) { "社区目录存在重复 ID" }
        return result
    }

    fun cover(urlText: String): Bitmap? {
        val url = checkedUrl(urlText, COMMUNITY, "/api/maps/covers/")
        require(Regex("/api/maps/covers/[0-9a-fA-F-]{36}").matches(url.path)) { "封面地址无效" }
        coverCache.get(url.toString())?.let { return it }
        val bytes = fetch(url.toString(), 4L * 1024 * 1024)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192) { "封面图片无效" }
        val sample = generateSequence(1) { it * 2 }.first { bounds.outWidth / it <= 720 && bounds.outHeight / it <= 720 }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.also { coverCache.put(url.toString(), it) }
    }

    fun install(entry: CommunityEntry): String {
        if (entry.link != null) return subscribe(entry.link)
        val url = checkedUrl(requireNotNull(entry.downloadUrl), DOWNLOAD, "/maps/")
        val temp = File(context.cacheDir, "community-${UUID.randomUUID()}.idvm")
        try {
            fetchFile(url.toString(), temp, MAX_PACKAGE, entry.size)
            return when (val result = IdvmImporter().importPackage(temp, AppServices.repository.mapsRoot,
                AppServices.repository.loadCatalog(), AppServices.repository::saveCatalog)) {
                is ImportResult.Success -> "已导入 ${result.importedMaps.size} 张地图"
                is ImportResult.Failure -> error(result.reason)
            }
        } finally { temp.delete() }
    }

    fun subscribe(rawLink: String, onProgress: (CommunityDownloadProgress) -> Unit = {}): String {
        onProgress(CommunityDownloadProgress("读取订阅信息"))
        val link = parseLink(rawLink)
        val previous = saved().firstOrNull { it.id == link.id }
        require(previous == null || parseLink(previous.link).publisherKeyId == link.publisherKeyId) { "发布者密钥发生变化" }
        val envelope = JSONObject(String(fetch(link.feedUrl, 2L * 1024 * 1024), Charsets.UTF_8))
        require(envelope.getInt("schemaVersion") == 1) { "订阅 feed 版本无效" }
        val payloadBytes = Base64.getDecoder().decode(envelope.getString("payload"))
        require(payloadBytes.size <= 2 * 1024 * 1024) { "签名内容过大" }
        val signatureBytes = Base64.getDecoder().decode(envelope.getString("signature"))
        require(signatureBytes.size == 64) { "签名长度无效" }
        val pem = envelope.getString("publisherPublicKeyPem")
        require(pem.length <= 4096 && pem.startsWith("-----BEGIN PUBLIC KEY-----")) { "公钥格式无效" }
        val spki = Base64.getDecoder().decode(pem.replace(Regex("-----BEGIN PUBLIC KEY-----|-----END PUBLIC KEY-----|\\s"), ""))
        require(hash(spki) == link.publisherKeyId) { "发布者公钥与订阅链接不一致" }
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update(payloadBytes)
        require(verifier.verify(p1363ToDer(signatureBytes))) { "订阅签名无效" }
        val payload = JSONObject(String(payloadBytes, Charsets.UTF_8))
        onProgress(CommunityDownloadProgress("校验发布签名"))
        require(payload.getInt("schemaVersion") == 1 && payload.getString("publicationId").equals(link.id, true)
            && payload.getString("publisherKeyId").equals(link.publisherKeyId, true)) { "签名发布身份不一致" }
        val handle = payload.getString("publisherHandle")
        require(handle.matches(Regex("@[\\p{L}\\p{N}_.-]{1,64}"))) { "发布者账号无效" }
        require(!handle.equals("@xigefuli", true) || link.publisherKeyId == OFFICIAL_KEY) { "官方发布密钥不匹配" }
        val publishedAt = payload.getString("publishedAtUtc")
        val published = Instant.parse(publishedAt)
        if (previous != null) require(!published.isBefore(Instant.parse(previous.publishedAt))) { "订阅版本回退" }
        val plainLength = payload.getLong("plaintextLength")
        val encryptedLength = payload.getLong("encryptedLength")
        require(plainLength in 1..MAX_PACKAGE && encryptedLength == plainLength + 92) { "加密包长度无效" }
        val plainHash = validHash(payload.getString("plaintextSha256"))
        val encryptedHash = validHash(payload.getString("encryptedSha256"))
        val name = payload.optString("packageName").ifBlank { "社区地图包" }.take(120)
        val publisher = payload.optString("publisherDisplayName").ifBlank { handle }.take(120)
        val record = SavedSubscription(link.id, name, publisher, payload.getString("version").take(128),
            link.canonical, publishedAt, plainHash, previous?.mapIds.orEmpty())
        if (previous?.plaintextHash == plainHash && previous.mapIds.isNotEmpty() &&
            previous.mapIds.all { id -> AppServices.repository.loadCatalog().maps.any { it.id == id } }) {
            save(saved().filterNot { it.id == link.id } + record)
            return "已是最新版本"
        }
        val packageUrl = checkedUrl(URL(URL(link.feedUrl), payload.getString("packageUri")).toString(),
            COMMUNITY, "/api/maps/subscriptions/${link.id}/")
        require(packageUrl.path.endsWith(".idvm.secure")) { "加密包地址无效" }
        val encryptedFile = File(context.cacheDir, "community-${UUID.randomUUID()}.idvm.secure")
        val temp = File(context.cacheDir, "community-${UUID.randomUUID()}.idvm")
        try {
            onProgress(CommunityDownloadProgress("下载地图包", 0f))
            require(fetchFile(packageUrl.toString(), encryptedFile, MAX_PACKAGE + 92, encryptedLength) { received, total ->
                onProgress(CommunityDownloadProgress("下载地图包", .85f * received.toFloat() / total))
            } == encryptedHash) { "加密包摘要不一致" }
            onProgress(CommunityDownloadProgress("解密地图包", .88f))
            decryptToFile(encryptedFile, temp, link, plainLength, plainHash)
            onProgress(CommunityDownloadProgress("安装地图", .95f))
            val repo = AppServices.repository
            val oldIds = previous?.mapIds.orEmpty().toSet()
            val result = IdvmImporter().importPackage(temp, repo.mapsRoot, repo.loadCatalog()) { imported ->
                val retained = imported.maps.filterNot { it.id in oldIds }
                repo.saveCatalog(imported.copy(
                    maps = retained,
                    classes = imported.classes.filter { cls -> retained.any { it.classId == cls.id } },
                    variantGroups = imported.variantGroups.filterNot { group -> group.mapIds.any(oldIds::contains) },
                ))
            }
            if (result is ImportResult.Failure) error(result.reason)
            result as ImportResult.Success
            val installed = record.copy(mapIds = result.importedMaps.map { it.id })
            save(saved().filterNot { it.id == link.id } + installed)
            val selectedId = AppServices.prefs.lastMapId
            if (selectedId in oldIds) {
                val oldMap = result.catalog.maps.firstOrNull { it.id == selectedId }
                val replacement = result.importedMaps.firstOrNull { it.sourceMapId == oldMap?.sourceMapId }
                    ?: result.importedMaps.firstOrNull()
                AppServices.prefs.lastMapId = replacement?.id
                if (replacement?.floors?.none { it.key == AppServices.prefs.lastFloorKey } != false)
                    AppServices.prefs.lastFloorKey = replacement?.floors?.firstOrNull()?.key
            }
            oldIds.forEach { File(repo.mapsRoot, it).deleteRecursively() }
            return "已订阅 ${result.importedMaps.size} 张地图"
        } finally { temp.delete(); encryptedFile.delete() }
    }

    private data class Link(val id: String, val feedUrl: String, val contentKey: ByteArray,
        val publisherKeyId: String, val canonical: String)

    private fun parseLink(text: String): Link {
        require(text.length <= 8192) { "订阅链接过长" }
        val uri = Uri.parse(text.trim())
        val direct = uri.scheme == "https"
        val feed = if (direct) uri.buildUpon().fragment(null).build().toString() else {
            require(uri.scheme == "idvb-sub" && uri.host == "v1") { "订阅链接格式无效" }
            require(uri.getQueryParameters("feed").size == 1) { "feed 参数无效" }
            uri.getQueryParameter("feed") ?: error("缺少 feed")
        }
        val params = if (direct) Uri.parse("https://local.invalid/?${uri.fragment.orEmpty()}") else uri
        val keyField = if (direct) "idvb-key" else "key"
        val pubField = if (direct) "idvb-publisher" else "publisher"
        require(params.getQueryParameters(keyField).size == 1 && params.getQueryParameters(pubField).size == 1) { "订阅参数无效" }
        val url = checkedUrl(feed, COMMUNITY, "/api/maps/subscriptions/")
        val id = publicationPath.matchEntire(url.path)?.groupValues?.get(1)?.lowercase() ?: error("feed 路径无效")
        require(runCatching { UUID.fromString(id) }.isSuccess && id != "00000000-0000-0000-0000-000000000000") { "发布 ID 无效" }
        val contentKey = Base64.getUrlDecoder().decode(params.getQueryParameter(keyField))
        require(contentKey.size == 32) { "内容密钥无效" }
        val publisherKeyId = validHash(params.getQueryParameter(pubField) ?: "")
        val canonical = "idvb-sub://v1?feed=${Uri.encode(url.toString())}&key=${Base64.getUrlEncoder().withoutPadding().encodeToString(contentKey)}&publisher=$publisherKeyId"
        return Link(id, url.toString(), contentKey, publisherKeyId, canonical)
    }

    private fun decryptToFile(encrypted: File, output: File, link: Link, plainLength: Long, plainHash: String) {
        require(encrypted.length() == plainLength + 92) { "加密容器长度无效" }
        val header = ByteArray(92)
        encrypted.inputStream().use { input -> require(input.read(header) == header.size) { "加密容器头不完整" } }
        require(header.copyOfRange(0, 8).contentEquals("IDVME2\u0000\u0000".toByteArray())) { "加密容器头无效" }
        val guid = UUID.fromString(link.id)
        val guidBytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt((guid.mostSignificantBits ushr 32).toInt()).putShort((guid.mostSignificantBits ushr 16).toShort())
            .putShort(guid.mostSignificantBits.toShort()).order(ByteOrder.BIG_ENDIAN).putLong(guid.leastSignificantBits).array()
        require(header.copyOfRange(8, 24).contentEquals(guidBytes)) { "加密容器发布 ID 不一致" }
        require(ByteBuffer.wrap(header, 52, 8).order(ByteOrder.LITTLE_ENDIAN).long == plainLength) { "容器明文长度无效" }
        require(header.copyOfRange(60, 92).toHex() == plainHash) { "容器明文摘要无效" }
        val nonce = header.copyOfRange(24, 36)
        val key = SecretKeySpec(link.contentKey, "AES")
        val tagVerifier = GcmTagVerifier(link.contentKey, nonce,
            "IDVB-IDVM-SECURE-2\n${link.id}\n$plainHash".toByteArray(Charsets.UTF_8))
        // Android's GCM decryptor retains plaintext until the tag is checked. Large maps
        // exceed the app heap, so decrypt to a temporary file with GCM's CTR counter.
        val ctr = Cipher.getInstance("AES/CTR/NoPadding")
        ctr.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(nonce + byteArrayOf(0, 0, 0, 2)))
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        encrypted.inputStream().use { input ->
            require(input.skip(92) == 92L) { "无法读取加密包内容" }
            output.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    tagVerifier.update(buffer, count)
                    val part = ctr.update(buffer, 0, count)
                    if (part != null) { out.write(part); digest.update(part); written += part.size }
                }
                val last = ctr.doFinal()
                out.write(last)
                digest.update(last)
                written += last.size
            }
        }
        require(written == plainLength && digest.digest().toHex() == plainHash) { "解密后地图包摘要无效" }
        require(tagVerifier.verify(header.copyOfRange(36, 52))) { "地图包 GCM 标签无效" }
    }

    private fun checkedUrl(value: String, origin: String, prefix: String): URL {
        val url = URL(URL(origin), value)
        val expected = URL(origin)
        require(url.protocol == "https" && url.host.equals(expected.host, true) && url.port == -1 &&
            url.userInfo == null && url.query == null && url.ref == null && url.path.startsWith(prefix) &&
            !url.path.contains("..")) { "只允许官方 HTTPS 地图资源" }
        return url
    }

    private fun fetch(value: String, max: Long, expected: Long? = null): ByteArray {
        val connection = URL(value).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15000
        connection.readTimeout = 60000
        connection.setRequestProperty("Accept", "application/json, application/octet-stream")
        try {
            require(connection.responseCode == 200) { "社区返回 HTTP ${connection.responseCode}" }
            require(connection.contentLengthLong < 0 || connection.contentLengthLong <= max) { "文件超过大小上限" }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= max && (expected == null || total <= expected)) { "下载大小无效" }
                    output.write(buffer, 0, count)
                }
                require(expected == null || total == expected) { "下载文件不完整" }
            }
            return output.toByteArray()
        } finally { connection.disconnect() }
    }

    private fun fetchFile(
        value: String,
        file: File,
        max: Long,
        expected: Long,
        onBytes: (Long, Long) -> Unit = { _, _ -> },
    ): String {
        val connection = URL(value).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15000
        connection.readTimeout = 60000
        try {
            require(connection.responseCode == 200) { "社区返回 HTTP ${connection.responseCode}" }
            require(connection.contentLengthLong < 0 || connection.contentLengthLong == expected) { "登记的文件长度不一致" }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input -> file.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= max && total <= expected) { "下载大小无效" }
                    digest.update(buffer, 0, count)
                    out.write(buffer, 0, count)
                    onBytes(total, expected)
                }
            } }
            require(total == expected) { "下载文件不完整" }
            return digest.digest().toHex()
        } finally { connection.disconnect() }
    }

    private fun validHash(value: String): String {
        require(value.matches(Regex("[0-9a-fA-F]{64}"))) { "SHA-256 格式无效" }
        return value.uppercase()
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    private fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0').uppercase() }

    private fun p1363ToDer(raw: ByteArray): ByteArray {
        fun integer(part: ByteArray): ByteArray {
            val nonZero = part.dropWhile { it == 0.toByte() }.toByteArray()
            val stripped = if (nonZero.isEmpty()) byteArrayOf(0) else nonZero
            val value = if (stripped[0].toInt() < 0) byteArrayOf(0) + stripped else stripped
            return byteArrayOf(2, value.size.toByte()) + value
        }
        val body = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}

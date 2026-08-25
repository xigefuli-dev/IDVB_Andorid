package com.idvb.android.idvm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IdvmImporterTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private class ImportOutcome(val result: ImportResult, val mapsRoot: File)

    /** 唯一目录名，避免同一测试多次导入时 TemporaryFolder.newFolder 冲突 */
    private fun writePackage(pkg: BuiltPackage): File {
        val dir = File(tmp.root, "pkg-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        return File(dir, "test.idvm").apply { writeBytes(pkg.zipBytes) }
    }

    private fun doImport(
        pkg: BuiltPackage,
        catalog: MapCatalogDocument = MapCatalogDocument(),
    ): ImportOutcome {
        val packageFile = writePackage(pkg)
        val mapsRoot = tmp.newFolder("maps-${java.util.UUID.randomUUID()}")
        val result = IdvmImporter().importPackage(packageFile, mapsRoot, catalog) {}
        return ImportOutcome(result, mapsRoot)
    }

    private fun importInto(
        pkg: BuiltPackage,
        mapsRoot: File,
        catalog: MapCatalogDocument,
    ): ImportResult {
        val packageFile = writePackage(pkg)
        return IdvmImporter().importPackage(packageFile, mapsRoot, catalog) {}
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun requireSuccess(result: ImportResult): ImportResult.Success = when (result) {
        is ImportResult.Success -> result
        is ImportResult.Failure -> throw AssertionError("应成功但失败：${result.reason}（${result.details}）")
    }

    private fun requireFailure(result: ImportResult): String = when (result) {
        is ImportResult.Failure -> result.reason
        is ImportResult.Success -> throw AssertionError("应失败但成功")
    }

    @Test
    fun `合法包导入成功并落盘`() {
        val pkg = TestIdvmPackage.build(
            floors = listOf(
                FloorImage("1f", "1F", 1, TestIdvmPackage.PNG_1X1),
                FloorImage("2f", "2F", 2, TestIdvmPackage.PNG_1X1),
            ),
        )
        val outcome = doImport(pkg)
        val success = requireSuccess(outcome.result)

        assertEquals(1, success.importedClasses.size)
        assertEquals("S1", success.importedClasses[0].name)
        assertEquals(1, success.importedMaps.size)
        val map = success.importedMaps[0]
        assertEquals("军工厂", map.title)
        assertEquals(2, map.floors.size)
        assertEquals(listOf("1f", "2f"), map.floors.map { it.key })

        // 落盘检查：图片文件存在且字节一致
        for (floor in map.floors) {
            val img = File(outcome.mapsRoot, floor.imagePath)
            assertTrue("楼层图应存在：${img.path}", img.exists())
            assertTrue(img.readBytes().contentEquals(TestIdvmPackage.PNG_1X1))
        }
        // 数据 JSON 落盘
        val dataDir = File(outcome.mapsRoot, "${map.id}/data")
        assertTrue(File(dataDir, "metadata.json").exists())
        assertTrue(File(dataDir, "gates.json").exists())
        assertTrue(File(dataDir, "anchors.json").exists())
    }

    @Test
    fun `兼容 Desktop 当前 IDVM 1_2 包`() {
        val success = requireSuccess(doImport(TestIdvmPackage.build(formatMinor = 2)).result)
        assertEquals(1, success.importedMaps.size)
        assertEquals("军工厂", success.importedMaps.single().title)
    }

    @Test
    fun `导入 Desktop 权威识别图和侧门特征`() {
        val pkg = TestIdvmPackage.build(
            formatMinor = 2,
            floors = listOf(
                FloorImage(
                    key = "1f",
                    displayName = "1F",
                    sortOrder = 1,
                    bytes = TestIdvmPackage.PNG_1X1,
                    recognitionImageBytes = TestIdvmPackage.PNG_1X1,
                    sideEntranceFeatureBytes = TestIdvmPackage.PNG_1X1,
                    sideEntranceFeatureCenterX = 0.5,
                    sideEntranceFeatureCenterY = 0.5,
                    sideEntranceFeatureRadius = 1,
                )
            ),
        )
        val outcome = doImport(pkg)
        val floor = requireSuccess(outcome.result).importedMaps.single().floors.single()

        assertEquals(1, floor.recognitionWidth)
        assertEquals(1, floor.recognitionHeight)
        assertEquals(NormalizedRect(0.0, 0.0, 1.0, 1.0), floor.validMapBounds)
        assertTrue(File(outcome.mapsRoot, requireNotNull(floor.recognitionImagePath)).isFile)
        val feature = requireNotNull(floor.sideEntranceFeature)
        assertEquals(0.5, feature.centerX, 0.0)
        assertEquals(0.5, feature.centerY, 0.0)
        assertEquals(1, feature.radius)
        assertEquals(1, feature.imageWidth)
        assertEquals(1, feature.imageHeight)
        assertTrue(File(outcome.mapsRoot, feature.imagePath).isFile)
    }

    @Test
    fun `metadata 引用未声明识别资产时拒绝导入`() {
        val pkg = TestIdvmPackage.build(
            formatMinor = 2,
            mutateMetadata = { metadata ->
                metadata.copy(floors = metadata.floors.map { floor ->
                    floor.copy(
                        recognitionImage = "maps/61ac15d778e94454a659aeeed373e902/data/missing-recognition.png"
                    )
                })
            },
        )

        val reason = requireFailure(doImport(pkg).result)
        assertTrue(reason.contains("未在 IDVM 文件清单中声明"))
    }

    @Test
    fun `重复导入生成新副本且 Class 名冲突时用原名-新添加N`() {
        val pkg = TestIdvmPackage.build(className = "S1")
        val first = doImport(pkg)
        val s1 = requireSuccess(first.result)

        val second = importInto(pkg, first.mapsRoot, s1.catalog)
        val s2 = requireSuccess(second)
        assertEquals("S1 - 新添加1", s2.importedClasses[0].name)
        assertEquals("S1", s2.catalog.classes[0].name)
        assertTrue(s2.importedMaps[0].id != s2.catalog.maps[0].id)
        assertEquals(2, s2.catalog.maps.size)
    }

    @Test
    fun `manifest 与 header 摘要不一致被拒绝`() {
        val reason = requireFailure(doImport(TestIdvmPackage.build(corruptHeaderSha = true)).result)
        assertTrue(reason.contains("SHA-256"))
    }

    @Test
    fun `魔数被篡改被拒绝`() {
        // fixture build() 内部会重新解析 header，篡改 magic 会让 build 自身抛异常；
        // 因此这里构造合法包，再直接修改 zip 字节中的 magic
        val pkg = TestIdvmPackage.build()
        val zipped = pkg.zipBytes.copyOf()
        val idx = indexOf(zipped, "IDVM".encodeToByteArray())
        assertTrue("应能在 zip 中找到 IDVM 魔数", idx >= 0)
        zipped[idx] = 'X'.code.toByte()
        val packageFile = File(tmp.root, "pkg-${java.util.UUID.randomUUID()}").apply { mkdirs() }.let { File(it, "test.idvm") }
        packageFile.writeBytes(zipped)
        val reason = requireFailure(
            IdvmImporter().importPackage(packageFile, tmp.newFolder("maps-${java.util.UUID.randomUUID()}"), MapCatalogDocument()) {}
        )
        assertTrue(reason.contains("IDVM") || reason.contains("魔数"))
    }

    @Test
    fun `清单声明文件但包内缺失被拒绝`() {
        val pkg = TestIdvmPackage.build(
            mutateManifest = { m ->
                m.copy(files = m.files + ManifestFile(path = "maps/x/data/ghost.json", size = 4, sha256 = "0".repeat(64)))
            }
        )
        val reason = requireFailure(doImport(pkg).result)
        assertTrue(reason.contains("缺失"))
    }

    @Test
    fun `包内存在清单未声明文件被拒绝`() {
        val pkg = TestIdvmPackage.build(extraEntries = mapOf("stray.txt" to byteArrayOf(1, 2, 3)))
        val reason = requireFailure(doImport(pkg).result)
        assertTrue(reason.contains("未声明"))
    }

    @Test
    fun `文件大小与清单不一致被拒绝`() {
        val pkg = TestIdvmPackage.build(
            mutateManifest = { m ->
                val first = m.files.first { it.path.endsWith(".png") }
                m.copy(files = m.files.map { if (it.path == first.path) it.copy(size = it.size + 1) else it })
            }
        )
        val reason = requireFailure(doImport(pkg).result)
        assertTrue(reason.contains("大小"))
    }

    @Test
    fun `文件摘要与清单不一致被拒绝`() {
        val pkg = TestIdvmPackage.build(
            mutateManifest = { m ->
                val first = m.files.first { it.path.endsWith(".png") }
                m.copy(files = m.files.map { if (it.path == first.path) it.copy(sha256 = "f".repeat(64)) else it })
            }
        )
        val reason = requireFailure(doImport(pkg).result)
        assertTrue(reason.contains("SHA-256"))
    }

    @Test
    fun `图片尺寸与 metadata 不一致被拒绝`() {
        val pkg = TestIdvmPackage.build(
            mutateMetadata = { md ->
                md.copy(floors = md.floors.map { it.copy(imageWidth = 2) })
            }
        )
        val reason = requireFailure(doImport(pkg).result)
        assertTrue(reason.contains("尺寸"))
    }

    @Test
    fun `空 Class 包被拒绝`() {
        val pkg = TestIdvmPackage.build(mutateManifest = { it.copy(classes = emptyList()) })
        val reason = requireFailure(doImport(pkg).result)
        assertTrue(reason.contains("Class"))
    }

    @Test
    fun `校验失败不残留任何地图目录或暂存目录`() {
        val pkg = TestIdvmPackage.build(corruptHeaderSha = true)
        val outcome = doImport(pkg)
        val leftovers = outcome.mapsRoot.listFiles() ?: emptyArray()
        assertEquals("不应残留地图目录或 staging", 0, leftovers.count { !it.name.startsWith("maps.json") })
        assertFalse("不应创建 maps.json", outcome.mapsRoot.listFiles()?.any { it.name == "maps.json" } ?: false)
    }

    @Test
    fun `楼层未按 sortOrder 排列时拒绝导入`() {
        // floor-001.png 对应 key 1f，floor-002.png 对应 2f；即使打乱也应按清单排序
        val pkg = TestIdvmPackage.build(
            floors = listOf(
                FloorImage("2f", "2F", 2, TestIdvmPackage.PNG_1X1),
                FloorImage("1f", "1F", 1, TestIdvmPackage.PNG_1X1),
            ),
        )
        assertTrue(requireFailure(doImport(pkg).result).contains("sortOrder"))
    }

    @Test
    fun `多地图多 Class 包整体导入`() {
        val pkg = TestIdvmPackage.build(
            packageId = java.util.UUID.randomUUID(),
            className = "S1",
            classId = "11111111-1111-4111-8111-111111111111",
            mapId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        )
        // 扩展为两个 Class、两张地图
        val json = IdvmJson.instance
        val m = pkg.manifest
        val secondClass = ManifestClass(classId = "22222222-2222-4222-8222-222222222222", name = "S2", mapIds = listOf("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"))
        val secondMap = ManifestMap(
            mapId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", mapVersion = 1, name = "红教堂",
            classId = "22222222-2222-4222-8222-222222222222",
            createdAt = m.maps[0].createdAt, updatedAt = m.maps[0].updatedAt,
            root = "maps/bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb",
            floors = listOf(ManifestFloor(key = "1f", displayName = "1F", sortOrder = 1, image = "maps/bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb/maps/floor-001.png")),
        )
        val secondMeta = MetadataDocument(
            schemaVersion = 1,
            map = MetadataMap(id = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", classId = "22222222-2222-4222-8222-222222222222", title = "红教堂", source = "manual", coordinateSystem = "normalized-top-left-y-down"),
            floors = listOf(MetadataFloor(key = "1f", displayName = "1F", sortOrder = 1, image = "maps/bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb/maps/floor-001.png", imageWidth = 1, imageHeight = 1, orientationDegrees = 0)),
        )
        val secondPayload: Map<String, ByteArray> = mapOf(
            "maps/bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb/maps/floor-001.png" to TestIdvmPackage.PNG_1X1,
            "maps/bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb/data/metadata.json" to json.encodeToString(MetadataDocument.serializer(), secondMeta).encodeToByteArray(),
            "maps/bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb/data/gates.json" to json.encodeToString(GatesDocument.serializer(), GatesDocument(schemaVersion = 1)).encodeToByteArray(),
            "maps/bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb/data/anchors.json" to json.encodeToString(AnchorsDocument.serializer(), AnchorsDocument(schemaVersion = 1, floors = mapOf("1f" to FloorAnchors()))).encodeToByteArray(),
        )
        val multiPkg = TestIdvmPackage.build(
            mutateManifest = { it.copy(classes = it.classes + secondClass, maps = it.maps + secondMap, files = it.files + secondPayload.map { (path, bytes) -> ManifestFile(path, bytes.size.toLong(), IdvmUtil.sha256Hex(IdvmUtil.sha256(bytes))) }) },
            extraEntries = secondPayload,
        )
        val success = requireSuccess(doImport(multiPkg).result)
        assertEquals(2, success.importedClasses.size)
        assertEquals(setOf("S1", "S2"), success.importedClasses.map { it.name }.toSet())
        assertEquals(2, success.importedMaps.size)
    }
}

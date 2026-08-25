package com.idvb.android.recognize

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.idvb.android.recognize.cv.OpenCvRuntime

data class CaptureDiagnosticsContext(
    val screenWidth: Int,
    val screenHeight: Int,
    val captureLeft: Int,
    val captureTop: Int,
    val captureRight: Int,
    val captureBottom: Int,
    val classId: String?,
    val className: String?,
)

/**
 * 保存可同时交给 Android 与 Desktop 识别器的原始输入及 Android 证据输出。
 * 包内 captured.png 从 MediaProjection 裁剪结果直接编码，不包含候选界面或悬浮窗。
 */
class RecognitionDiagnosticsStore(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "idvb/diagnostics/recognition")

    @Synchronized
    fun record(
        capturedFrame: Bitmap,
        result: RecognitionResult,
        captureContext: CaptureDiagnosticsContext,
    ): Result<File> = runCatching {
        require(!capturedFrame.isRecycled) { "原始扫描帧已经释放" }
        directory.mkdirs()
        require(directory.isDirectory) { "无法创建识别诊断目录" }

        val now = System.currentTimeMillis()
        val finalFile = File(directory, "recognition-$now.zip")
        val temporary = File(directory, ".recognition-$now.tmp")
        temporary.delete()
        try {
            ZipOutputStream(FileOutputStream(temporary)).use { zip ->
                zip.putNextEntry(ZipEntry("captured.png"))
                check(capturedFrame.compress(Bitmap.CompressFormat.PNG, 100, zip)) {
                    "无法编码原始扫描帧"
                }
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("diagnostics.json"))
                zip.write(buildManifest(capturedFrame, result, captureContext, now).toString().encodeToByteArray())
                zip.closeEntry()
            }
            check(temporary.renameTo(finalFile) || run {
                temporary.copyTo(finalFile, overwrite = true)
                temporary.delete()
            }) { "无法提交识别诊断包" }
            pruneOldPackages(keep = 10)
            finalFile
        } finally {
            temporary.delete()
        }
    }

    fun latestPackage(): File? = directory.listFiles { file ->
        file.isFile && file.name.startsWith("recognition-") && file.extension == "zip"
    }?.maxByOrNull(File::lastModified)

    fun shareLatest(context: Context): Boolean {
        val packageFile = latestPackage() ?: return false
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.diagnostics.files",
            packageFile,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "IDVB Android 识别诊断")
            clipData = ClipData.newRawUri("IDVB recognition diagnostics", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "分享识别诊断"))
        return true
    }

    private fun buildManifest(
        frame: Bitmap,
        result: RecognitionResult,
        context: CaptureDiagnosticsContext,
        createdAt: Long,
    ): JsonObject = buildJsonObject {
        put("schemaVersion", RecognitionPortContract.DIAGNOSTICS_SCHEMA_VERSION)
        put("createdAt", Instant.ofEpochMilli(createdAt).toString())
        put("route", result.diagnostics?.route ?: "unknown-or-debug-route")
        put("desktopReferenceCommit", RecognitionPortContract.DESKTOP_SOURCE_COMMIT)
        put("sideEntranceFeatureAlgorithmVersion", RecognitionPortContract.SIDE_ENTRANCE_FEATURE_ALGORITHM_VERSION)
        put("openCvAvailable", OpenCvRuntime.available)
        put("openCvVersion", OpenCvRuntime.version)
        put("capturedImage", "captured.png")
        put("frameWidth", frame.width)
        put("frameHeight", frame.height)
        put("screenWidth", context.screenWidth)
        put("screenHeight", context.screenHeight)
        put("captureRegion", buildJsonObject {
            put("left", context.captureLeft)
            put("top", context.captureTop)
            put("right", context.captureRight)
            put("bottom", context.captureBottom)
        })
        putNullable("classId", context.classId)
        putNullable("className", context.className)
        result.diagnostics?.let { diagnostics ->
            val rules = diagnostics.sideEntranceConfig
            put("sideEntranceRules", buildJsonObject {
                put("featureRatio", rules.featureRegionRatio)
                put("coarseScaleStep", rules.coarseScaleStep)
                put("refineStepsPerSide", rules.refineStepsPerSide)
                put("coarsePyramidFactor", rules.coarsePyramidFactor)
                put("scanParallelism", rules.scanParallelism)
                put("minimumScale", rules.minimumScale)
                put("maximumScale", rules.maximumScale)
                put("minimumReferenceSimilarity", rules.minimumReferenceSimilarity)
                put("minimumVerificationSimilarity", rules.minimumVerificationSimilarity)
                put("minimumTemplateMargin", rules.minimumTemplateMargin)
                put("maximumGateSpatialResidualPixels", rules.maximumGateSpatialResidualPixels)
                put("strictChamferLimit", SideEntranceRecognizer.STRICT_CHAMFER_LIMIT)
            })
            put("scanSummary", buildJsonObject {
                put("eligibleMapCount", diagnostics.eligibleMapCount)
                put("readyMapCount", diagnostics.readyMapCount)
                put("rejectedCandidateCount", diagnostics.rejectedCandidateCount)
                put("structureAlgorithmVersion", diagnostics.structureAlgorithmVersion)
                put("structureVerificationCount", diagnostics.structureVerificationCount)
                put("reliableCandidateCount", diagnostics.reliableCandidateCount)
                putFinite("structureTotalMilliseconds", diagnostics.structureTotalMilliseconds)
                put("failureReason", diagnostics.failureReason)
            })
            val gate = diagnostics.gateDetection
            put("gateDetection", buildJsonObject {
                put("searchMode", gate.searchModeUsed.name)
                put("stopReason", gate.stopReason.name)
                put("scalesEvaluated", gate.scalesEvaluated)
                put("regionsEvaluated", gate.regionsEvaluated)
                put("matchTemplateCalls", gate.matchTemplateCalls)
                put("budgetExceeded", gate.budgetExceeded)
                put("elapsedMilliseconds", gate.elapsedMilliseconds)
                put("gates", buildJsonArray {
                    gate.gates.forEachIndexed { index, item ->
                        add(buildJsonObject {
                            put("index", index)
                            put("score", item.score)
                            put("scale", item.scale)
                            put("bounds", buildJsonObject {
                                put("x", item.screenBounds.x)
                                put("y", item.screenBounds.y)
                                put("width", item.screenBounds.width)
                                put("height", item.screenBounds.height)
                            })
                        })
                    }
                })
            })
        }
        put("candidates", buildJsonArray {
            result.candidates.forEachIndexed { index, candidate ->
                add(buildJsonObject {
                    put("rank", index + 1)
                    put("localMapId", candidate.map.id)
                    put("sourceMapId", candidate.map.sourceMapId)
                    put("mapTitle", candidate.map.title)
                    put("floorKey", candidate.floorKey)
                    put("disposition", candidate.disposition.name)
                    putFinite("templateScore", candidate.templateScore)
                    putFinite("templateMargin", candidate.templateMargin)
                    putFinite("chamferPixels", candidate.chamferPixels)
                    putFinite("edgeCoverage", candidate.edgeCoverage)
                    putFinite("occupancyCoverage", candidate.occupancyCoverage)
                    putFinite("referenceCoverage", candidate.referenceCoverage)
                    put("consistentStructurePartitions", candidate.consistentStructurePartitions)
                    putFinite("structureCompositeCost", candidate.structureCompositeCost)
                    putFinite("structureCandidateMargin", candidate.structureCandidateMargin)
                    putFinite("structureScale", candidate.structureScale)
                    putFinite("structureOffsetX", candidate.structureOffsetX)
                    putFinite("structureOffsetY", candidate.structureOffsetY)
                    putNullable("structureRejectionReason", candidate.structureRejectionReason?.name)
                    put("usedStructureGlobalRecovery", candidate.usedStructureGlobalRecovery)
                    putFinite("structureElapsedMilliseconds", candidate.structureElapsedMilliseconds)
                    putFinite("matchScale", candidate.matchScale)
                    put("gateAssociation", candidate.gateAssociationKind.name)
                    put("associatedGateIndex", candidate.associatedGateIndex)
                    putFinite("gateSpatialResidualPixels", candidate.gateSpatialResidualPixels)
                    candidate.matchBounds?.let { bounds ->
                        put("matchBounds", buildJsonObject {
                            put("x", bounds.x)
                            put("y", bounds.y)
                            put("width", bounds.width)
                            put("height", bounds.height)
                        })
                    }
                    put("evidenceLabel", candidate.evidenceLabel)
                })
            }
        })
    }

    private fun pruneOldPackages(keep: Int) {
        directory.listFiles { file ->
            file.isFile && file.name.startsWith("recognition-") && file.extension == "zip"
        }?.sortedByDescending(File::lastModified)
            ?.drop(keep.coerceAtLeast(1))
            ?.forEach(File::delete)
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putFinite(name: String, value: Double) {
    put(name, if (value.isFinite()) JsonPrimitive(value) else JsonNull)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(name: String, value: String?) {
    put(name, value?.let(::JsonPrimitive) ?: JsonNull)
}

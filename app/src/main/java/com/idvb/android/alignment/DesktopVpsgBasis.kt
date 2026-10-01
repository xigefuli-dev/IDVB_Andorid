package com.idvb.android.alignment

/** Source evidence for this port. Android uses the shared bootstrap, not desktop continuous tracking. */
internal object DesktopVpsgBasis {
    val labels = mapOf(
        "desktopRepository" to "IDV_Buff",
        "desktopBaseCommit" to "ff8ad3f536c631ef90b38a683e8e3b97014f9e1c",
        "desktopAlignSource" to "Features/Maps/MapCvRecognitionService.Vpsg3.Align.cs",
        "desktopAlignSha256" to "D1A721283C021C3DF82057C5340F5DEA90DA7604E1E7EEB75B05C95026CC03DE",
        "desktopPrecisionSource" to "Features/Maps/Vpsg3/Vpsg3PrecisionRefiner.cs",
        "desktopPrecisionSha256" to "337F55069D8D34E3DC2CA97A4BBBD2279CBB8CD3C45505EB11990C650E784C88",
        "desktopBootstrapSource" to "Features/Maps/Vpsg3/Vpsg3FastBootstrapSolver.cs",
        "desktopBootstrapSha256" to "BA7976C849AE35D84FCDE7E163BFC193CF784AC2547C67E2612EBE863C9E2505",
    )
}

package com.sukisu.ultra.ui.privileged

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Raw partition flashing, for VIP users.
 *
 * This is the same thing TWRP, kernel managers and Flashify do: enumerate /dev/block/by-name and
 * write an image over one of those block devices with dd. It is deliberately conservative:
 *
 *   - the image must fit inside the partition, otherwise the write is refused outright,
 *   - partitions whose loss bricks the device are flagged and need a typed confirmation,
 *   - a backup can be taken first, and is offered prominently,
 *   - every step is echoed into the log so the user can see exactly what ran.
 *
 * Nothing here talks to the network and nothing reads a device identifier.
 */
object PartitionFlasher {
    private const val TAG = "PaperSUPartition"

    data class Partition(
        val name: String,
        val block: String,
        val size: Long,
    ) {
        val dangerous: Boolean
            get() = name.lowercase(Locale.US) in DANGEROUS

        val humanSize: String
            get() = when {
                size >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", size / 1073741824.0)
                size >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", size / 1048576.0)
                size > 0 -> String.format(Locale.US, "%.0f KB", size / 1024.0)
                else -> "?"
            }
    }

    /**
     * Getting one of these wrong can leave the phone unable to boot, or wipe calibration data
     * such as the radio and sensor calibration. They are still flashable, because restoring a
     * backup of them is a legitimate thing to want, but the UI makes the user type the name.
     */
    private val DANGEROUS = setOf(
        "abl", "xbl", "xbl_config", "tz", "hyp", "keymaster", "cmnlib", "cmnlib64",
        "devcfg", "aop", "qupfw", "uefisecapp", "storsec", "featenabler",
        "modem", "modemst1", "modemst2", "fsg", "fsc", "bluetooth",
        "persist", "persistbak", "efs", "devinfo", "misc", "param", "oem",
        "vbmeta", "vbmeta_system", "vbmeta_vendor", "dtbo", "spmfw", "sspm", "mcupm", "scp",
    )

    private fun sh(vararg cmds: String): String = runCatching {
        val result = Shell.cmd(*cmds).exec()
        (result.out + result.err).joinToString("\n")
    }.getOrElse {
        Log.w(TAG, "shell failed", it)
        "error: ${it.message}"
    }

    /** Enumerate /dev/block/by-name. */
    fun list(context: Context): List<Partition> {
        val script = """
            for f in /dev/block/by-name/*; do
              n=${'$'}{f##*/}
              b=${'$'}(readlink -f "${'$'}f" 2>/dev/null)
              s=${'$'}(blockdev --getsize64 "${'$'}f" 2>/dev/null)
              [ -n "${'$'}b" ] && echo "${'$'}n|${'$'}b|${'$'}s"
            done
        """.trimIndent()
        val file = File(context.cacheDir, "psu-list-parts.sh")
        runCatching {
            file.writeText(script)
            file.setExecutable(true, false)
        }
        val out = sh("sh ${file.absolutePath}")
        return out.lineSequence()
            .map { it.trim() }
            .filter { it.count { c -> c == '|' } == 2 }
            .mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size != 3) return@mapNotNull null
                val block = parts[1].trim()
                if (block.isBlank() || !block.startsWith("/dev/block/")) return@mapNotNull null
                Partition(parts[0].trim(), block, parts[2].trim().toLongOrNull() ?: 0L)
            }
            .distinctBy { it.name }
            .sortedBy { it.name }
            .toList()
    }

    private fun backupDir(context: Context): File =
        File(context.getExternalFilesDir(null), "partitions").apply { mkdirs() }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    /** Read a partition into a file under the app's own external dir. */
    fun backup(context: Context, partition: Partition, onLog: (String) -> Unit): File? {
        val dest = File(backupDir(context), "${partition.name}-${stamp()}.img")
        onLog("备份 ${partition.name} → ${dest.absolutePath}\n")
        val out = sh("dd if=${partition.block} of=${dest.absolutePath} bs=4M 2>&1")
        onLog(out.trim() + "\n")
        return if (dest.isFile && dest.length() > 0) dest else null
    }

    sealed interface FlashOutcome {
        data class Done(val bytes: Long) : FlashOutcome
        data class Refused(val reason: String) : FlashOutcome
    }

    /**
     * Write an image over a partition.
     *
     * Refuses when the image is larger than the partition: dd would happily run off the end and
     * destroy whatever lives after it.
     */
    fun flash(partition: Partition, image: File, onLog: (String) -> Unit): FlashOutcome {
        if (!image.isFile) {
            return FlashOutcome.Refused("镜像文件不存在")
        }
        val imageSize = image.length()
        if (imageSize <= 0) {
            return FlashOutcome.Refused("镜像文件是空的")
        }
        if (partition.size > 0 && imageSize > partition.size) {
            val reason = "镜像 ${imageSize} 字节，比分区 ${partition.name} 的 ${partition.size} 字节还大，" +
                "写进去会越界破坏后面的数据 —— 已拒绝"
            onLog("拒绝：$reason\n")
            return FlashOutcome.Refused(reason)
        }

        onLog("写入 ${image.name}（$imageSize 字节）→ ${partition.name}（${partition.block}）\n")
        onLog("分区原有内容会被完全覆盖，且不可撤销。\n")
        val out = sh("dd if=${image.absolutePath} of=${partition.block} bs=4M 2>&1; sync")
        onLog(out.trim() + "\n")
        return if (out.contains("error", ignoreCase = true) || out.contains("No space", ignoreCase = true)) {
            FlashOutcome.Refused(out.trim())
        } else {
            FlashOutcome.Done(imageSize)
        }
    }

    /** Free space where backups go, so the user is warned before a 128 MB read fails. */
    fun backupSpace(context: Context): Long = runCatching { backupDir(context).usableSpace }.getOrDefault(0L)
}
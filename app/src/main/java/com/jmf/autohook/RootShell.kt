package com.jmf.autohook

import android.graphics.Bitmap
import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 常驻 root 会话：整个 App 进程只向 root 管理器申请一次授权，
 * 之后所有命令（改设置 / 截图）都复用同一个 shell，避免反复弹窗抢焦点。
 */
object RootShell {
    private const val TAG = "AutoHook"
    private const val CMD_MARK = "__HOOK_CMD__"
    private const val CMD_END = "__HOOK_END__"
    private const val CAP_MARK = "__HOOK_CAP__"

    @Volatile private var proc: Process? = null
    private var stdin: OutputStream? = null
    private var stdout: BufferedInputStream? = null
    private val lock = Any()

    /** 最近一次截图的实际宽高，供调用方校准坐标系 */
    @Volatile var lastWidth = 0
    @Volatile var lastHeight = 0

    fun isReady(): Boolean {
        val p = proc ?: return false
        return try {
            p.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    /** 建立会话（未授权时会弹一次 root 授权），返回是否可用 */
    fun ensure(timeoutMs: Long = 15000): Boolean = synchronized(lock) { ensureInternal(timeoutMs) }

    private fun ensureInternal(timeoutMs: Long): Boolean {
        if (isReady()) return true
        closeInternal()
        return try {
            val p = ProcessBuilder("su").redirectErrorStream(false).start()
            val ins = BufferedInputStream(p.inputStream, 1 shl 20)
            val os = p.outputStream
            proc = p
            stdin = os
            stdout = ins
            os.write("id\n".toByteArray())
            os.flush()
            val line = readLine(p, ins, timeoutMs)
            if (line != null && line.contains("uid=0")) {
                Log.i(TAG, "root 会话已建立")
                true
            } else {
                Log.w(TAG, "root 会话建立失败: $line")
                closeInternal()
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "root 会话异常: ${e.message}")
            closeInternal()
            false
        }
    }

    /** 执行一条命令并返回输出（用标记行定位，跳过提示符等杂音） */
    fun exec(cmd: String, timeoutMs: Long = 8000): String? = synchronized(lock) {
        if (!ensureInternal(timeoutMs)) return null
        val p = proc ?: return null
        val os = stdin ?: return null
        val ins = stdout ?: return null
        try {
            os.write("echo $CMD_MARK\n$cmd\necho $CMD_END\n".toByteArray())
            os.flush()
            if (skipUntil(p, ins, CMD_MARK, timeoutMs) != true) return null
            val sb = StringBuilder()
            while (true) {
                val line = readLine(p, ins, timeoutMs) ?: return null
                if (line == CMD_END) break
                sb.append(line).append('\n')
            }
            sb.toString()
        } catch (_: Exception) {
            null
        }
    }

    /** 截图：screencap 直接输出原始 RGBA（12 字节头 + 像素） */
    fun screencap(timeoutMs: Long = 10000): Bitmap? = synchronized(lock) {
        if (!ensureInternal(timeoutMs)) return null
        val p = proc ?: return null
        val os = stdin ?: return null
        val ins = stdout ?: return null
        val killer = watchdog(p, timeoutMs)
        try {
            os.write("echo $CAP_MARK\nscreencap\n".toByteArray())
            os.flush()
            if (skipUntil(p, ins, CAP_MARK, timeoutMs) != true) return null
            val header = ByteArray(12)
            if (!readFully(ins, header)) return null
            val w = leInt(header, 0)
            val h = leInt(header, 4)
            val fmt = leInt(header, 8)
            // 1 = RGBA_8888，4 = RGBX_8888；并限制像素总量，防止头信息异常时按超大尺寸分配内存
            if (w !in 16..8192 || h !in 16..8192 || (fmt != 1 && fmt != 4) ||
                w.toLong() * h.toLong() > 4_000_000L
            ) {
                Log.w(TAG, "screencap 格式不支持 w=$w h=$h fmt=$fmt")
                return null
            }
            val buf = ByteArray(w * h * 4)
            if (!readFully(ins, buf)) return null
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(buf))
            lastWidth = w
            lastHeight = h
            bmp
        } catch (_: Exception) {
            null
        } finally {
            killer.interrupt()
        }
    }

    fun close() = synchronized(lock) { closeInternal() }

    private fun closeInternal() {
        try { proc?.destroy() } catch (_: Exception) {}
        try { stdin?.close() } catch (_: Exception) {}
        try { stdout?.close() } catch (_: Exception) {}
        proc = null
        stdin = null
        stdout = null
    }

    private fun watchdog(p: Process, timeoutMs: Long): Thread {
        val t = Thread {
            try {
                Thread.sleep(timeoutMs)
                p.destroy()
            } catch (_: Exception) {
            }
        }
        t.isDaemon = true
        t.start()
        return t
    }

    private fun readLine(p: Process, ins: InputStream, timeoutMs: Long): String? {
        val killer = watchdog(p, timeoutMs)
        try {
            val sb = StringBuilder()
            while (true) {
                val b = ins.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString().trim()
                if (b == '\n'.code) {
                    if (sb.isNotEmpty()) return sb.toString().trim()
                } else if (b != '\r'.code) {
                    if (sb.length < 8192) sb.append(b.toChar())
                }
            }
        } catch (_: Exception) {
            return null
        } finally {
            killer.interrupt()
        }
    }

    private fun skipUntil(p: Process, ins: InputStream, marker: String, timeoutMs: Long): Boolean? {
        var i = 0
        while (i < 64) {
            val line = readLine(p, ins, timeoutMs) ?: return null
            if (line == marker) return true
            i++
        }
        return null
    }

    private fun readFully(ins: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
}

package com.lelloman.simpleaiassistant.diagnostics

import java.io.File
import java.io.FileOutputStream

/** Host must use an account-private, backup-excluded directory. Called only on IO. */
interface DiagnosticStorage {
    fun read(maxBytes: Int): String?
    fun write(content: String)
    fun clear()
}

class FileDiagnosticStorage(private val file: File) : DiagnosticStorage {
    private val temporary get() = File(file.path + ".tmp")
    override fun read(maxBytes: Int): String? {
        if (!file.exists()) return null
        require(file.length() <= maxBytes)
        return file.inputStream().use { input ->
            val bytes = input.readBytesBounded(maxBytes)
            bytes.toString(Charsets.UTF_8)
        }
    }
    override fun write(content: String) {
        file.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        FileOutputStream(temporary).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        check(temporary.renameTo(file)) { "Could not replace diagnostic file" }
    }
    override fun clear() {
        check(!temporary.exists() || temporary.delete())
        check(!file.exists() || file.delete())
    }
    private fun java.io.InputStream.readBytesBounded(max: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer, 0, minOf(buffer.size, max + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
            require(output.size() <= max)
        }
        return output.toByteArray()
    }
}

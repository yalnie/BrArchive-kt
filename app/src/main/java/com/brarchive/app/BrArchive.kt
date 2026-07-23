package com.brarchive.app

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

object BrArchive {
    const val MAGIC: Long = 0x267052A0B125277DL
    const val VERSION: Int = 1
    const val ENTRY_NAME_LEN_MAX: Int = 247
    const val HEADER_SIZE: Int = 16
    const val DESCRIPTOR_SIZE: Int = 256

    private data class DescInfo(val name: String, val offset: Int, val len: Int)

    /**
     * 细水长流版打包 (Encode)
     * @param entries 文件名与对应物理文件的映射
     * @param outputFile 输出的 .brarchive 文件
     * @param dedup 是否开启去重
     */
    fun encode(entries: Map<String, File>, outputFile: File, dedup: Boolean) {
        val entryCount = entries.size
        val descriptors = mutableListOf<DescInfo>()
        val filesToWrite = mutableListOf<File>()

        var currentOffset = 0
        // 用于去重：MD5哈希值 -> 文件在归档中的Offset
        val hashToOffset = mutableMapOf<String, Int>()

        // 阶段 1：遍历文件，计算去重信息与描述符偏移量（不将文件全部读进内存）
        for ((name, file) in entries) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            if (nameBytes.size > ENTRY_NAME_LEN_MAX) throw EntryNameTooLongException(nameBytes.size)

            val len = file.length().toInt() // 格式限制每个单文件最大约2GB

            if (dedup) {
                val hash = calculateMD5(file)
                if (hashToOffset.containsKey(hash)) {
                    // 如果发现重复，复用之前的偏移量，且不需要再次写入该文件
                    descriptors.add(DescInfo(name, hashToOffset[hash]!!, len))
                } else {
                    hashToOffset[hash] = currentOffset
                    descriptors.add(DescInfo(name, currentOffset, len))
                    filesToWrite.add(file)
                    currentOffset += len
                }
            } else {
                descriptors.add(DescInfo(name, currentOffset, len))
                filesToWrite.add(file)
                currentOffset += len
            }
        }

        // 阶段 2：创建输出流，开始真正写出数据
        outputFile.parentFile?.mkdirs()
        BufferedOutputStream(FileOutputStream(outputFile)).use { out ->
            
            // 小端序写入工具函数
            fun writeIntLE(v: Int) {
                out.write(v and 0xFF)
                out.write((v ushr 8) and 0xFF)
                out.write((v ushr 16) and 0xFF)
                out.write((v ushr 24) and 0xFF)
            }
            fun writeLongLE(v: Long) {
                out.write((v and 0xFF).toInt())
                out.write(((v ushr 8) and 0xFF).toInt())
                out.write(((v ushr 16) and 0xFF).toInt())
                out.write(((v ushr 24) and 0xFF).toInt())
                out.write(((v ushr 32) and 0xFF).toInt())
                out.write(((v ushr 40) and 0xFF).toInt())
                out.write(((v ushr 48) and 0xFF).toInt())
                out.write(((v ushr 56) and 0xFF).toInt())
            }

            // 写 Header (16 字节)
            writeLongLE(MAGIC)
            writeIntLE(entryCount)
            writeIntLE(VERSION)

            // 写 Descriptors (条目数 * 256 字节)
            for (desc in descriptors) {
                val nameBytes = desc.name.toByteArray(Charsets.UTF_8)
                out.write(nameBytes.size)
                out.write(nameBytes)
                val padding = ByteArray(ENTRY_NAME_LEN_MAX - nameBytes.size)
                out.write(padding) // 补齐 247 字节
                writeIntLE(desc.offset)
                writeIntLE(desc.len)
            }

            // 写 Content：使用 64KB 的缓冲区“搬运”文件，避免内存爆炸
            val buffer = ByteArray(64 * 1024)
            for (file in filesToWrite) {
                FileInputStream(file).use { fis ->
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    /**
     * 细水长流版解包 (Decode)
     */
    fun decode(inputFile: File, outDir: File) {
        // 使用 RandomAccessFile 方便我们在二进制文件中随意跳跃（seek）去拿对应的内容
        RandomAccessFile(inputFile, "r").use { raf ->
            val header = ByteArray(HEADER_SIZE)
            raf.readFully(header)
            val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            val magic = hb.long
            if (magic != MAGIC) throw MagicMismatchException(magic)
            val entryCount = hb.int
            val version = hb.int
            if (version != VERSION) throw UnsupportedVersionException(version)

            val descBytes = ByteArray(entryCount * DESCRIPTOR_SIZE)
            raf.readFully(descBytes)
            val db = ByteBuffer.wrap(descBytes).order(ByteOrder.LITTLE_ENDIAN)

            val descriptors = mutableListOf<DescInfo>()
            for (i in 0 until entryCount) {
                val nameLen = db.get().toInt() and 0xFF
                if (nameLen > ENTRY_NAME_LEN_MAX) throw EntryNameTooLongException(nameLen)
                
                val nameBytes = ByteArray(nameLen)
                db.get(nameBytes)
                db.position(db.position() + (ENTRY_NAME_LEN_MAX - nameLen))
                
                val offset = db.int
                val len = db.int
                descriptors.add(DescInfo(String(nameBytes, Charsets.UTF_8), offset, len))
            }

            val contentBase = HEADER_SIZE + (entryCount * DESCRIPTOR_SIZE).toLong()
            val buffer = ByteArray(64 * 1024)

            // 开始按描述符抽取文件内容并写出
            for (desc in descriptors) {
                val destFile = File(outDir, desc.name)
                destFile.parentFile?.mkdirs()

                raf.seek(contentBase + desc.offset)
                var bytesRemaining = desc.len
                
                // “搬运工”模式直接写出文件
                FileOutputStream(destFile).use { fos ->
                    while (bytesRemaining > 0) {
                        val toRead = minOf(buffer.size, bytesRemaining)
                        val read = raf.read(buffer, 0, toRead)
                        if (read == -1) break
                        fos.write(buffer, 0, read)
                        bytesRemaining -= read
                    }
                }
            }
        }
    }

    /**
     * 获取归档内文件名列表 (轻量读取)
     */
    fun list(inputFile: File): List<String> {
        val names = mutableListOf<String>()
        RandomAccessFile(inputFile, "r").use { raf ->
            val header = ByteArray(HEADER_SIZE)
            if (raf.read(header) < HEADER_SIZE) return emptyList()
            val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            if (hb.long != MAGIC) return emptyList()
            val entryCount = hb.int
            
            val descBytes = ByteArray(entryCount * DESCRIPTOR_SIZE)
            if (raf.read(descBytes) < descBytes.size) return emptyList()
            val db = ByteBuffer.wrap(descBytes).order(ByteOrder.LITTLE_ENDIAN)

            for (i in 0 until entryCount) {
                val nameLen = db.get().toInt() and 0xFF
                val nameBytes = ByteArray(nameLen)
                db.get(nameBytes)
                names.add(String(nameBytes, Charsets.UTF_8))
                db.position(db.position() + (ENTRY_NAME_LEN_MAX - nameLen) + 8)
            }
        }
        return names
    }

    // 私有辅助方法：用极小内存计算文件的 MD5 哈希
    private fun calculateMD5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
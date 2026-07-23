package com.brarchive.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

object BrArchive {
    const val MAGIC: Long = 0x267052A0B125277DL
    const val VERSION: Int = 1
    const val ENTRY_NAME_LEN_MAX: Int = 247
    const val HEADER_SIZE: Int = 16
    const val DESCRIPTOR_SIZE: Int = 256

    data class EntryDescriptor(val name: String, val contentsOffset: Int, val contentsLen: Int)

    fun serialize(entries: Map<String, String>, dedup: Boolean = false): ByteArray {
        val entryCount = entries.size
        val contentBase = HEADER_SIZE + (entryCount * DESCRIPTOR_SIZE)

        var currentOffset = 0
        val contentBytesList = mutableListOf<ByteArray>()
        val contentIndex = mutableMapOf<ByteBuffer, Int>()
        val descriptorBuffer = ByteBuffer.allocate(entryCount * DESCRIPTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        for ((name, content) in entries) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            if (nameBytes.size > ENTRY_NAME_LEN_MAX) throw EntryNameTooLongException(nameBytes.size)

            val cBytes = content.toByteArray(Charsets.UTF_8)
            val cLen = cBytes.size
            var cOffset = currentOffset

            if (dedup) {
                val wrapper = ByteBuffer.wrap(cBytes)
                if (contentIndex.containsKey(wrapper)) {
                    cOffset = contentIndex[wrapper]!!
                } else {
                    contentIndex[wrapper] = currentOffset
                    contentBytesList.add(cBytes)
                    currentOffset = Math.addExact(currentOffset, cLen)
                }
            } else {
                contentBytesList.add(cBytes)
                currentOffset = Math.addExact(currentOffset, cLen)
            }

            descriptorBuffer.put(nameBytes.size.toByte())
            descriptorBuffer.put(nameBytes)
            val padding = ByteArray(ENTRY_NAME_LEN_MAX - nameBytes.size)
            descriptorBuffer.put(padding)
            descriptorBuffer.putInt(cOffset)
            descriptorBuffer.putInt(cLen)
        }

        val totalSize = contentBase + currentOffset
        if (totalSize < 0) throw ContentTooLargeException()

        val finalBuf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        finalBuf.putLong(MAGIC)
        finalBuf.putInt(entryCount)
        finalBuf.putInt(VERSION)

        descriptorBuffer.flip()
        finalBuf.put(descriptorBuffer)
        for (c in contentBytesList) finalBuf.put(c)

        return finalBuf.array()
    }

    fun deserialize(data: ByteArray): Map<String, String> {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        
        if (buf.remaining() < HEADER_SIZE) throw BrArchiveIoException("File too small")
        val magic = buf.long
        if (magic != MAGIC) throw MagicMismatchException(magic)
        
        val entriesCount = buf.int
        val version = buf.int
        if (version != VERSION) throw UnsupportedVersionException(version)

        val descriptors = mutableListOf<EntryDescriptor>()
        for (i in 0 until entriesCount) {
            val nameLen = buf.get().toInt() and 0xFF
            if (nameLen > ENTRY_NAME_LEN_MAX) throw EntryNameTooLongException(nameLen)
            
            val nameBytes = ByteArray(nameLen)
            buf.get(nameBytes)
            buf.position(buf.position() + (ENTRY_NAME_LEN_MAX - nameLen))
            
            val offset = buf.int
            val len = buf.int
            descriptors.add(EntryDescriptor(String(nameBytes, Charsets.UTF_8), offset, len))
        }

        val contentBase = buf.position()
        val result = mutableMapOf<String, String>()

        for (desc in descriptors) {
            val cBytes = ByteArray(desc.contentsLen)
            buf.position(contentBase + desc.contentsOffset)
            buf.get(cBytes)
            result[desc.name] = String(cBytes, Charsets.UTF_8)
        }
        return result
    }

    fun list(data: ByteArray): List<String> {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < HEADER_SIZE) return emptyList()
        
        val magic = buf.long
        if (magic != MAGIC) throw MagicMismatchException(magic)
        
        val entriesCount = buf.int
        val version = buf.int
        if (version != VERSION) throw UnsupportedVersionException(version)

        val names = mutableListOf<String>()
        for (i in 0 until entriesCount) {
            val nameLen = buf.get().toInt() and 0xFF
            val nameBytes = ByteArray(nameLen)
            buf.get(nameBytes)
            names.add(String(nameBytes, Charsets.UTF_8))
            buf.position(buf.position() + (ENTRY_NAME_LEN_MAX - nameLen) + 8)
        }
        return names
    }
}
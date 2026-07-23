package com.brarchive.app

sealed class BrArchiveException(message: String) : Exception(message)

class MagicMismatchException(val magic: Long) :
    BrArchiveException("Magic Sequence Mismatch (expected 2769805646197172093, got $magic)")

class UnsupportedVersionException(val version: Int) :
    BrArchiveException("Unsupported Archive Version $version")

class EntryNameTooLongException(val length: Int) :
    BrArchiveException("Entry Name too long! Got $length bytes, maximum 247")

class TooManyEntriesException(val count: Int) :
    BrArchiveException("Too many entries: $count exceeds the maximum")

class ContentTooLargeException :
    BrArchiveException("Content block exceeds maximum array size limit (2 GiB in JVM)")

class BrArchiveIoException(message: String, cause: Throwable? = null) :
    BrArchiveException(message)
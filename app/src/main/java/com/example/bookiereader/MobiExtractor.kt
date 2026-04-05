package com.example.bookiereader

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

object MobiExtractor {
    fun extractText(context: Context, file: File): MobiData {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(76)
            val numRecords = raf.readUnsignedShort()
            
            val recordOffsets = LongArray(numRecords)
            for (_i in 0 until numRecords) {
                recordOffsets[_i] = raf.readInt().toLong() and 0xFFFFFFFFL
                raf.skipBytes(4) // Skip attributes and unique ID
            }
            
            if (numRecords == 0) return MobiData(context.getString(R.string.error_no_records_mobi), null, null)
            
            // Record 0 contains the PalmDoc Header followed by MOBI Header
            raf.seek(recordOffsets[0])
            
            // PalmDoc Header (16 bytes)
            val compression = raf.readUnsignedShort()
            raf.skipBytes(2) // Skip unused
            raf.skipBytes(4) // Skip textLength
            val textRecordCount = raf.readUnsignedShort()
            raf.skipBytes(6) // Skip textRecordSize and encryption
            
            // MOBI Header starts after PalmDoc Header
            val mobiHeaderOffset = recordOffsets[0] + 16
            raf.seek(mobiHeaderOffset)
            val mobiId = ByteArray(4)
            raf.read(mobiId)
            if (String(mobiId) != "MOBI") return MobiData(context.getString(R.string.error_invalid_mobi), null, null)

            val mobiHeaderLength = raf.readInt()
            
            // Encoding: 4 bytes at offset 28 in MOBI header
            raf.seek(mobiHeaderOffset + 28)
            val encoding = raf.readInt()
            val charset = if (encoding == 65001) Charsets.UTF_8 else Charset.forName("windows-1252")
            
            // Extra Data Flags at offset 242 in MOBI header
            raf.seek(mobiHeaderOffset + 242)
            val extraDataFlags = if (mobiHeaderLength >= 242 + 2) raf.readUnsignedShort() else 0
            
            // First Image Index at offset 108 in MOBI header
            raf.seek(mobiHeaderOffset + 108)
            val firstImageIndex = raf.readInt()
            
            Log.d("MobiExtractor", "Compression: $compression, Records: $textRecordCount, Flags: $extraDataFlags, Encoding: $encoding, FirstImageIndex: $firstImageIndex")

            val allBytes = mutableListOf<Byte>()
            
            // Extract text records (Record 1 to textRecordCount)
            for (idx in 1..textRecordCount) {
                if (idx >= numRecords) break
                val offset = recordOffsets[idx]
                val nextOffset = if (idx + 1 < numRecords) recordOffsets[idx + 1] else file.length()
                val size = (nextOffset - offset).toInt()
                
                raf.seek(offset)
                val data = ByteArray(size)
                raf.read(data)
                
                val decompressed = when (compression) {
                    1 -> data // No compression
                    2 -> PalmDocDecompressor.decompress(data)
                    else -> null
                }
                
                if (decompressed != null) {
                    val extraBytes = getExtraBytesCount(decompressed, extraDataFlags)
                    val actualSize = (decompressed.size - extraBytes).coerceAtLeast(0)
                    
                    for (k in 0 until actualSize) {
                        allBytes.add(decompressed[k])
                    }
                } else if (compression == 17480) {
                    return MobiData(context.getString(R.string.error_huff_cdic_unsupported), null, null)
                }
            }

            val text = String(allBytes.toByteArray(), charset)
            
            // Extract images
            val images = mutableMapOf<Int, ByteArray>()
            if (firstImageIndex != -1) {
                for (idx in firstImageIndex until numRecords) {
                    val offset = recordOffsets[idx]
                    val nextOffset = if (idx + 1 < numRecords) recordOffsets[idx + 1] else file.length()
                    val size = (nextOffset - offset).toInt()
                    if (size <= 0) continue
                    
                    raf.seek(offset)
                    val imgData = ByteArray(size)
                    raf.read(imgData)
                    
                    // Check if it's actually an image (could be other resources)
                    // Simple check for JPEG, PNG, GIF
                    if (imgData.size > 4) {
                        val isImage = (imgData[0] == 0xFF.toByte() && imgData[1] == 0xD8.toByte()) || // JPEG
                                      (imgData[0] == 0x89.toByte() && imgData[1] == 'P'.code.toByte()) || // PNG
                                      (imgData[0] == 'G'.code.toByte() && imgData[1] == 'I'.code.toByte()) // GIF
                        
                        if (isImage) {
                            // index in map should be (idx - firstImageIndex + 1) to match recindex
                            images[idx - firstImageIndex + 1] = imgData
                        }
                    }
                }
            }

            var series: String? = null
            var seriesIndex: Float? = null

            // EXTH Header
            raf.seek(mobiHeaderOffset + 12)
            val hasExth = (raf.readInt() and 0x40) != 0
            if (hasExth) {
                val exthOffset = mobiHeaderOffset + mobiHeaderLength
                raf.seek(exthOffset)
                val exthId = ByteArray(4)
                raf.read(exthId)
                if (String(exthId) == "EXTH") {
                    val exthLength = raf.readInt()
                    val recordCount = raf.readInt()
                    for (_i in 0 until recordCount) {
                        if (raf.filePointer >= exthOffset + exthLength) break
                        val recordType = raf.readInt()
                        val recordLen = raf.readInt()
                        val dataLen = recordLen - 8
                        if (dataLen > 0) {
                            val data = ByteArray(dataLen)
                            raf.read(data)
                            when (recordType) {
                                501 -> series = String(data, charset)
                                504 -> seriesIndex = String(data, charset).toFloatOrNull()
                            }
                        }
                    }
                }
            }

            return MobiData(text, series, seriesIndex, images)
        }
    }

    private fun getExtraBytesCount(data: ByteArray, flags: Int): Int {
        if (data.isEmpty()) return 0
        var total = 0
        var f = flags
        
        // Bit 0: Multi-byte extra data (stored at the very end)
        if ((f and 1) != 0) {
            val v = data[data.size - 1].toInt() and 0xFF
            total = (v and 0x03) + 1
        }
        
        // Other bits: Variable-length data stored before bit 0 data
        f = f shr 1
        while (f > 0) {
            if ((f and 1) != 0) {
                total += getTrailingDataSize(data, data.size - total)
            }
            f = f shr 1
        }
        return total
    }

    private fun getTrailingDataSize(data: ByteArray, end: Int): Int {
        var pos = end - 1
        if (pos < 0) return 0
        
        val v = data[pos].toInt() and 0xFF
        if ((v and 0x80) == 0) return 0 // Last byte must have bit 7 set
        
        var size = v and 0x7F
        var shift = 7
        pos--
        while (pos >= 0 && shift < 28) {
            val vv = data[pos].toInt() and 0xFF
            if ((vv and 0x80) != 0) break // Found another high bit, belongs to a previous field
            size = size or ((vv and 0x7F) shl shift)
            shift += 7
            pos--
        }
        return size
    }
}

data class MobiData(
    val text: String,
    val series: String?,
    val seriesIndex: Float?,
    val images: Map<Int, ByteArray> = emptyMap()
)

object PalmDocDecompressor {
    fun decompress(input: ByteArray): ByteArray {
        val output = ArrayList<Byte>(input.size * 2)
        var i = 0
        while (i < input.size) {
            val c = input[i].toInt() and 0xFF
            i++
            
            when {
                c == 0x00 -> { // Null byte
                    output.add(0)
                }
                c in 1..8 -> { // Literal copy
                    for (_j in 0 until c) {
                        if (i < input.size) {
                            output.add(input[i])
                            i++
                        }
                    }
                }
                c <= 0x7F -> { // Literal char
                    output.add(c.toByte())
                }
                c >= 0xC0 -> { // Space + char
                    output.add(' '.code.toByte())
                    output.add((c xor 0x80).toByte())
                }
                else -> { // 0x80 to 0xBF: Distance/Length
                    if (i < input.size) {
                        val c2 = input[i].toInt() and 0xFF
                        i++
                        
                        val compound = ((c shl 8) or c2) and 0x3FFF
                        val distance = compound shr 3
                        val length = (compound and 0x07) + 3
                        
                        val start = output.size - distance
                        if (distance > 0) {
                            for (_j in 0 until length) {
                                val pos = start + _j
                                if (pos >= 0 && pos < output.size) {
                                    output.add(output[pos])
                                }
                            }
                        }
                    }
                }
            }
        }
        val result = ByteArray(output.size)
        for (idx in output.indices) result[idx] = output[idx]
        return result
    }
}

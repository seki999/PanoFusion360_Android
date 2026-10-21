package com.panofusion.app

import java.io.File
import java.io.RandomAccessFile

/** ISO BMFF Spherical Video v2 boxes, with bounded box traversal. Aspect ratio is never proof. */
object VideoProjection {
    enum class Type { MONO_ERP, STEREO, OTHER_PROJECTION, UNKNOWN }
    fun detect(file: File): Type = runCatching {
        RandomAccessFile(file,"r").use { input ->
            var equi = false;var mono = false;var stereo = false;var other = false;var boxes = 0
            fun walk(start: Long, end: Long, depth: Int) {
                require(depth <= 12)
                var pos = start
                while (pos+8 <= end) {
                    require(++boxes <= 50000)
                    input.seek(pos)
                    var size = input.readInt().toLong() and 0xffffffffL
                    val typeBytes = ByteArray(4);input.readFully(typeBytes)
                    val type = String(typeBytes,Charsets.US_ASCII)
                    var header = 8
                    if (size == 1L) { size = input.readLong();header = 16 }
                    if (size == 0L) size = end-pos
                    require(size >= header && size <= end-pos)
                    val payload = pos+header
                    when (type) {
                        "moov","trak","mdia","minf","stbl","sv3d","proj" -> walk(payload,pos+size,depth+1)
                        "stsd" -> if (size >= header+8) walk(payload+8,pos+size,depth+1)
                        "avc1","avc3","hvc1","hev1","vp09","av01","mp4v" -> if(size>=header+78)walk(payload+78,pos+size,depth+1)
                        "st3d" -> if(size>=header+5) { input.seek(payload+4);when(input.readUnsignedByte()){0 -> mono=true;1,2 -> stereo=true;else -> other=true} }
                        "equi" -> if(size>=header+20) { input.seek(payload+4);val bounds=IntArray(4){input.readInt()};if(bounds.all{it==0})equi=true else other=true }
                        "cbmp","mshp" -> other=true
                    }
                    pos += size
                }
            }
            walk(0,input.length(),0)
            when { stereo -> Type.STEREO;other -> Type.OTHER_PROJECTION;equi && mono -> Type.MONO_ERP;else -> Type.UNKNOWN }
        }
    }.getOrDefault(Type.UNKNOWN)
}

package com.panofusion.app

import android.graphics.*
import android.media.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VideoDecoderIntegrationTest {
    @Test fun realAvcStreamDecodesWithoutDuplicateTimestamps() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root=File(context.cacheDir,"codec-${System.nanoTime()}").apply { mkdirs() }
        val video=File(root,"input.mp4")
        val encoder=MediaCodec.createEncoderByType("video/avc")
        val muxer=MediaMuxer(video.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track=-1;var started=false
        try {
            val w=320;val h=240
            val format=MediaFormat.createVideoFormat("video/avc",w,h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE,600_000);setInteger(MediaFormat.KEY_FRAME_RATE,4);setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
            }
            encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);encoder.start()
            val info=MediaCodec.BufferInfo()
            fun drain(eos: Boolean) {
                var waits=0
                while (true) {
                    val index=encoder.dequeueOutputBuffer(info,10_000)
                    if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){track=muxer.addTrack(encoder.outputFormat);muxer.start();started=true}
                    else if(index>=0){
                        val buffer=encoder.getOutputBuffer(index)!!
                        if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0){check(started);buffer.position(info.offset);buffer.limit(info.offset+info.size);muxer.writeSampleData(track,buffer,info)}
                        val ended=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                        encoder.releaseOutputBuffer(index,false);if(ended)return
                    } else if(index==MediaCodec.INFO_TRY_AGAIN_LATER){if(!eos)return;check(++waits<500){"Encoder EOS timeout"}}
                }
            }
            for(frame in 0 until 12) {
                val index=encoder.dequeueInputBuffer(1_000_000);check(index>=0)
                val image=encoder.getInputImage(index) ?: error("Encoder does not expose flexible YUV input")
                for(planeId in 0..2){
                    val plane=image.planes[planeId];val pw=if(planeId==0)w else w/2;val ph=if(planeId==0)h else h/2
                    for(y in 0 until ph)for(x in 0 until pw){
                        // Moving checker texture supplies real encoded spatial detail.
                        val px=if(planeId==0)x else x*2;val py=if(planeId==0)y else y*2
                        val value=if(planeId==0)if(((px+frame*7)/12+py/12)%2==0)60 else 200 else 128
                        plane.buffer.put(y*plane.rowStride+x*plane.pixelStride,value.toByte())
                    }
                }
                image.close();encoder.queueInputBuffer(index,0,w*h*3/2,frame*250_000L,0);drain(false)
            }
            val end=encoder.dequeueInputBuffer(1_000_000);check(end>=0);encoder.queueInputBuffer(end,0,0,3_000_000,MediaCodec.BUFFER_FLAG_END_OF_STREAM);drain(true)
            encoder.stop();muxer.stop();started=false
            val timestamps=mutableListOf<Long>();val files=mutableListOf<File>()
            val meta=VideoFrameExtractor().extract(video.path,File(root,"frames")){ file,us,fraction ->
                timestamps+=us;files+=file;assertTrue(fraction in 0.0..1.0)
                assertTrue(NativePanorama.analyze(file.path,null)[0]>0)
            }
            assertEquals(w,meta.width);assertEquals(h,meta.height)
            assertTrue(timestamps.size in 5..6);assertEquals(timestamps.size,timestamps.distinct().size)
            assertTrue(timestamps.zipWithNext().all { it.second-it.first>=500_000 })
            assertTrue(files.all { BitmapFactory.decodeFile(it.path)?.let { bitmap -> val ok=bitmap.width==w;bitmap.recycle();ok }==true })
        } finally { runCatching { encoder.stop() };encoder.release();if(started)runCatching{muxer.stop()};muxer.release();root.deleteRecursively() }
    }
}

package com.panofusion.app

import android.graphics.*
import android.media.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.work.WorkManager
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class VideoDecoderIntegrationTest {
    @Test fun corruptVideoProducesReadableFailure() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val file=File(context.cacheDir,"corrupt-${System.nanoTime()}.mp4").apply{writeBytes(byteArrayOf())}
        val output=File(context.cacheDir,"corrupt-frames-${System.nanoTime()}")
        try {
            VideoFrameExtractor().extract(file.path,output){_,_,_-> fail("Corrupt video decoded")}
            fail("Corrupt input was accepted")
        } catch(e:IllegalStateException){assertTrue(e.message.orEmpty().contains("E_DECODE"))}
        finally{file.delete();output.deleteRecursively()}
    }
    @Test fun realAvcStreamDecodesWithoutDuplicateTimestamps() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root=File(context.cacheDir,"codec-${System.nanoTime()}").apply { mkdirs() }
        val video=File(root,"input.mp4")
        val encoder=MediaCodec.createEncoderByType("video/avc")
        val muxer=MediaMuxer(video.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track=-1;var started=false
        val app=context.applicationContext as PanoApplication
        var projectId:String?=null
        var scenario:ActivityScenario<MainActivity>?=null
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
                        // Known rotating pinhole camera over a deterministic textured sphere.
                        val px=if(planeId==0)x else x*2;val py=if(planeId==0)y else y*2
                        val yaw=frame*0.08
                        val rx=(px-w/2.0)/250.0;val ry=(py-h/2.0)/250.0
                        val wx=cos(yaw)*rx+sin(yaw);val wz=-sin(yaw)*rx+cos(yaw)
                        val u=floor((atan2(wx,wz)+PI)*1600/PI).toInt()/10
                        val v=floor((atan2(ry,sqrt(wx*wx+wz*wz))+PI/2)*1600/PI).toInt()/10
                        var hash=(u*73856093) xor (v*19349663) xor (17*83492791);hash=hash xor (hash ushr 13)
                        val r=30+(hash and 191);val g=30+((hash ushr 8) and 191);val b=30+((hash ushr 16) and 191)
                        val value=when(planeId){0 -> ((66*r+129*g+25*b+128) shr 8)+16;1 -> ((-38*r-74*g+112*b+128) shr 8)+128;else -> ((112*r-94*g-18*b+128) shr 8)+128}.coerceIn(0,255)
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
            scenario=ActivityScenario.launch(MainActivity::class.java)
            val project=app.store.create("视频端到端验证","360");projectId=project.id
            app.store.import(project.id,listOf(android.net.Uri.fromFile(video)))
            PanoramaWorker.enqueue(app,project.id)
            withTimeout(180_000) { WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow("panorama-${project.id}").first { infos -> infos.isNotEmpty() && infos.first().state.isFinished } }
            val completed=requireNotNull(app.store.dao.project(project.id))
            assertEquals(completed.error,"处理完成",completed.status)
            val selected=app.store.dao.frames(project.id).filter{it.selected && it.enabled}
            assertTrue(selected.size>=2)
            val panorama=File(app.store.directory(project.id),"history/revision-${completed.revision}")
            val coverage=requireNotNull(completed.coverage)
            val artifacts=File(context.getExternalFilesDir(null),"validation").apply{mkdirs()}
            File(panorama,"panorama.png").copyTo(File(artifacts,"video-panorama.png"),overwrite=true)
            File(panorama,"coverage.png").copyTo(File(artifacts,"video-coverage.png"),overwrite=true)
            video.copyTo(File(artifacts,"synthetic-rotation-input.mp4"),overwrite=true)
            assertTrue("Real decoded video frames must produce a partial spherical panorama",coverage>0 && coverage<50)
            assertTrue(File(panorama,"coverage.png").isFile)
            println("VIDEO_PIPELINE_RESULT decodedFrames=${files.size} coverage=$coverage videoBytes=${video.length()}")
            assertTrue(files.all { BitmapFactory.decodeFile(it.path)?.let { bitmap -> val ok=bitmap.width==w;bitmap.recycle();ok }==true })
        } finally {
            projectId?.let { WorkManager.getInstance(app).cancelUniqueWork("panorama-$it");app.store.delete(it) }
            scenario?.close()
            runCatching { encoder.stop() };encoder.release();if(started)runCatching{muxer.stop()};muxer.release();root.deleteRecursively()
        }
    }
}

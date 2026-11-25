package com.uhflogger.spacevis

import com.uhflogger.spacevis.SpacevisCommandChannel.Result
import com.uhflogger.spacevis.SpacevisProtocol as P
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SpacevisCommandChannelTest {

    @Test
    fun `resposta chega na primeira tentativa`() {
        val reader = FakeSpacevisReader()
        val r = reader.channel().send(P.start())
        assertTrue(r is Result.Response)
        assertEquals(P.ST_OK, (r as Result.Response).status)
        assertEquals(listOf(P.CMD_START), reader.received)
    }

    @Test
    fun `reenvia ate 3 vezes quando o frame se perde`() {
        val reader = FakeSpacevisReader().apply { dropFirst[P.CMD_GET_INFO] = 2 }
        val r = reader.channel().send(P.getInfo())
        assertTrue(r is Result.Response)
        assertEquals(3, reader.received.size)
    }

    @Test
    fun `sem resposta depois de 3 tentativas e FALHA`() {
        val reader = FakeSpacevisReader().apply { mute += P.CMD_GET_INFO }
        assertEquals(Result.NoResponse, reader.channel().send(P.getInfo()))
        assertEquals(3, reader.received.size)
    }

    @Test
    fun `erro de escrita e conexao perdida, sem reenvio`() {
        val reader = FakeSpacevisReader().apply { writeFails = true }
        assertTrue(reader.channel().send(P.getInfo()) is Result.WriteFailed)
    }

    @Test
    fun `resposta a outro comando e ignorada`() {
        // Resposta de GET_STATUS (sonda do watchdog) chegando durante o START.
        lateinit var ch: SpacevisCommandChannel
        ch = SpacevisCommandChannel(write = { ch.onResponse(0x88, ByteArray(9)) }, timeoutMs = 50, attempts = 1)
        assertEquals(Result.NoResponse, ch.send(P.start()))
    }

    @Test
    fun `close libera quem espera`() {
        val ch = SpacevisCommandChannel(write = {}, timeoutMs = 10_000, attempts = 3)
        val result = AtomicReference<Result>()
        val done = CountDownLatch(1)
        thread { result.set(ch.send(P.getInfo())); done.countDown() }
        Thread.sleep(50)
        ch.close()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(Result.NoResponse, result.get())
    }

    @Test
    fun `um comando por vez - STOP espera a sequencia terminar`() {
        val order = mutableListOf<String>()
        val reader = FakeSpacevisReader()
        val ch = reader.channel()
        val inSequence = CountDownLatch(1)
        val t = thread {
            ch.sequence {
                inSequence.countDown()
                Thread.sleep(100)
                ch.send(P.start()); synchronized(order) { order += "START" }
            }
        }
        inSequence.await()
        ch.send(P.stop()); synchronized(order) { order += "STOP" }
        t.join()
        assertEquals(listOf("START", "STOP"), order)
        assertEquals(listOf(P.CMD_START, P.CMD_STOP), reader.received)
    }
}

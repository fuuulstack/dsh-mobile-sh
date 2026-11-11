package com.dsh.mobile.v2.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 续传决策。三种响应的语义各不相同，而其中两种写错都不会在 happy path 上暴露：
 * 把 `200` 当 `206` 追加会拼出重复字节（看起来只是「内容重复了一遍」），
 * 把 `416` 一律当失败会让「刚好取完最后一段」变成永久补不齐。
 */
class BlobPlanTest {

    @Test
    fun `a full 200 body finishes immediately`() {
        assertEquals(BlobPlan.Step.Complete, BlobPlan.onChunk(status = 200, chunk = 1024, offset = 0))
    }

    @Test
    fun `a 200 after a partial read replaces instead of appending`() {
        // The body we just got is the *whole* thing even though we had 500 bytes
        // already; appending would duplicate the first 500 bytes.
        assertEquals(BlobPlan.Step.Complete, BlobPlan.onChunk(status = 200, chunk = 1024, offset = 500))
    }

    @Test
    fun `a 206 slice advances the offset by exactly the bytes received`() {
        val step = BlobPlan.onChunk(status = 206, chunk = 300, offset = 700)
        assertTrue(step is BlobPlan.Step.Again)
        assertEquals(1000, (step as BlobPlan.Step.Again).offset)
        assertEquals(false, step.replace)
    }

    @Test
    fun `an empty 206 is the end, not an invitation to ask again`() {
        // Asking again would return the same empty 206 forever.
        assertEquals(BlobPlan.Step.Complete, BlobPlan.onChunk(status = 206, chunk = 0, offset = 4096))
    }

    @Test
    fun `exceeding the hard ceiling fails loudly instead of growing the buffer`() {
        val step = BlobPlan.onChunk(status = 206, chunk = 2048, offset = BlobPlan.LIMIT - 1024)
        assertEquals(BlobPlan.Step.Fail("too-large"), step)
    }

    @Test
    fun `a 200 that exceeds the ceiling also fails`() {
        assertEquals(BlobPlan.Step.Fail("too-large"), BlobPlan.onChunk(status = 200, chunk = BlobPlan.LIMIT + 1, offset = 0))
    }

    @Test
    fun `416 after a partial read means we already hold everything`() {
        // This is the normal ending when the last slice ended exactly at the end.
        assertEquals(BlobPlan.Step.Complete, BlobPlan.onFailure(status = 416, offset = 8192, attempt = 0, error = "416"))
    }

    @Test
    fun `416 before any byte is a real refusal, not a completion`() {
        // Nothing read and the server says the range is unsatisfiable: the byte math
        // is wrong or the blob is empty. Retrying cannot help.
        assertEquals(BlobPlan.Step.Fail("range-rejected"), BlobPlan.onFailure(status = 416, offset = 0, attempt = 0, error = "416"))
    }

    @Test
    fun `a transient error retries from the same offset, not from zero`() {
        val step = BlobPlan.onFailure(status = 0, offset = 5000, attempt = 0, error = "connection-failed")
        assertTrue(step is BlobPlan.Step.Again)
        assertEquals(5000, (step as BlobPlan.Step.Again).offset)
        assertEquals(1, step.attempt)
    }

    @Test
    fun `retries stop at the attempt ceiling and report the last error`() {
        val step = BlobPlan.onFailure(
            status = 0, offset = 5000, attempt = BlobPlan.MAX_ATTEMPTS - 1,
            error = "connection-failed", maxAttempts = BlobPlan.MAX_ATTEMPTS,
        )
        assertEquals(BlobPlan.Step.Fail("connection-failed"), step)
    }

    @Test
    fun `a missing blob is reported rather than retried until the ceiling`() {
        val step = BlobPlan.onFailure(status = 404, offset = 0, attempt = 0, error = "404")
        assertEquals(BlobPlan.Step.Again(offset = 0, replace = false, attempt = 1), step)
    }
}

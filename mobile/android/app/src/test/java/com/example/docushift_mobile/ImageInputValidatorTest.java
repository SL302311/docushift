package com.example.docushift_mobile;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * ImageInputValidator / ImageToPdfConverter 纯逻辑单元测试（JUnit 4）。
 *
 * 覆盖：采样边界、错误码提取、CountingOutputStream、损坏图片门禁。
 */
public class ImageInputValidatorTest {

    // ================================================================
    // calculateSampleSize 边界测试
    // ================================================================

    @Test
    public void sampleSize_under4096_returns1() {
        assertEquals(1, ImageInputValidator.Companion.calculateSampleSize(100, 100));
        assertEquals(1, ImageInputValidator.Companion.calculateSampleSize(2048, 2048));
        assertEquals(1, ImageInputValidator.Companion.calculateSampleSize(4096, 4096));
    }

    @Test
    public void sampleSize_4097to8192_returns2() {
        assertEquals(2, ImageInputValidator.Companion.calculateSampleSize(4097, 100));
        assertEquals(2, ImageInputValidator.Companion.calculateSampleSize(5000, 5000));
        assertEquals(2, ImageInputValidator.Companion.calculateSampleSize(8192, 8192));
    }

    @Test
    public void sampleSize_8193_returns4() {
        // 修复前：8193/2=4096(截断) >4096=false → sample=2
        // 修复后：8193 > 4096*2=8192 → true → sample=4
        assertEquals(4, ImageInputValidator.Companion.calculateSampleSize(8193, 100));
        assertEquals(4, ImageInputValidator.Companion.calculateSampleSize(10000, 8000));
    }

    @Test
    public void sampleSize_large_returnsPowerOf2() {
        assertEquals(8, ImageInputValidator.Companion.calculateSampleSize(32768, 32768));
        assertEquals(16, ImageInputValidator.Companion.calculateSampleSize(65536, 65536));
    }

    @Test
    public void sampleSize_usesLongestSide() {
        assertEquals(1, ImageInputValidator.Companion.calculateSampleSize(800, 4096));
        assertEquals(4, ImageInputValidator.Companion.calculateSampleSize(800, 8193));
    }

    @Test
    public void sampleSize_alwaysPowerOf2() {
        int[] dims = {100, 2000, 4096, 5000, 8192, 8193, 10000, 50000};
        for (int w : dims) {
            for (int h : dims) {
                int s = ImageInputValidator.Companion.calculateSampleSize(w, h);
                assertTrue("sampleSize=" + s + " 应是2的幂", (s & (s - 1)) == 0);
            }
        }
    }

    // ================================================================
    // 错误码提取测试
    // ================================================================

    @Test
    public void extractErrorCode_knownPrefixes() {
        assertEquals("DECODE_FAILED",
            ImageToPdfCoordinator.Companion.extractErrorCode("DECODE_FAILED: 损坏图片"));
        assertEquals("WRITE_FAILED",
            ImageToPdfCoordinator.Companion.extractErrorCode("WRITE_FAILED: 无法写入"));
        assertEquals("UNSUPPORTED_FORMAT",
            ImageToPdfCoordinator.Companion.extractErrorCode("UNSUPPORTED_FORMAT: 不支持的格式"));
        assertEquals("IMAGE_TOO_LARGE",
            ImageToPdfCoordinator.Companion.extractErrorCode("IMAGE_TOO_LARGE: 像素超限"));
        assertEquals("FILE_TOO_LARGE",
            ImageToPdfCoordinator.Companion.extractErrorCode("FILE_TOO_LARGE: 文件超限"));
        assertEquals("FILE_SIZE_UNKNOWN",
            ImageToPdfCoordinator.Companion.extractErrorCode("FILE_SIZE_UNKNOWN: 未知大小"));
    }

    @Test
    public void extractErrorCode_unknown_returnsGeneric() {
        assertEquals("CONVERSION_FAILED",
            ImageToPdfCoordinator.Companion.extractErrorCode("未知错误"));
        assertEquals("CONVERSION_FAILED",
            ImageToPdfCoordinator.Companion.extractErrorCode(""));
    }

    // ================================================================
    // CountingOutputStream 测试
    // ================================================================

    @Test
    public void countingOutputStream_countsBytes() throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        ImageToPdfConverter.CountingOutputStream cos =
            new ImageToPdfConverter.CountingOutputStream(baos);

        cos.write(65);               // 'A'
        cos.write(new byte[]{66, 67, 68});  // 'BCD'
        cos.write("Hello".getBytes("UTF-8"), 0, 5);
        cos.close();

        assertEquals(9, cos.getCount());
        assertEquals("ABCDHello", baos.toString("UTF-8"));
    }

    @Test
    public void countingOutputStream_empty_returns0() throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        ImageToPdfConverter.CountingOutputStream cos =
            new ImageToPdfConverter.CountingOutputStream(baos);
        cos.close();
        assertEquals(0, cos.getCount());
    }

    // ================================================================
    // CompletionGuard — single-flight / 恰好完成一次
    // ================================================================

    @Test
    public void completionGuard_exactlyOnce_singleThread() {
        CompletionGuard guard = new CompletionGuard();
        final int[] calls = {0};
        assertTrue(guard.complete(() -> { calls[0]++; }));
        assertFalse(guard.complete(() -> { calls[0]++; }));
        assertFalse(guard.complete(() -> { calls[0]++; }));
        assertEquals(1, calls[0]);
        assertTrue(guard.isCompleted());
    }

    @Test
    public void completionGuard_exactlyOnce_underConcurrency() throws Exception {
        final CompletionGuard guard = new CompletionGuard();
        final java.util.concurrent.atomic.AtomicInteger calls =
            new java.util.concurrent.atomic.AtomicInteger(0);

        int threads = 16;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                guard.complete(() -> { calls.incrementAndGet(); });
                done.countDown();
            }).start();
        }
        start.countDown();
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
        // 无论多少线程竞争，action 只执行一次（对应 single-flight）
        assertEquals(1, calls.get());
    }

    @Test
    public void completionGuard_destroyVsComplete_race() {
        // 模拟 onDestroy 与后台线程完成竞争：只有一个胜出
        final CompletionGuard guard = new CompletionGuard();
        final String[] winner = {null};
        final java.util.concurrent.atomic.AtomicBoolean destroyed =
            new java.util.concurrent.atomic.AtomicBoolean(false);

        // 后台线程完成
        Thread bg = new Thread(() -> {
            guard.complete(() -> { winner[0] = "SUCCESS"; });
        });
        // onDestroy 取消
        Thread destroy = new Thread(() -> {
            guard.complete(() -> {
                destroyed.set(true);
                winner[0] = "DESTROYED";
            });
        });

        bg.start();
        destroy.start();
        try {
            bg.join(2000);
            destroy.join(2000);
        } catch (InterruptedException ignored) {}

        // 恰好一个胜出
        assertTrue(guard.isCompleted());
        assertNotNull(winner[0]);
        // 不变量：最终结果只能是二者之一且只发生一次
        assertTrue(winner[0].equals("SUCCESS") || winner[0].equals("DESTROYED"));
    }
}

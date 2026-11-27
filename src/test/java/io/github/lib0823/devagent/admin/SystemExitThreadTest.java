package io.github.lib0823.devagent.admin;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TC-028: 종료 스레드는 요청(데몬) 스레드에서 만들어도 비데몬이어야 JVM 이 System.exit 보다 먼저 끝나지 않는다. */
class SystemExitThreadTest {

    @Test
    void tc028_exitThreadIsNonDaemonEvenWhenCreatedFromDaemonThread() throws Exception {
        AtomicReference<Thread> created = new AtomicReference<>();
        Thread requestLike = new Thread(() -> created.set(SystemController.newExitThread(0, 10, () -> 0, c -> { })), "req");
        requestLike.setDaemon(true);
        requestLike.start();
        requestLike.join();

        Thread t = created.get();
        assertFalse(t.isDaemon());
        assertEquals("system-exit", t.getName());
    }

    @Test
    void tc028_restartAndShutdownPassTheirCodesToExit() throws Exception {
        for (int code : new int[]{10, 0}) {
            AtomicInteger exited = new AtomicInteger(-1);
            Thread t = SystemController.newExitThread(0, code, () -> code, exited::set);
            t.start();
            t.join(5000);
            assertEquals(code, exited.get());
        }
    }

    @Test
    void tc028_exiterIsCalledWithRequestedCodeEvenWhenCloseContextThrowsError() throws Exception {
        for (int code : new int[]{10, 0}) {
            AtomicInteger exited = new AtomicInteger(-1);
            Thread t = SystemController.newExitThread(0, code, () -> {
                throw new NoClassDefFoundError("simulated");
            }, exited::set);
            t.start();
            t.join(5000);
            assertEquals(code, exited.get());
        }
    }

    @Test
    void tc028_onlyFirstExitRequestClaimsTheExitThread() {
        AtomicBoolean flag = new AtomicBoolean();
        assertTrue(SystemController.claimExit(flag));
        assertFalse(SystemController.claimExit(flag));
        assertFalse(SystemController.claimExit(flag));
    }
}

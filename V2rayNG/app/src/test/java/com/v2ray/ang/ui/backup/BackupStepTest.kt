package com.v2ray.ang.ui.backup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class BackupStepTest {

    @Test
    fun thirdWrongPasswordEndsTheAttempt() {
        val start = BackupStep.RestorePassword(File("backup.rxbk"))
        val first = afterWrongPassword(start)
        assertEquals(BackupStep.RestorePassword(File("backup.rxbk"), wrongCount = 1, wrongTick = 1), first)
        val second = afterWrongPassword(first as BackupStep.RestorePassword)
        assertEquals(2, (second as BackupStep.RestorePassword).wrongCount)
        assertTrue(afterWrongPassword(second) is BackupStep.RestoreGaveUp)
    }
}

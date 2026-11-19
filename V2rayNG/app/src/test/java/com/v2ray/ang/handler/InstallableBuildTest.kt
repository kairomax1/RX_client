package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * An APK that a phone with RX VPN on it already will not take is not a build, it is
 * a download. These are the two facts that decide whether it will be taken, asserted
 * against the files that set them rather than against a green workflow.
 */
class InstallableBuildTest {

    private fun read(path: String) = java.io.File(path).readText()

    /**
     * The version number must be able to move.
     *
     * A phone replaces an installed app only when the new file's versionCode is
     * higher than the installed one. It was frozen at 4 for every build this project
     * ever shipped, so an installed copy could never be updated — the update was
     * downloaded, handed to the installer, and refused without a word about why.
     */
    @Test
    fun theVersionNumberIsNotFrozen() {
        val gradle = read("build.gradle.kts")
        assertTrue(
            gradle.contains("rx.versionCode"),
            "versionCode must come from the build, not from a constant",
        )
    }

    /** And the build that ships must have been told a number, and be checked against it. */
    @Test
    fun theWorkflowPassesTheNumberAndRefusesAFileWithoutOne() {
        val workflow = read("../../.github/workflows/build.yml")
        assertTrue(workflow.contains("-Prx.versionCode="), "the run's number must reach gradle")
        assertTrue(workflow.contains("Refuse an APK that a phone cannot update"), "a frozen number must fail the build")
        assertTrue(workflow.contains("4000004"), "the floor is the highest number any earlier build carries")
    }

    /** The installed copy's signature has to be readable in the log when an install fails. */
    @Test
    fun theSignatureOfTheInstalledCopyIsRecorded() {
        val installer = read("src/main/java/com/v2ray/ang/handler/ApkInstaller.kt")
        assertTrue(installer.contains("fun logInstalledSignature"))
        assertTrue(installer.contains("logInstalledSignature(context)"), "it must be called before the hand-off")
    }
}

package com.inyogeshwar.agentmt

import com.inyogeshwar.agentmt.core.linux.LinuxEnv
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class GuestCwdTest {
    private val root = File("/data/user/0/com.inyogeshwar.agentmt/files/linux/ubuntu")

    @Test fun hostRootIsNeverBound() = assertEquals("/root" to null, LinuxEnv.guestCwd(root, File("/")))
    @Test fun nullIsHome() = assertEquals("/root" to null, LinuxEnv.guestCwd(root, null))
    @Test fun insideRootfsIsTranslated() {
        assertEquals("/root" to null, LinuxEnv.guestCwd(root, File(root, "root")))
        assertEquals("/" to null, LinuxEnv.guestCwd(root, root))
    }
    @Test fun parentOfRootfsIsHome() = assertEquals("/root" to null, LinuxEnv.guestCwd(root, File("/data/user/0/com.inyogeshwar.agentmt/files")))
    @Test fun sharedStorageNotRebound() = assertEquals("/storage/emulated/0/MT2" to null, LinuxEnv.guestCwd(root, File("/storage/emulated/0/MT2")))
    @Test fun projectFolderIsBound() {
        val p = File("/data/user/0/com.inyogeshwar.agentmt/files/workspace/app")
        assertEquals(p.path to p, LinuxEnv.guestCwd(root, p))
    }
}

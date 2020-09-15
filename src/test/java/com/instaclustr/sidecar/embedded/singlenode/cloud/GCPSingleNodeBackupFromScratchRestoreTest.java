package com.instaclustr.sidecar.embedded.singlenode.cloud;

import com.instaclustr.sidecar.embedded.singlenode.AbstractSingleNodeBackupFromScratchRestoreTest;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Test(groups = {
        "googleTest",
        "cloudTest",
})
public class GCPSingleNodeBackupFromScratchRestoreTest extends AbstractSingleNodeBackupFromScratchRestoreTest {

    @Test
    public void backupTest() throws Exception {
        backupTest("gcp");
    }

    @BeforeClass
    public void beforeClass() throws Exception {
        super.beforeClass();
    }

    @AfterClass
    public void afterClass() throws Exception {
        super.afterClass();
    }

    @BeforeMethod
    public void beforeMethod() {
        super.beforeMethod();
    }

    @AfterMethod
    public void afterMethod() {
        super.afterMethod();
    }
}

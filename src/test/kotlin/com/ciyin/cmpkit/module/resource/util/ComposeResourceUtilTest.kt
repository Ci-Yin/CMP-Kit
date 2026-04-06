package com.ciyin.cmpkit.module.resource.util

import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ComposeResourceUtilTest : BasePlatformTestCase() {

    fun testResolveStringValue_prefersValuesOverQualified() {
        val root = myFixture.tempDirFixture.findOrCreateDir(".")

        val values = myFixture.tempDirFixture.createFile(
            "src/commonMain/composeResources/values/strings.xml",
            """
                <resources>
                    <string name="app_name">Default</string>
                </resources>
            """.trimIndent()
        )
        val valuesZh = myFixture.tempDirFixture.createFile(
            "src/commonMain/composeResources/values-zh/strings.xml",
            """
                <resources>
                    <string name="app_name">中文</string>
                </resources>
            """.trimIndent()
        )

        // Ensure PSI is created (sanity).
        PsiManager.getInstance(project).findFile(values)
        PsiManager.getInstance(project).findFile(valuesZh)

        assertEquals("Default", project.getResStringValueOrNull("app_name"))
    }

    fun testResolveStringValue_ignoresNonResourcesRoot() {
        val root = myFixture.tempDirFixture.findOrCreateDir(".")

        myFixture.tempDirFixture.createFile(
            "src/commonMain/composeResources/values/strings.xml",
            """
                <not_resources>
                    <string name="app_name">Default</string>
                </not_resources>
            """.trimIndent()
        )

        assertNull(project.getResStringValueOrNull("app_name"))
    }
}

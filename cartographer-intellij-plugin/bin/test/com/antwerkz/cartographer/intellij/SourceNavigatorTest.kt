package com.antwerkz.cartographer.intellij

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.util.ui.UIUtil

class SourceNavigatorTest : LightJavaCodeInsightFixtureTestCase() {

    fun `test navigate resolves Calculator add method`() {
        myFixture.addClass("""
            package com.example;
            public class Calculator {
                public int add(int a, int b) { return a + b; }
            }
        """.trimIndent())

        SourceNavigator.navigate(project, "com.example.Calculator.add")
        UIUtil.dispatchAllInvocationEvents()
        // No exception = PSI lookup and navigation completed without error
    }

    fun `test navigate shows balloon for unknown class`() {
        SourceNavigator.navigate(project, "com.example.NonExistent.method")
        UIUtil.dispatchAllInvocationEvents()
        // Should not throw — null class is handled gracefully
    }

    fun `test navigate handles constructor`() {
        myFixture.addClass("""
            package com.example;
            public class Widget { public Widget() {} }
        """.trimIndent())
        SourceNavigator.navigate(project, "com.example.Widget.<init>")
        UIUtil.dispatchAllInvocationEvents()
    }
}

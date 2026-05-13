package com.antwerkz.surveyor.intellij

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class SourceNavigatorTest : LightJavaCodeInsightFixtureTestCase() {

    fun `test navigate resolves Calculator add method`() {
        myFixture.addClass("""
            package com.example;
            public class Calculator {
                public int add(int a, int b) { return a + b; }
            }
        """.trimIndent())

        // navigate(true) opens an editor — just verify no exception is thrown
        SourceNavigator.navigate(project, "com.example.Calculator.add")
    }

    fun `test navigate shows balloon for unknown class`() {
        // Should not throw — missing class falls back to HintManager balloon
        SourceNavigator.navigate(project, "com.example.NonExistent.method")
    }

    fun `test navigate handles constructor`() {
        myFixture.addClass("""
            package com.example;
            public class Widget { public Widget() {} }
        """.trimIndent())
        SourceNavigator.navigate(project, "com.example.Widget.<init>")
    }
}

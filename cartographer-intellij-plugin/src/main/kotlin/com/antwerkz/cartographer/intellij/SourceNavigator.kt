package com.antwerkz.cartographer.intellij

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.pom.Navigatable
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope

object SourceNavigator {

    fun navigate(project: Project, spanName: String) {
        val lastDot = spanName.lastIndexOf('.')
        if (lastDot < 0) return
        val className = spanName.substring(0, lastDot)
        val methodName = spanName.substring(lastDot + 1)
        if (methodName.isEmpty()) return

        ApplicationManager.getApplication().invokeLater {
            val target: PsiElement? = ReadAction.compute<PsiElement?, Throwable> {
                val psiFacade = JavaPsiFacade.getInstance(project)
                val psiClass = psiFacade.findClass(className, GlobalSearchScope.allScope(project))
                    ?: return@compute null
                if (methodName == "<init>") {
                    psiClass.constructors.firstOrNull() ?: psiClass
                } else {
                    psiClass.findMethodsByName(methodName, true).firstOrNull() ?: psiClass
                }
            }

            if (target == null) {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                if (editor != null) {
                    HintManager.getInstance().showErrorHint(editor, "Source not available for $spanName")
                }
                return@invokeLater
            }

            if (target.isValid) (target as? Navigatable)?.navigate(true)
        }
    }
}

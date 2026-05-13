package com.antwerkz.surveyor.intellij

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

object SourceNavigator {

    fun navigate(project: Project, spanName: String) {
        val lastDot = spanName.lastIndexOf('.')
        if (lastDot < 0) return
        val className = spanName.substring(0, lastDot)
        val methodName = spanName.substring(lastDot + 1)

        ApplicationManager.getApplication().invokeLater {
            val psiFacade = JavaPsiFacade.getInstance(project)
            val psiClass = psiFacade.findClass(className, GlobalSearchScope.allScope(project))

            if (psiClass == null) {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                if (editor != null) {
                    HintManager.getInstance().showErrorHint(editor, "Source not available for $spanName")
                }
                return@invokeLater
            }

            val target = if (methodName == "<init>") {
                psiClass.constructors.firstOrNull() ?: psiClass
            } else {
                psiClass.findMethodsByName(methodName, true).firstOrNull() ?: psiClass
            }

            target.navigate(true)
        }
    }
}

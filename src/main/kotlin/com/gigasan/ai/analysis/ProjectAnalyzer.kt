package com.gigasan.ai.analysis

import com.intellij.psi.PsiFile

interface ProjectAnalyzer {
    fun analyzePsiFile(psiFile: PsiFile, deep: Boolean): String
    fun psiFileToMemberChooserList(psiFile: PsiFile): List<UniversalMember>
}
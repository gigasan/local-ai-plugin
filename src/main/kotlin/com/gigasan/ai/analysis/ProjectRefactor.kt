package com.gigasan.ai.analysis

import com.intellij.codeInsight.generation.MemberChooserObject
import com.intellij.psi.PsiFile

interface ProjectRefactor {
    fun psiFileToMemberChooserList(header: MemberChooserObject, psiFile: PsiFile): List<UniversalMember>
    fun rawFileToMemberChooserList(header: MemberChooserObject, psiFile: PsiFile): List<UniversalMember>
}
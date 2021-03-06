/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.structure

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase
import org.rust.lang.core.psi.RsImplItem
import org.rust.lang.core.psi.RsTypeReference

class RsImplTreeElement(element: RsImplItem) : PsiTreeElementBase<RsImplItem>(element) {

    override fun getPresentableText(): String? {
        val type: RsTypeReference = element?.typeReference ?: return "<unknown>"

        val traitRef = element?.traitRef ?: return type.text

        return "${traitRef.text} for ${type.text}"
    }

    override fun getChildrenBase(): Collection<StructureViewTreeElement> {
        val members = element?.members ?: return emptyList()
        return listOf(
            members.functionList.map(::RsFunctionTreeElement),
            members.constantList.map(::RsBaseTreeElement)
        ).flatten().sortedBy { it.element?.textOffset }
    }
}

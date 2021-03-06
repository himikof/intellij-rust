/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.runconfig.test

import com.intellij.execution.PsiLocation
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.util.Function
import org.rust.cargo.runconfig.getExecutorActions
import org.rust.lang.core.psi.RsElementTypes.IDENTIFIER
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.RsModItem
import org.rust.lang.core.psi.ext.*

class CargoTestRunLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        if (element.elementType != IDENTIFIER) return null
        val parent = element.parent
        val location = PsiLocation(parent.project, parent.module, parent)
        val state = when (parent) {
            is RsMod -> CargoTestRunConfigurationProducer.findTestMod(location)
            is RsFunction -> CargoTestRunConfigurationProducer.findTestFunction(location)
            else -> null
        } ?: return null
        return Info(
            AllIcons.RunConfigurations.TestState.Run,
            Function<PsiElement, String> { state.configurationName },
            // `1` here will prefer test configuration over application configuration,
            // when both a applicable. Usually configurations are ordered by their target
            // PSI elements (smaller element means more specific), but this is not the case here.
            *getExecutorActions(1)
        )
    }
}

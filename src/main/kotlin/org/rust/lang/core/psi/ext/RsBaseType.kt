/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi.ext

import org.rust.lang.core.psi.RsBaseType

val RsBaseType.isCself: Boolean get() {
    val path = path
    return path != null && !path.hasColonColon && path.hasCself
}

val RsBaseType.isUnit: Boolean get() {
    val stub = stub
    return (stub?.isUnit) ?: (lparen != null && rparen != null)
}

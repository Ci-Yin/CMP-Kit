package com.ciyin.cmpkit

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * 判断当前文件是否处于 `build/` 目录下（用于过滤生成物与缓存）。
 */
internal fun VirtualFile.isInsideBuildDir(): Boolean {
    var cur: VirtualFile? = this
    while (cur != null) {
        if (cur.isDirectory && cur.name == "build") return true
        cur = cur.parent
    }
    return false
}

/**
 * 判断当前文件是否位于 `composeResources/` 目录下。
 *
 * 该插件只对 Compose Multiplatform 的资源源文件生效，避免误命中其他 `strings.xml`。
 */
internal fun VirtualFile.isInsideComposeResources(): Boolean {
    var cur: VirtualFile? = this
    while (cur != null) {
        if (cur.isDirectory && cur.name == "composeResources") return true
        cur = cur.parent
    }
    return false
}

/**
 * 从表达式中提取 `Res.string.<name>` 的 `<name>`。
 *
 * @return 若表达式形状不匹配（例如不是 `Res.string.xxx`）则返回 null
 */
internal fun extractStringKey(expr: KtDotQualifiedExpression): String? {
    val keyRef = expr.selectorExpression as? KtNameReferenceExpression ?: return null
    val innerDot = expr.receiverExpression as? KtDotQualifiedExpression ?: return null
    val typeRef = innerDot.selectorExpression as? KtNameReferenceExpression ?: return null
    if (typeRef.getReferencedName() != "string") return null
    val resRef = innerDot.receiverExpression as? KtNameReferenceExpression ?: return null
    if (resRef.getReferencedName() != "Res") return null
    return keyRef.getReferencedName()
}

/**
 * 将 `Res.string.<key>` 解析为实际显示文本。
 *
 * 解析策略：
 * - 在整个项目范围查找 `strings.xml`
 * - 只考虑 `composeResources/` 下的文件，并排除 `build/` 目录
 * - 优先使用默认语言目录 `values/`，若不存在再尝试 `values-xx/` 等限定目录
 *
 * @return 找不到 key 或值为空时返回 null
 */
internal fun Project.resolveStringValue(key: String): String? {
    val scope = GlobalSearchScope.projectScope(this)
    val files = FilenameIndex.getVirtualFilesByName("strings.xml", scope)
        .filter { !it.isInsideBuildDir() && it.isInsideComposeResources() }

    val default = files.filter { it.parent?.name == "values" }
    val qualified = files.filter { it.parent?.name != "values" }

    for (vf in default + qualified) {
        val xml = PsiManager.getInstance(this).findFile(vf) as? XmlFile ?: continue
        val tag = xml.rootTag?.subTags
            ?.firstOrNull { it.name == "string" && it.getAttributeValue("name") == key }
            ?: continue
        val text = tag.value.trimmedText
        if (text.isNotEmpty()) return text
    }
    return null
}

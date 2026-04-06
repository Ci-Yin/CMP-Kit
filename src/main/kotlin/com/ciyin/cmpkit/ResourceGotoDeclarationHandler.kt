package com.ciyin.cmpkit

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * 为 `Res.<type>.<key>` 提供“转到声明”（Ctrl/Cmd+点击）能力。
 *
 * 目标：
 * - 对 `Res.string.key`：跳转到 `composeResources/**/strings.xml` 中对应的 `<string name="key">`
 * - 对 `Res.drawable.name` / `Res.font.name`：跳转到 `composeResources/**/<type*>/name.<ext>` 的资源文件
 *
 * 说明：会排除 `build/` 目录下的生成物，只在 `composeResources/` 内查找。
 */
class ResourceGotoDeclarationHandler : GotoDeclarationHandler {

    /**
     * 当用户在编辑器里触发“转到声明”时被调用。
     *
     * @return 目标 PSI 列表；返回 null 表示当前不是本处理器关心的表达式
     */
    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor
    ): Array<PsiElement>? {
        // sourceElement is the leaf node; its parent is the KtNameReferenceExpression (the key)
        val nameRef = sourceElement?.parent as? KtNameReferenceExpression ?: return null

        // Must be the selector (right side) of a dot expression: <receiver>.someKey
        val selectorExpr = nameRef.parent as? KtDotQualifiedExpression ?: return null
        if (selectorExpr.selectorExpression !== nameRef) return null

        // Receiver must also be a dot expression: Res.string
        val receiverExpr = selectorExpr.receiverExpression as? KtDotQualifiedExpression
            ?: return null

        // Left side must be "Res"
        val resRef = receiverExpr.receiverExpression as? KtNameReferenceExpression ?: return null
        if (resRef.getReferencedName() != "Res") return null

        // Middle is the resource type: "string", "drawable", "font", etc.
        val typeRef = receiverExpr.selectorExpression as? KtNameReferenceExpression ?: return null
        val resourceType = typeRef.getReferencedName()
        val resourceKey = nameRef.getReferencedName()

        return when (resourceType) {
            "string"   -> findStringResource(sourceElement.project, resourceKey)
            "drawable" -> findFileResource(sourceElement.project, resourceKey, "drawable")
            "font"     -> findFileResource(sourceElement.project, resourceKey, "font")
            else       -> null
        }
    }

    /**
     * 在项目内查找所有匹配的 `<string name="key">` 标签并返回。
     *
     * 说明：
     * - 会遍历所有 `composeResources/**/strings.xml`（包含 `values/` 与 `values-xx/`）
     * - 会排除 `build/` 目录
     */
    private fun findStringResource(project: Project, key: String): Array<PsiElement>? {
        val results = mutableListOf<PsiElement>()
        val scope = GlobalSearchScope.projectScope(project)

        for (vf in FilenameIndex.getVirtualFilesByName("strings.xml", scope)) {
            if (vf.isInsideBuildDir() || !vf.isInsideComposeResources()) continue
            val xml = PsiManager.getInstance(project).findFile(vf) as? XmlFile ?: continue
            xml.rootTag?.subTags
                ?.filter { it.name == "string" && it.getAttributeValue("name") == key }
                ?.forEach { results.add(it) }
        }

        return results.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    /**
     * 按文件名在资源目录中定位资源文件（不包含扩展名）。
     *
     * 匹配规则：
     * - 目录名需要是 `baseType` 或以 `baseType-` 开头（例如 `drawable/`、`drawable-night/`、`drawable-hdpi/`）
     * - 仅在 `composeResources/` 内查找，并排除 `build/`
     *
     * @param baseType 资源基础类型，例如 `drawable`、`font`
     */
    private fun findFileResource(
        project: Project,
        key: String,
        baseType: String
    ): Array<PsiElement>? {
        val extensions = when (baseType) {
            "drawable" -> listOf("xml", "png", "jpg", "jpeg", "webp", "svg")
            "font" -> listOf("ttf", "otf")
            else -> emptyList()
        }

        val results = mutableListOf<PsiElement>()
        val scope = GlobalSearchScope.projectScope(project)

        for (ext in extensions) {
            for (vf in FilenameIndex.getVirtualFilesByName("$key.$ext", scope)) {
                if (vf.isInsideBuildDir() || !vf.isInsideComposeResources()) continue
                // Accept "drawable", "drawable-dark", "drawable-night", "drawable-hdpi", etc.
                val parentName = vf.parent?.name ?: continue
                if (parentName != baseType && !parentName.startsWith("$baseType-")) continue
                PsiManager.getInstance(project).findFile(vf)?.let { results.add(it) }
            }
        }

        return results.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

}

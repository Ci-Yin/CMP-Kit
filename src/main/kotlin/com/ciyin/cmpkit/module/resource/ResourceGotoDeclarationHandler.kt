package com.ciyin.cmpkit.module.resource

import com.ciyin.cmpkit.module.resource.util.isUnderBuildDir
import com.ciyin.cmpkit.module.resource.util.isUnderComposeResources
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import java.util.concurrent.ConcurrentHashMap

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

    private val stringsXmlCachedValueKey: Key<CachedValue<List<VirtualFile>>> =
        Key.create("CMPKIT_STRINGS_XML_CACHED_VALUE")

    private val composeFilenameIndexCachedValueKey: Key<CachedValue<ConcurrentHashMap<String, List<VirtualFile>>>> =
        Key.create("CMPKIT_COMPOSE_FILENAME_INDEX_CACHED_VALUE")

    private data class ResAccess(val type: String, val key: String)

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
        val access = findResAccess(sourceElement) ?: return null
        val resourceType = access.type
        val resourceKey = access.key

        return when (resourceType) {
            "string" -> findStringResource(editor.project ?: sourceElement?.project, resourceKey)
            "drawable" -> findFileResource(editor.project ?: sourceElement?.project, resourceKey, "drawable")
            "font" -> findFileResource(editor.project ?: sourceElement?.project, resourceKey, "font")
            else       -> null
        }
    }

    /**
     * 尝试从当前位置 PSI 向上解析出 `Res.<type>.<key>` 的访问结构。
     */
    private fun findResAccess(sourceElement: PsiElement?): ResAccess? {
        val anyDot = PsiTreeUtil.getParentOfType(sourceElement, KtDotQualifiedExpression::class.java, false) ?: return null
        val top = anyDot.topmostDotQualifiedExpression()

        val keyRef = top.selectorExpression as? KtNameReferenceExpression ?: return null
        val inner = top.receiverExpression as? KtDotQualifiedExpression ?: return null
        val typeRef = inner.selectorExpression as? KtNameReferenceExpression ?: return null
        val resRef = inner.receiverExpression as? KtNameReferenceExpression ?: return null
        if (resRef.getReferencedName() != "Res") return null

        return ResAccess(type = typeRef.getReferencedName(), key = keyRef.getReferencedName())
    }

    /**
     * 获取最外层的点表达式（用于在任意子节点上点击时仍能识别完整 `Res.<type>.<key>`）。
     */
    private fun KtDotQualifiedExpression.topmostDotQualifiedExpression(): KtDotQualifiedExpression {
        var cur: KtDotQualifiedExpression = this
        while (true) {
            val parent = cur.parent as? KtDotQualifiedExpression ?: return cur
            if (parent.receiverExpression !== cur) return cur
            cur = parent
        }
    }

    /**
     * 在项目内查找所有匹配的 `<string name="key">` 标签并返回。
     *
     * 说明：
     * - 会遍历所有 `composeResources/**/strings.xml`（包含 `values/` 与 `values-xx/`）
     * - 会排除 `build/` 目录
     */
    private fun findStringResource(project: Project?, key: String): Array<PsiElement>? {
        if (project == null) return null
        val results = mutableListOf<PsiElement>()

        val psiManager = PsiManager.getInstance(project)
        for (vf in project.cachedComposeStringsXmlVirtualFiles()) {
            val xml = psiManager.findFile(vf) as? XmlFile ?: continue
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
        project: Project?,
        key: String,
        baseType: String
    ): Array<PsiElement>? {
        if (project == null) return null
        val extensions = when (baseType) {
            "drawable" -> listOf("xml", "png", "jpg", "jpeg", "webp", "svg")
            "font" -> listOf("ttf", "otf")
            else -> emptyList()
        }

        val results = mutableListOf<PsiElement>()
        val psiManager = PsiManager.getInstance(project)

        for (ext in extensions) {
            for (vf in project.cachedComposeResourceVirtualFilesByName("$key.$ext")) {
                // Accept "drawable", "drawable-dark", "drawable-night", "drawable-hdpi", etc.
                val parentName = vf.parent?.name ?: continue
                if (parentName != baseType && !parentName.startsWith("$baseType-")) continue
                toNavigatablePsi(psiManager, vf)?.let { results.add(it) }
            }
        }

        return results.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    /**
     * 将 VirtualFile 转为可导航的 PSI（优先 file PSI，其次 viewProvider 的任意 PSI）。
     */
    private fun toNavigatablePsi(psiManager: PsiManager, vf: VirtualFile): PsiElement? {
        return psiManager.findFile(vf)
            ?: psiManager.findViewProvider(vf)?.allFiles?.firstOrNull()
    }

    /**
     * 缓存并返回项目内 `composeResources/**/strings.xml` 文件列表（排除 `build/`）。
     *
     * 以 VFS 结构变更与项目根变更作为失效条件。
     */
    private fun Project.cachedComposeStringsXmlVirtualFiles(): List<VirtualFile> {
        val cached = this.getUserData(stringsXmlCachedValueKey)?.value
        if (cached != null) return cached

        val manager = CachedValuesManager.getManager(this)
        val cv = manager.createCachedValue {
            val scope = GlobalSearchScope.projectScope(this)
            val files = FilenameIndex.getVirtualFilesByName("strings.xml", scope)
                .asSequence()
                .filter { !it.isUnderBuildDir() && it.isUnderComposeResources() }
                .toList()
            CachedValueProvider.Result.create(
                files,
                ProjectRootModificationTracker.getInstance(this),
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS
            )
        }
        this.putUserData(stringsXmlCachedValueKey, cv)
        return cv.value
    }

    /**
     * 在 `composeResources/` 内按文件名查找资源文件并缓存结果（排除 `build/`）。
     */
    private fun Project.cachedComposeResourceVirtualFilesByName(fileName: String): List<VirtualFile> {
        val map = cachedComposeFilenameIndex()
        return map.computeIfAbsent(fileName) {
            val scope = GlobalSearchScope.projectScope(this)
            FilenameIndex.getVirtualFilesByName(fileName, scope)
                .asSequence()
                .filter { !it.isUnderBuildDir() && it.isUnderComposeResources() }
                .toList()
        }
    }

    /**
     * 缓存一个“文件名 -> VirtualFile 列表”的索引映射，用于复用多次查询结果。
     *
     * 以 VFS 结构变更与项目根变更作为失效条件。
     */
    private fun Project.cachedComposeFilenameIndex(): ConcurrentHashMap<String, List<VirtualFile>> {
        val existing = this.getUserData(composeFilenameIndexCachedValueKey)?.value
        if (existing != null) return existing

        val manager = CachedValuesManager.getManager(this)
        val cv = manager.createCachedValue {
            CachedValueProvider.Result.create(
                ConcurrentHashMap<String, List<VirtualFile>>(),
                ProjectRootModificationTracker.getInstance(this),
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS
            )
        }
        this.putUserData(composeFilenameIndexCachedValueKey, cv)
        return cv.value
    }

}

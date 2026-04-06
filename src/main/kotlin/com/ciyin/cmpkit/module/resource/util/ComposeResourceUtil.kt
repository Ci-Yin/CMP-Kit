package com.ciyin.cmpkit.module.resource.util

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import java.util.concurrent.ConcurrentHashMap

/**
 * 判断当前文件是否位于 `build/` 目录子树中。
 *
 * 用途：过滤生成物目录，避免把 build 输出误当作源资源文件。
 */
internal fun VirtualFile.isUnderBuildDir(): Boolean {
    return isInsideDir("build")
}

/**
 * 判断当前文件是否位于 `composeResources/` 目录子树中。
 *
 * 该插件只对 Compose Multiplatform 的资源源文件生效，避免误命中其他 `strings.xml`。
 */
internal fun VirtualFile.isUnderComposeResources(): Boolean {
    return isInsideDir("composeResources")
}

/**
 * 从点表达式中提取 `Res.string.<name>` 的 `<name>`。
 *
 * 支持以下形态：
 * - `Res.string.some_key`
 * - `com.xxx.Res.string.some_key`（`Res` 允许带限定名前缀）
 *
 * @return 若表达式形状不匹配（例如不是 `Res.string.xxx`）则返回 null
 */
internal fun extractResStringKey(expr: KtDotQualifiedExpression): String? {
    val keyRef = expr.selectorExpression as? KtNameReferenceExpression ?: return null
    val innerDot = expr.receiverExpression as? KtDotQualifiedExpression ?: return null
    val typeRef = innerDot.selectorExpression as? KtNameReferenceExpression ?: return null

    return keyRef
        .takeIf { typeRef.getReferencedName() == "string" }
        ?.takeIf { innerDot.receiverExpression.isResReference() }
        ?.getReferencedName()
}
/**
 * 解析并返回字符串资源 `Res.string.<key>` 对应的显示文本。
 *
 * 行为与约束：
 * - **线程安全**：内部会在 [ReadAction] 中访问 PSI。
 * - **DumbMode**：索引未就绪时（[DumbService.isDumb]）会安全降级直接返回 null。
 * - **搜索范围**：仅在项目内容根下的 `composeResources/` 子树中查找 `values/strings.xml`，并排除 `build/`。
 * - **优先级**：优先 `values/`，然后按目录名排序处理 `values-xx/` 等限定目录。
 * - **缓存**：Project 级缓存，按 key 缓存解析结果；包含 null 结果以减少重复扫描。
 *
 * 说明：返回值来自 XML PSI 的 `trimmedText`（会去除首尾空白）；若解析到空字符串，会继续尝试其他候选文件。
 *
 * @return 找不到 key 或值为空时返回 null
 */
internal fun Project.getResStringValueOrNull(key: String): String? {
    return service<ComposeStringValueResolver>().resolve(key)
}

/**
 * 判断当前文件是否位于指定目录子树中（向上递归父目录匹配目录名）。
 *
 * @param name 目标目录名称
 * @return 命中任意父目录名称则返回 true，否则 false
 */
private fun VirtualFile.isInsideDir(name: String): Boolean {
    return generateSequence(this) { it.parent }.any { it.isDirectory && it.name == name }
}

/**
 * 判断表达式是否引用了 `Res`。
 *
 * 支持：
 * - `Res`
 * - `com.xxx.Res`（限定名形式）
 */
private fun KtExpression?.isResReference(): Boolean {
    return when (this) {
        is KtNameReferenceExpression -> this.getReferencedName() == "Res"
        is KtDotQualifiedExpression -> {
            val sel = this.selectorExpression as? KtNameReferenceExpression ?: return false
            sel.getReferencedName() == "Res"
        }
        else -> false
    }
}

/**
 * 以 Project 级别缓存解析结果的解析器。
 *
 * 缓存与失效：
 * - `strings.xml` 文件列表缓存：由 [ProjectRootModificationTracker] 与 [VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS] 驱动失效。
 * - key -> value 缓存（包含 null）：同样由上述 tracker 失效，确保 VFS/根结构变化后不会使用旧结果。
 *
 * 线程与索引：
 * - PSI 访问必须在 [ReadAction] 中执行。
 * - DumbMode 直接返回 null，避免索引/PSI 访问风险与卡顿。
 */
@Service(Service.Level.PROJECT)
private class ComposeStringValueResolver(private val project: Project) {

    private val cachedStringsXmlFiles: CachedValue<List<VirtualFile>> =
        CachedValuesManager.getManager(project).createCachedValue {
            CachedValueProvider.Result.create(
                collectComposeStringsXmlFiles(project),
                ProjectRootModificationTracker.getInstance(project),
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS
            )
        }

    private val cachedKeyToValue: CachedValue<ConcurrentHashMap<String, String?>> =
        CachedValuesManager.getManager(project).createCachedValue {
            CachedValueProvider.Result.create(
                ConcurrentHashMap(),
                ProjectRootModificationTracker.getInstance(project),
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS
            )
        }

    fun resolve(key: String): String? {
        if (project.isDisposed) return null
        if (DumbService.isDumb(project)) return null

        return ReadAction.compute<String?, RuntimeException> {
            if (project.isDisposed) return@compute null
            val cache = cachedKeyToValue.value
            cache.computeIfAbsent(key) {
                val files = cachedStringsXmlFiles.value
                resolveFromStringsXmlFiles(project, files, key)
            }
        }
    }
}

private fun collectComposeStringsXmlFiles(project: Project): List<VirtualFile> {
    val roots = collectComposeResourcesRoots(project)
    if (roots.isEmpty()) return emptyList()

    val results = ArrayList<VirtualFile>(64)
    for (root in roots) {
        VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>() {
            override fun visitFileEx(file: VirtualFile): Result {
                if (file.isDirectory) {
                    if (file.name == "build") return SKIP_CHILDREN
                    return CONTINUE
                }
                if (file.name != "strings.xml") return CONTINUE
                if (file.isUnderBuildDir()) return CONTINUE
                val parentName = file.parent?.name ?: return CONTINUE
                if (!parentName.startsWith("values")) return CONTINUE
                results.add(file)
                return CONTINUE
            }
        })
    }

    return results.sortedWith(
        compareBy(
            { if (it.parent?.name == "values") 0 else 1 },
            { it.parent?.name ?: "" }
        )
    )
}

/**
 * 从项目内容根中收集所有 `composeResources/` 目录本身作为扫描根。
 *
 * 会跳过 `build/` 目录子树以避免扫描生成物。
 */
private fun collectComposeResourcesRoots(project: Project): List<VirtualFile> {
    val roots = com.intellij.openapi.roots.ProjectRootManager.getInstance(project).contentRoots
    if (roots.isEmpty()) return emptyList()

    val results = ArrayList<VirtualFile>(8)
    for (root in roots) {
        VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>() {
            override fun visitFileEx(file: VirtualFile): Result {
                if (!file.isDirectory) return CONTINUE
                if (file.name == "build") return SKIP_CHILDREN
                if (file.name == "composeResources") {
                    results.add(file)
                    return SKIP_CHILDREN
                }
                return CONTINUE
            }
        })
    }
    return results
}

/**
 * 依序在候选 `strings.xml` 中查找指定 key 的值。
 *
 * 仅接受根标签为 `<resources>` 的 XML；值取 `trimmedText`，空字符串视为无效并继续查找下一个文件。
 */
private fun resolveFromStringsXmlFiles(project: Project, files: List<VirtualFile>, key: String): String? {
    val psiManager = PsiManager.getInstance(project)
    for (vf in files) {
        val xml = psiManager.findFile(vf) as? XmlFile ?: continue
        val root = xml.rootTag ?: continue
        if (root.name != "resources") continue

        val value = findStringValueInResourcesTag(root, key) ?: continue
        if (value.isNotEmpty()) return value
    }
    return null
}

/**
 * 在 `<resources>` 根下查找 `<string name="key">` 的值。
 *
 * 实现为 DFS 遍历，以避免遗漏非直接子节点的 `string` 标签。
 */
private fun findStringValueInResourcesTag(root: com.intellij.psi.xml.XmlTag, key: String): String? {
    val stack = ArrayDeque<com.intellij.psi.xml.XmlTag>()
    stack.add(root)
    while (stack.isNotEmpty()) {
        val tag = stack.removeLast()
        if (tag.name == "string" && tag.getAttributeValue("name") == key) {
            return tag.value.trimmedText
        }
        for (child in tag.subTags) {
            stack.add(child)
        }
    }
    return null
}

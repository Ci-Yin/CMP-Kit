package com.ciyin.cmpkit.module.resource

import com.ciyin.cmpkit.module.resource.util.extractResStringKey
import com.ciyin.cmpkit.module.resource.util.getResStringValueOrNull
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/**
 * 将 Kotlin 代码中的字符串资源引用折叠为字符串值的预览文本（带引号）。
 *
 * 行为说明：
 * - 支持：
 *   - `Res.string.some_key`
 *   - `stringResource(Res.string.some_key)`（仅 1 个资源参数，且无额外格式化参数）
 *   - `getString(Res.string.some_key)`（仅 1 个资源参数，且无额外格式化参数；可用于 `context.getString(...)` 等）
 * - 值来源于 `composeResources/**/strings.xml`（解析逻辑复用 `resolveStringValue`）
 * - 默认对新打开文件折叠（效果类似 Android 资源引用折叠）
 * - 在索引不可用（Dumb Mode）或 quick pass 时不做解析，避免卡顿
 */
class ComposeResStringFoldingBuilder : FoldingBuilderEx(), DumbAware {

    /**
     * 收集当前文件中可折叠的 `Res.string.<key>` 区域。
     *
     * @param root 当前 PSI 根节点（这里期望是 Kotlin 文件）
     * @param document 当前编辑器文档
     * @param quick IntelliJ 的快速折叠构建阶段；为 true 时应尽量避免复杂解析
     */
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        if (quick || root !is KtFile) return FoldingDescriptor.EMPTY_ARRAY
        val project = root.project
        if (DumbService.isDumb(project)) return FoldingDescriptor.EMPTY_ARRAY

        val descriptors = ArrayList<FoldingDescriptor>()
        root.accept(object : KtTreeVisitorVoid() {
            override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
                super.visitDotQualifiedExpression(expression)
                // 若外层调用（stringResource/getString）会被折叠，则避免内部 Res.string.xxx 再折叠一次。
                if (expression.getStrictParentOfType<KtCallExpression>()
                        ?.let { extractStringKeyFromCall(it) } != null
                ) {
                    return
                }
                val key = extractResStringKey(expression) ?: return
                project.getResStringValueOrNull(key) ?: return
                val node = expression.node
                val range = expression.textRange
                if (range.isEmpty) return
                descriptors.add(newDescriptor(node, range))
            }

            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)
                val key = extractStringKeyFromCall(expression) ?: return
                project.getResStringValueOrNull(key) ?: return
                val node = expression.node
                val range = expression.textRange
                if (range.isEmpty) return
                descriptors.add(newDescriptor(node, range))
            }
        })
        return descriptors.toTypedArray()
    }

    /**
     * 返回折叠后的占位文本（也就是展示给用户看的预览内容）。
     *
     * 例如：`Res.string.app_name` 会显示为 `"应用名称"`。
     */
    override fun getPlaceholderText(node: ASTNode): String? {
        val psi = node.psi
        val key = when (psi) {
            is KtDotQualifiedExpression -> extractResStringKey(psi)
            is KtCallExpression -> extractStringKeyFromCall(psi)
            else -> null
        } ?: return null
        val value = psi.project.getResStringValueOrNull(key) ?: return PLACEHOLDER_FALLBACK
        return formatPlaceholder(value)
    }

    /**
     * 控制折叠是否默认启用。
     *
     * 返回 true 表示：新打开文件时默认折叠为占位符文本。
     */
    override fun isCollapsedByDefault(node: ASTNode): Boolean = true

    /**
     * 将实际字符串值格式化为折叠占位符文本（带引号与必要转义）。
     */
    private fun formatPlaceholder(value: String): String {
        val display = if (value.length > MAX_PLACEHOLDER_LEN) value.take(MAX_PLACEHOLDER_LEN) + "…" else value
        return "\"" + escapeForPlaceholder(display) + "\""
    }

    /**
     * 为占位符文本做最小转义，避免破坏编辑器显示（如引号、换行、反斜杠）。
     */
    private fun escapeForPlaceholder(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\"", "\\\"")

    /**
     * 从调用表达式中提取 `Res.string.<key>` 的 key。
     *
     * 仅支持 `stringResource(...)` / `getString(...)` 且仅有 1 个参数，以避免误折叠格式化/quantity 等场景。
     */
    private fun extractStringKeyFromCall(call: KtCallExpression): String? {
        val callee = call.calleeExpression?.text ?: return null
        if (callee != "stringResource" && callee != "getString") return null

        // 只在“没有额外参数”的情况下折叠，避免误把格式化/quantity 等情况折叠掉。
        val args = call.valueArguments
        val candidate = when (args.size) {
            1 -> args.single()
            else -> return null
        }

        val argExpr = candidate.getArgumentExpression() ?: return null

        val dot = argExpr as? KtDotQualifiedExpression ?: return null
        return extractResStringKey(dot)
    }

    /**
     * 创建折叠描述符。
     */
    private fun newDescriptor(node: ASTNode, range: TextRange) = FoldingDescriptor(
        /* node = */ node,
        /* range = */ range,
        /* group = */ null,
        /* dependencies = */ emptySet(),
        /* neverExpands = */ false,
        /* placeholderText = */ null,
        /* collapsedByDefault = */ true,
    )

    companion object {
        private const val MAX_PLACEHOLDER_LEN = 60
        private const val PLACEHOLDER_FALLBACK = "…"
    }
}

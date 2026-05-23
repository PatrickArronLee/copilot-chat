package com.patrick.copilotchat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders markdown text with support for:
 * - **bold**, *italic*, `inline code`
 * - # h1, ## h2, ### h3 headers
 * - ``` code blocks with copy button
 * - - / * bullet lists, 1. numbered lists
 * - Plain text paragraphs
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified
) {
    val blocks = remember(text) { parseMarkdown(text) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.CodeBlock -> CodeBlockView(block.language, block.code)
                is MarkdownBlock.Header -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    Text(
                        text = block.text,
                        style = style.copy(fontWeight = FontWeight.Bold),
                        color = color,
                        modifier = Modifier.padding(top = if (block.level == 1) 8.dp else 4.dp, bottom = 2.dp)
                    )
                }
                is MarkdownBlock.BulletItem -> {
                    Row(modifier = Modifier.padding(start = 8.dp)) {
                        Text("• ", color = color, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = buildInlineAnnotatedString(block.text),
                            color = color,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                is MarkdownBlock.NumberedItem -> {
                    Row(modifier = Modifier.padding(start = 8.dp)) {
                        Text("${block.number}. ", color = color, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = buildInlineAnnotatedString(block.text),
                            color = color,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = buildInlineAnnotatedString(block.text),
                        color = color,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                is MarkdownBlock.HorizontalRule -> {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun CodeBlockView(language: String, code: String) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(2000)
            copied = false
        }
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Header bar with language label and copy button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(code))
                        copied = true
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = if (copied) "Copied" else "Copy code",
                        modifier = Modifier.size(16.dp),
                        tint = if (copied) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Code content
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 20.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/** Build AnnotatedString supporting **bold**, *italic*, `code`, ~~strikethrough~~ */
private fun buildInlineAnnotatedString(text: String): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                // **bold** or __bold__
                (text.startsWith("**", i) || text.startsWith("__", i)) -> {
                    val marker = text.substring(i, i + 2)
                    val end = text.indexOf(marker, i + 2)
                    if (end > i + 2) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // *italic* or _italic_
                (text[i] == '*' || text[i] == '_') && (i == 0 || text[i - 1] == ' ' || text[i - 1] == '\n') -> {
                    val marker = text[i].toString()
                    val end = text.indexOf(marker, i + 1)
                    if (end > i + 1 && (end == text.length - 1 || text[end + 1] == ' ' || text[end + 1] == '\n' || text[end + 1] == '.' || text[end + 1] == ',')) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // `inline code`
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = Color(0x22808080),
                                fontSize = 13.sp
                            )
                        ) {
                            append(" ")
                            append(text.substring(i + 1, end))
                            append(" ")
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // ~~strikethrough~~
                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end > i + 2) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}

// ─── Data model ────────────────────────────────────────────────────────────────

sealed class MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Header(val level: Int, val text: String) : MarkdownBlock()
    data class BulletItem(val text: String) : MarkdownBlock()
    data class NumberedItem(val number: Int, val text: String) : MarkdownBlock()
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock()
    object HorizontalRule : MarkdownBlock()
}

// ─── Parser ─────────────────────────────────────────────────────────────────

private fun parseMarkdown(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.split('\n')
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // Fenced code block ```
        if (line.trimStart().startsWith("```")) {
            val language = line.trimStart().removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            blocks.add(MarkdownBlock.CodeBlock(language, codeLines.joinToString("\n")))
            i++ // skip closing ```
            continue
        }

        // Headers
        val headerMatch = Regex("^(#{1,6})\\s+(.+)$").matchEntire(line)
        if (headerMatch != null) {
            val level = headerMatch.groupValues[1].length
            val headerText = headerMatch.groupValues[2]
            blocks.add(MarkdownBlock.Header(level, headerText))
            i++
            continue
        }

        // Horizontal rule
        if (Regex("^[-*_]{3,}$").matches(line.trim())) {
            blocks.add(MarkdownBlock.HorizontalRule)
            i++
            continue
        }

        // Bullet list item (- or * or +)
        val bulletMatch = Regex("^[\\s]*[-*+]\\s+(.+)$").matchEntire(line)
        if (bulletMatch != null) {
            blocks.add(MarkdownBlock.BulletItem(bulletMatch.groupValues[1]))
            i++
            continue
        }

        // Numbered list item
        val numberedMatch = Regex("^[\\s]*(\\d+)\\.\\s+(.+)$").matchEntire(line)
        if (numberedMatch != null) {
            val number = numberedMatch.groupValues[1].toIntOrNull() ?: 1
            blocks.add(MarkdownBlock.NumberedItem(number, numberedMatch.groupValues[2]))
            i++
            continue
        }

        // Blank line — skip
        if (line.isBlank()) {
            i++
            continue
        }

        // Paragraph — collect consecutive non-blank, non-special lines
        val paragraphLines = mutableListOf<String>()
        while (i < lines.size) {
            val l = lines[i]
            if (l.isBlank()) break
            if (l.trimStart().startsWith("```")) break
            if (Regex("^#{1,6}\\s").containsMatchIn(l)) break
            if (Regex("^[\\s]*[-*+]\\s+").containsMatchIn(l)) break
            if (Regex("^[\\s]*\\d+\\.\\s+").containsMatchIn(l)) break
            paragraphLines.add(l)
            i++
        }
        if (paragraphLines.isNotEmpty()) {
            blocks.add(MarkdownBlock.Paragraph(paragraphLines.joinToString(" ")))
        }
    }

    return blocks
}

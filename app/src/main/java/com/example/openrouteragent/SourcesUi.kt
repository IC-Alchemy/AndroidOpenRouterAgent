package com.example.openrouteragent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.net.URI

/** The one-line label of a reply's collapsed web search block, such as "5 sources · 1 search". */
internal fun sourcesSummary(sourceCount: Int, queryCount: Int): String {
    val sources = when (sourceCount) {
        0 -> null
        1 -> "1 source"
        else -> "$sourceCount sources"
    }
    val searches = when (queryCount) {
        0 -> null
        1 -> "1 search"
        else -> "$queryCount searches"
    }
    return listOfNotNull(sources, searches).joinToString(" · ").ifEmpty { "Web search" }
}

/** The site a source came from, without "www.", or null when the address has no host. */
internal fun sourceHost(url: String): String? =
    runCatching { URI(url.trim()).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() }

/**
 * A reply's web searches and sources folded into one row, so a long list of links never
 * pushes the reply itself off the screen. Tapping the row shows the searches and the links.
 */
@Composable
internal fun SourcesDisclosure(
    citations: List<WebCitation>,
    queries: List<String>,
    modifier: Modifier = Modifier,
) {
    if (citations.isEmpty() && queries.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    StudioRailBlock(
        railColor = MaterialTheme.colorScheme.secondary,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide sources" else "Show sources") {
                    expanded = !expanded
                },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Text(
                sourcesSummary(citations.size, queries.size),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                queries.forEach { query ->
                    CopyableText(
                        "Searched: $query",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                SourceLinks(citations)
            }
        }
    }
}

/** Sources as single-spaced lines: the title, then the site it came from. Tapping one opens it. */
@Composable
internal fun SourceLinks(citations: List<WebCitation>) {
    val uriHandler = LocalUriHandler.current
    citations.forEach { cite ->
        val host = sourceHost(cite.url)
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable(role = Role.Button) { runCatching { uriHandler.openUri(cite.url) } }
                .padding(vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                cite.title.ifBlank { host ?: cite.url },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (host != null && cite.title.isNotBlank()) {
                Text(
                    host,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
            }
        }
    }
}

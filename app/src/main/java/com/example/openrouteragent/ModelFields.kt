package com.example.openrouteragent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The OpenRouter models a settings picker offers: the account's model list, which OpenRouter already filters by the
 * provider and model preferences set on openrouter.ai. OpenAI (direct) models are left out, because routing, the
 * advisor and the other OpenRouter tools can't use them.
 */
internal data class ModelChoices(
    val models: List<CatalogModel>,
    val loading: Boolean,
    val error: String?,
    val onRefresh: () -> Unit,
) {
    val openRouterModels: List<CatalogModel> get() = models.filterNot { it.id.startsWith(OPENAI_MODEL_PREFIX) }

    /** The model's display name, or the ID itself when the list doesn't have it. */
    fun label(id: String): String = openRouterModels.firstOrNull { it.id == id }?.name ?: id
}

/** Splits a comma-separated model list into IDs, without blanks or repeats. */
internal fun modelIds(csv: String): List<String> = csv.split(',').map(String::trim).filter(String::isNotEmpty).distinct()

/** Adds a model to a comma-separated list, or leaves the list alone when the model is already in it. */
internal fun addModelId(csv: String, id: String): String = (modelIds(csv) + id.trim()).filter(String::isNotEmpty)
    .distinct().joinToString(", ")

internal fun removeModelId(csv: String, id: String): String = modelIds(csv).filterNot { it == id }.joinToString(", ")

/**
 * One model setting. It shows the chosen model and opens the model list to change it, so nobody has to type a model
 * ID. [emptyLabel] says what happens when no model is chosen.
 */
@Composable
internal fun ModelField(
    title: String,
    value: String,
    onChange: (String) -> Unit,
    choices: ModelChoices,
    emptyLabel: String,
    hint: String? = null,
    filter: (CatalogModel) -> Boolean = { true },
) {
    var picking by rememberSaveable(title) { mutableStateOf(false) }
    val id = value.trim()
    OutlinedSection(title = title) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(if (id.isEmpty()) emptyLabel else choices.label(id), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (id.isNotEmpty() && choices.label(id) != id) {
                    Text(id, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (id.isNotEmpty()) TextButton(onClick = { onChange("") }) { Text("Clear") }
            OutlinedButton(onClick = { picking = true }) { Text(if (id.isEmpty()) "Choose" else "Change") }
        }
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (picking) {
        ModelPicker(
            models = choices.openRouterModels.filter(filter),
            selected = choices.openRouterModels.firstOrNull { it.id == id },
            requireTools = false,
            loading = choices.loading,
            error = choices.error,
            onRefresh = choices.onRefresh,
            onDismiss = { picking = false },
        ) { chosen ->
            onChange(chosen.id)
            picking = false
        }
    }
}

/**
 * A list of models, such as fallback models. Each chosen model shows as a chip that can be removed, and Add model
 * opens the model list. The value stays a comma-separated list of IDs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelListField(
    title: String,
    value: String,
    onChange: (String) -> Unit,
    choices: ModelChoices,
    hint: String? = null,
) {
    var picking by rememberSaveable(title) { mutableStateOf(false) }
    val ids = modelIds(value)
    OutlinedSection(title = title) {
        if (ids.isEmpty()) {
            Text("None", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ids.forEachIndexed { index, id ->
                    InputChip(
                        selected = false,
                        onClick = { onChange(removeModelId(value, id)) },
                        label = { Text("${index + 1}. ${choices.label(id)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { Icon(Icons.Default.Close, "Remove ${choices.label(id)}", Modifier.size(16.dp)) },
                    )
                }
            }
        }
        OutlinedButton(onClick = { picking = true }) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Text("Add model", Modifier.padding(start = 6.dp))
        }
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (picking) {
        ModelPicker(
            models = choices.openRouterModels.filterNot { it.id in ids },
            selected = null,
            requireTools = false,
            loading = choices.loading,
            error = choices.error,
            onRefresh = choices.onRefresh,
            onDismiss = { picking = false },
        ) { chosen ->
            onChange(addModelId(value, chosen.id))
            picking = false
        }
    }
}

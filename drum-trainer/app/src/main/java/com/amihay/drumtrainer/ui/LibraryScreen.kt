package com.amihay.drumtrainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** All exercises, filtered by category. */
@Composable
fun LibraryScreen(app: AppState) {
    val progressFlow = remember { app.dao.allProgress() }
    val progress by progressFlow.collectAsState(initial = emptyList())
    val byId = progress.associateBy { it.exerciseId }
    var category by rememberSaveable { mutableStateOf(Category.ALL) }
    val categories = Category.entries.filter { c -> c == Category.ALL || c == Category.MINE || app.exercises.any(c.matches) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = PagePadding, vertical = 0.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            TabHeader("Exercises", "${app.exercises.size} exercises · in curriculum order") {
                TextButton(onClick = { app.screen = Screen.Editor() }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("New", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        item(key = "chips") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                items(categories) { c -> ChoiceChip(c.label, c == category) { category = c } }
            }
        }
        if (category == Category.ALL) {
            for (c in Category.entries.drop(1)) {
                val list = app.exercises.filter(c.matches).sortedBy { it.level }
                if (list.isEmpty()) continue
                item(key = "title-${c.name}") { SectionLabel(c.label, Modifier.padding(top = 8.dp)) }
                items(list, key = { "${c.name}-${it.id}" }) { ex -> ExerciseCard(app, ex, byId[app.progressId(ex.id)]) }
            }
        } else {
            val list = app.exercises.filter(category.matches).sortedBy { it.level }
            if (list.isEmpty()) {
                item(key = "empty") {
                    Panel(onClick = { app.screen = Screen.Editor() }) {
                        Text("No exercises here yet", style = MaterialTheme.typography.titleMedium)
                        Text("Write a groove, a fill or a sticking in the editor and it shows up here.",
                            style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
                    }
                }
            }
            items(list, key = { it.id }) { ex -> ExerciseCard(app, ex, byId[app.progressId(ex.id)]) }
        }
        item(key = "end") { androidx.compose.foundation.layout.Spacer(Modifier.padding(12.dp)) }
    }
}

package com.messenger.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.messenger.app.data.model.UserSearchResult
import com.messenger.app.ui.components.HairlineDivider
import com.messenger.app.ui.components.SectionHeader
import com.messenger.app.ui.theme.Tokens
import com.messenger.app.ui.theme.isDarkTheme
import com.messenger.app.ui.viewmodel.CreateGroupViewModel
import com.messenger.app.ui.viewmodel.SelectedMember

/**
 * Group creation: name the group, pick members, create.
 *
 * Built on the shared design tokens so it reads as the same product as the
 * Settings surfaces rather than a bolted-on form.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    onNavigateBack: () -> Unit,
    onGroupCreated: (chatId: String, chatName: String) -> Unit,
    viewModel: CreateGroupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dark = isDarkTheme()

    // One-shot: navigate into the group once the server confirms it exists.
    LaunchedEffect(state.createdChatId) {
        val id = state.createdChatId ?: return@LaunchedEffect
        val name = state.trimmedName
        viewModel.consumeCreated()
        onGroupCreated(id, name)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "New group",
                        style = Tokens.Type.screenTitle,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    CreateAction(
                        enabled = state.canCreate,
                        busy = state.isCreating,
                        onClick = viewModel::create
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            AnimatedVisibility(
                visible = state.error != null,
                enter = fadeIn(tween(Tokens.Motion.FAST_MS)) + expandVertically(),
                exit = fadeOut(tween(Tokens.Motion.EXIT_MS)) + shrinkVertically()
            ) {
                ErrorBanner(message = state.error.orEmpty(), onDismiss = viewModel::dismissError)
            }

            // ---- Name + description ----
            Column(modifier = Modifier.padding(horizontal = Tokens.Space.screenGutter)) {
                Spacer(Modifier.height(Tokens.Space.lg))
                LuxTextField(
                    value = state.name,
                    onValueChange = viewModel::setName,
                    label = "Group name",
                    error = state.nameError,
                    imeAction = ImeAction.Next
                )
                Spacer(Modifier.height(Tokens.Space.md))
                LuxTextField(
                    value = state.description,
                    onValueChange = viewModel::setDescription,
                    label = "Description (optional)",
                    imeAction = ImeAction.Done
                )
            }

            // ---- Chosen members ----
            AnimatedVisibility(visible = state.selected.isNotEmpty()) {
                Column {
                    SectionHeader(
                        title = "Members",
                        overline = "${state.selected.size} selected"
                    )
                    SelectedMembersRow(
                        members = state.selected,
                        onRemove = viewModel::removeMember
                    )
                }
            }

            SectionHeader(title = "Add people", overline = "Search")

            Column(modifier = Modifier.padding(horizontal = Tokens.Space.screenGutter)) {
                LuxTextField(
                    value = state.query,
                    onValueChange = viewModel::search,
                    label = "Search by name or email",
                    leadingIcon = Icons.Outlined.Search,
                    imeAction = ImeAction.Search
                )
            }

            Spacer(Modifier.height(Tokens.Space.md))
            HairlineDivider()

            SearchResults(
                results = state.searchResults,
                isSearching = state.isSearching,
                query = state.query,
                isSelected = viewModel::isSelected,
                onToggle = viewModel::toggleMember,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// ==================== Pieces ====================

@Composable
private fun CreateAction(enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    val dark = isDarkTheme()
    Box(
        modifier = Modifier
            .padding(end = Tokens.Space.sm)
            .clip(RoundedCornerShape(Tokens.Radius.sm))
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .defaultMinSize(minWidth = Tokens.Size.touchTarget, minHeight = Tokens.Size.touchTarget)
            .padding(horizontal = Tokens.Space.md)
            .semantics { contentDescription = "Create group" },
        contentAlignment = Alignment.Center
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = Tokens.Palette.accent(dark)
            )
        } else {
            Text(
                text = "Create",
                style = Tokens.Type.rowValue,
                color = Tokens.Palette.accent(dark),
                modifier = Modifier.alpha(if (enabled) 1f else Tokens.DISABLED_ALPHA)
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
            .padding(
                horizontal = Tokens.Space.screenGutter,
                vertical = Tokens.Space.md
            )
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            style = Tokens.Type.rowSubtitle,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Dismiss error",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(Tokens.Size.icon)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LuxTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    imeAction: ImeAction = ImeAction.Default
) {
    val dark = isDarkTheme()
    val accent = Tokens.Palette.accent(dark)
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, style = Tokens.Type.rowSubtitle) },
            singleLine = true,
            isError = error != null,
            leadingIcon = leadingIcon?.let {
                {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        modifier = Modifier.size(Tokens.Size.icon),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            shape = RoundedCornerShape(Tokens.Radius.md),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent,
                unfocusedBorderColor = Tokens.Palette.divider(dark),
                focusedLabelColor = accent,
                cursorColor = accent,
                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground
            ),
            modifier = Modifier.fillMaxWidth()
        )
        if (error != null) {
            Text(
                text = error,
                style = Tokens.Type.rowSubtitle,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = Tokens.Space.xs)
            )
        }
    }
}

@Composable
private fun SelectedMembersRow(
    members: List<SelectedMember>,
    onRemove: (SelectedMember) -> Unit
) {
    val dark = isDarkTheme()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Space.screenGutter),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.sm)
    ) {
        members.forEach { member ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Tokens.Radius.md))
                    .background(Tokens.Elevation.level1(dark))
                    .clickable(role = Role.Button) { onRemove(member) }
                    .defaultMinSize(minHeight = Tokens.Size.touchTarget)
                    .padding(
                        horizontal = Tokens.Space.md,
                        vertical = Tokens.Space.sm
                    )
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Remove ${member.name} from group"
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(name = member.name, size = 28.dp)
                Spacer(Modifier.size(Tokens.Space.md))
                Text(
                    text = member.name,
                    style = Tokens.Type.rowTitle,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Tokens.Size.icon)
                )
            }
        }
    }
}

@Composable
private fun SearchResults(
    results: List<UserSearchResult>,
    isSearching: Boolean,
    query: String,
    isSelected: (String) -> Boolean,
    onToggle: (UserSearchResult) -> Unit,
    modifier: Modifier = Modifier
) {
    val dark = isDarkTheme()

    when {
        isSearching && results.isEmpty() -> Box(
            modifier = modifier.padding(Tokens.Space.xl),
            contentAlignment = Alignment.TopCenter
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Tokens.Palette.accent(dark)
            )
        }

        results.isEmpty() -> Box(
            modifier = modifier.padding(Tokens.Space.xl),
            contentAlignment = Alignment.TopCenter
        ) {
            Text(
                text = if (query.isBlank()) {
                    "Search for people to add"
                } else {
                    "No one found for “$query”"
                },
                style = Tokens.Type.rowSubtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        else -> LazyColumn(modifier = modifier) {
            items(results, key = { it.id }) { user ->
                UserRow(
                    user = user,
                    selected = isSelected(user.id),
                    onToggle = { onToggle(user) }
                )
            }
        }
    }
}

@Composable
private fun UserRow(
    user: UserSearchResult,
    selected: Boolean,
    onToggle: () -> Unit
) {
    val dark = isDarkTheme()
    val name = user.displayName?.takeIf { it.isNotBlank() } ?: user.username

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Checkbox role rather than Switch: this is multi-select membership,
            // not an on/off setting.
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .defaultMinSize(minHeight = Tokens.Size.rowHeightTall)
            .padding(
                horizontal = Tokens.Space.screenGutter,
                vertical = Tokens.Space.sm
            )
            .semantics(mergeDescendants = true) {
                stateDescription = if (selected) "Selected" else "Not selected"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(name = name, size = 40.dp)
        Spacer(Modifier.size(Tokens.Space.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = Tokens.Type.rowTitle,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = user.email,
                style = Tokens.Type.rowSubtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = Tokens.Palette.accent(dark),
                modifier = Modifier.size(Tokens.Size.icon)
            )
        }
    }
}

@Composable
private fun Avatar(name: String, size: androidx.compose.ui.unit.Dp) {
    val dark = isDarkTheme()
    Box(
        modifier = Modifier
            .size(size)
            .background(Tokens.Palette.accent(dark), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.take(1).uppercase(),
            color = Color.White,
            fontWeight = FontWeight.Medium,
            style = Tokens.Type.rowValue
        )
    }
}

package com.messenger.app.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.messenger.app.BuildConfig
import com.messenger.app.data.model.GroupMemberDto
import com.messenger.app.data.model.GroupRole
import com.messenger.app.ui.components.Avatar
import com.messenger.app.ui.components.ConfirmDialog
import com.messenger.app.ui.components.HairlineDivider
import com.messenger.app.ui.components.LuxDialog
import com.messenger.app.ui.components.SettingsRow
import com.messenger.app.ui.components.ToggleRow
import com.messenger.app.ui.theme.Tokens
import com.messenger.app.ui.theme.isDarkTheme
import com.messenger.app.ui.viewmodel.GroupInfoViewModel

private sealed interface GroupDialog {
    data object Rename : GroupDialog
    data object AddMembers : GroupDialog
    data object ConfirmLeave : GroupDialog
    data object ConfirmDelete : GroupDialog
    data class ConfirmRemove(val member: GroupMemberDto) : GroupDialog

    /** Debug-only MLS recovery. See the DangerRow guarded by BuildConfig.DEBUG. */
    data object ConfirmResetMls : GroupDialog
}

/**
 * Group details, in the shape of Telegram's: an identity header, the member
 * list, then group-level settings.
 *
 * The header is fixed and only the member list scrolls, so the group's identity
 * and the admin actions stay put while working through a long member list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    chatId: String,
    onNavigateBack: () -> Unit,
    onGroupExited: () -> Unit,
    viewModel: GroupInfoViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dark = isDarkTheme()
    val snackbarHostState = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<GroupDialog?>(null) }

    // The system photo picker: no storage permission needed, and the user only
    // ever hands over the single image they chose.
    val pickGroupPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(viewModel::setGroupPhoto) }

    val onPickGroupPhoto: () -> Unit = {
        pickGroupPhoto.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    LaunchedEffect(chatId) { viewModel.load(chatId) }

    // Left or deleted - this chat no longer exists for us.
    LaunchedEffect(state.exited) { if (state.exited) onGroupExited() }

    LaunchedEffect(state.message) {
        state.message?.let { snackbarHostState.showSnackbar(it); viewModel.consumeMessage() }
    }
    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it); viewModel.dismissError() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Group info",
                        style = Tokens.Type.screenTitle,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    if (state.isAdmin && state.group != null) {
                        IconButton(onClick = { dialog = GroupDialog.Rename }) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = "Edit group",
                                tint = Tokens.Palette.accent(dark)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        when {
            state.isLoading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Tokens.Palette.accent(dark))
            }

            state.group == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    state.error ?: "Group not found",
                    style = Tokens.Type.rowSubtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.TopCenter
            ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                // ---- Fixed header ----
                GroupHeaderCard(
                    name = state.group!!.name,
                    avatarUrl = state.group!!.avatarUrl,
                    description = state.group!!.description,
                    memberCount = state.members.size,
                    canEdit = state.isAdmin,
                    onEdit = { dialog = GroupDialog.Rename },
                    onChangePhoto = onPickGroupPhoto
                )

                // ---- Scrollable remainder ----
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    item {
                        SectionLabel("${state.members.size} members")
                        if (state.isAdmin) {
                            SettingsRow(
                                title = "Add members",
                                icon = Icons.Outlined.PersonAdd,
                                onClick = {
                                    viewModel.resetAddMembers()
                                    dialog = GroupDialog.AddMembers
                                }
                            )
                        }
                    }

                    items(state.sortedMembers, key = { it.id }) { member ->
                        MemberRow(
                            member = member,
                            isMe = member.id == state.currentUserId,
                            isOwner = state.isOwner(member.id),
                            viewerIsAdmin = state.isAdmin,
                            busy = state.busyMemberId == member.id,
                            onPromote = { viewModel.setRole(member, GroupRole.ADMIN) },
                            onDemote = { viewModel.setRole(member, GroupRole.MEMBER) },
                            onRemove = { dialog = GroupDialog.ConfirmRemove(member) }
                        )
                    }

                    item {
                        Spacer(Modifier.height(Tokens.Space.lg))
                        HairlineDivider()
                        SectionLabel("Settings")

                        ToggleRow(
                            title = "Mute notifications",
                            subtitle = "Stop alerts from this group",
                            icon = Icons.Outlined.NotificationsOff,
                            checked = state.isMuted,
                            onCheckedChange = viewModel::setMuted
                        )

                        HairlineDivider()

                        DangerRow(
                            title = "Leave group",
                            icon = Icons.AutoMirrored.Outlined.Logout,
                            onClick = { dialog = GroupDialog.ConfirmLeave }
                        )

                        // Delete is owner-only: hidden rather than shown-and-refused.
                        if (state.isOwner) {
                            DangerRow(
                                title = "Delete group",
                                subtitle = "Removes it for everyone. Cannot be undone.",
                                icon = Icons.Outlined.DeleteForever,
                                onClick = { dialog = GroupDialog.ConfirmDelete }
                            )
                        }

                        // Debug builds only. Recovery for a device that has lost
                        // its MLS state: it cannot rejoin the existing group, so
                        // this abandons that group and starts a fresh one. Not a
                        // production affordance - it discards readable history.
                        if (BuildConfig.DEBUG) {
                            DangerRow(
                                title = "Reset MLS Group",
                                subtitle = "Debug: rejoin encryption. Earlier messages " +
                                    "become unreadable.",
                                icon = Icons.Outlined.Lock,
                                onClick = { dialog = GroupDialog.ConfirmResetMls }
                            )
                        }
                        Spacer(Modifier.height(Tokens.Space.xxl))
                    }
                }
            }
            }
        }
    }

    // ==================== Dialogs ====================

    when (val d = dialog) {
        null -> Unit

        GroupDialog.Rename -> RenameDialog(
            initialName = state.group?.name.orEmpty(),
            initialDescription = state.group?.description.orEmpty(),
            onConfirm = { n, desc -> viewModel.rename(n, desc); dialog = null },
            onDismiss = { dialog = null }
        )

        GroupDialog.AddMembers -> AddMembersDialog(
            viewModel = viewModel,
            onDismiss = { viewModel.resetAddMembers(); dialog = null },
            onDone = { dialog = null }
        )

        GroupDialog.ConfirmLeave -> ConfirmDialog(
            title = "Leave group?",
            message = "You'll stop receiving messages from " +
                "${state.group?.name.orEmpty()}. An admin can add you back later.",
            amountAtRisk = "You will leave ${state.group?.name.orEmpty()}",
            confirmLabel = "Leave",
            destructive = true,
            onConfirm = { viewModel.leaveGroup(); dialog = null },
            onDismiss = { dialog = null }
        )

        GroupDialog.ConfirmDelete -> ConfirmDialog(
            title = "Delete group?",
            message = "This removes ${state.group?.name.orEmpty()} for every member " +
                "and deletes its messages. This cannot be undone.",
            amountAtRisk = "${state.members.size} members will lose this group",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { viewModel.deleteGroup(); dialog = null },
            onDismiss = { dialog = null }
        )

        GroupDialog.ConfirmResetMls -> ConfirmDialog(
            title = "Reset encryption for this group?",
            message = "This device will leave the group's encryption and rejoin it " +
                "fresh. Messages sent before now become permanently unreadable for " +
                "everyone. Use this only when messages cannot be decrypted.",
            amountAtRisk = "All existing messages in this group become unreadable",
            confirmLabel = "Reset",
            destructive = true,
            onConfirm = { viewModel.recoverMlsGroup(chatId); dialog = null },
            onDismiss = { dialog = null }
        )

        is GroupDialog.ConfirmRemove -> ConfirmDialog(
            title = "Remove member?",
            message = "${d.member.bestName()} will be removed from " +
                "${state.group?.name.orEmpty()}.",
            amountAtRisk = "${d.member.bestName()} will lose access",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = { viewModel.removeMember(d.member); dialog = null },
            onDismiss = { dialog = null }
        )
    }
}

// ==================== Header ====================

@Composable
private fun GroupHeaderCard(
    name: String,
    avatarUrl: String?,
    description: String,
    memberCount: Int,
    canEdit: Boolean,
    onEdit: () -> Unit,
    onChangePhoto: () -> Unit
) {
    val dark = isDarkTheme()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Space.md, vertical = Tokens.Space.sm)
            .clip(RoundedCornerShape(Tokens.Radius.md))
            .background(Tokens.Elevation.level1(dark))
            .then(
                if (canEdit) Modifier.clickable(role = Role.Button, onClick = onEdit)
                else Modifier
            )
            .padding(vertical = Tokens.Space.lg, horizontal = Tokens.Space.md)
            .semantics(mergeDescendants = true) { },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Initial-based avatar: there is no image upload endpoint yet, so a
        // photo picker here would have nowhere to send the file.
        Box(contentAlignment = Alignment.BottomEnd) {
            Avatar(name = name, avatarUrl = avatarUrl, size = 88.dp)
            if (canEdit) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Tokens.Palette.accent(dark))
                        .clickable(role = Role.Button, onClick = onChangePhoto)
                        .semantics { contentDescription = "Change group picture" },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.PhotoCamera,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(Tokens.Space.md))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = Tokens.Type.sectionHeader,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (canEdit) {
                Spacer(Modifier.width(Tokens.Space.sm))
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = null,
                    tint = Tokens.Palette.accent(dark),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (description.isNotBlank()) {
            Spacer(Modifier.height(Tokens.Space.xs))
            Text(
                text = description,
                style = Tokens.Type.rowSubtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(Tokens.Space.xs))
        Text(
            text = "$memberCount members",
            style = Tokens.Type.rowSubtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ==================== Member row ====================

@Composable
private fun MemberRow(
    member: GroupMemberDto,
    isMe: Boolean,
    isOwner: Boolean,
    viewerIsAdmin: Boolean,
    busy: Boolean,
    onPromote: () -> Unit,
    onDemote: () -> Unit,
    onRemove: () -> Unit
) {
    val dark = isDarkTheme()
    var menuOpen by remember { mutableStateOf(false) }
    val isAdmin = member.groupRole == GroupRole.ADMIN

    // The owner's role is immutable and they can't be removed, so there is
    // nothing to offer for them; likewise you don't administer yourself here.
    val actionable = viewerIsAdmin && !isMe && !isOwner

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Tokens.Size.rowHeightTall)
            .padding(horizontal = Tokens.Space.screenGutter, vertical = Tokens.Space.sm)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(name = member.bestName(), avatarUrl = member.avatarUrl, size = 44.dp)

        Spacer(Modifier.width(Tokens.Space.md))

        Column(Modifier.weight(1f)) {
            Text(
                text = if (isMe) "${member.bestName()} (you)" else member.bestName(),
                style = Tokens.Type.rowTitle,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val label = when {
                isOwner -> "Owner"
                isAdmin -> "Admin"
                else -> member.username.takeIf { it.isNotBlank() } ?: ""
            }
            if (label.isNotBlank()) {
                Text(
                    text = label,
                    style = Tokens.Type.rowSubtitle,
                    color = if (isOwner || isAdmin) {
                        Tokens.Palette.accent(dark)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        when {
            busy -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = Tokens.Palette.accent(dark)
            )

            actionable -> Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = "Manage ${member.bestName()}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (isAdmin) "Dismiss as admin" else "Promote to admin") },
                        leadingIcon = {
                            Icon(Icons.Outlined.AdminPanelSettings, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            if (isAdmin) onDemote() else onPromote()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Remove from group",
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.PersonRemove,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = { menuOpen = false; onRemove() }
                    )
                }
            }
        }
    }
}

// ==================== Small pieces ====================

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(Tokens.Space.lg))
    Text(
        text = text.uppercase(),
        style = Tokens.Type.overline,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Space.screenGutter)
    )
    Spacer(Modifier.height(Tokens.Space.sm))
}

@Composable
private fun DangerRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    subtitle: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(
                minHeight = if (subtitle != null) Tokens.Size.rowHeightTall else Tokens.Size.rowHeight
            )
            .padding(horizontal = Tokens.Space.screenGutter, vertical = Tokens.Space.sm)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(Tokens.Size.icon)
        )
        Spacer(Modifier.width(Tokens.Space.md))
        Column {
            Text(title, style = Tokens.Type.rowTitle, color = MaterialTheme.colorScheme.error)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = Tokens.Type.rowSubtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameDialog(
    initialName: String,
    initialDescription: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }
    val dark = isDarkTheme()
    val accent = Tokens.Palette.accent(dark)

    LuxDialog(
        title = "Edit group",
        onDismiss = onDismiss,
        confirmLabel = "Save",
        onConfirm = { onConfirm(name, description) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Tokens.Space.lg),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.md)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Group name") },
                singleLine = true,
                shape = RoundedCornerShape(Tokens.Radius.md),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent,
                    focusedLabelColor = accent,
                    cursorColor = accent
                ),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                shape = RoundedCornerShape(Tokens.Radius.md),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent,
                    focusedLabelColor = accent,
                    cursorColor = accent
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMembersDialog(
    viewModel: GroupInfoViewModel,
    onDismiss: () -> Unit,
    onDone: () -> Unit
) {
    val add by viewModel.addMembers.collectAsStateWithLifecycle()
    val dark = isDarkTheme()
    val accent = Tokens.Palette.accent(dark)

    LuxDialog(
        title = "Add members",
        onDismiss = onDismiss,
        confirmLabel = if (add.selected.isEmpty()) null else "Add ${add.selected.size}",
        onConfirm = { viewModel.confirmAddMembers(onDone) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Tokens.Space.lg)
        ) {
            OutlinedTextField(
                value = add.query,
                onValueChange = viewModel::searchUsers,
                label = { Text("Search people") },
                singleLine = true,
                shape = RoundedCornerShape(Tokens.Radius.md),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent,
                    focusedLabelColor = accent,
                    cursorColor = accent
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(Tokens.Space.md))

            when {
                add.isSearching -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = accent
                )
                add.results.isEmpty() -> Text(
                    if (add.query.isBlank()) "Search for people to add"
                    else "No one found who isn't already a member",
                    style = Tokens.Type.rowSubtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> add.results.forEach { user ->
                    val selected = add.selected.any { it.id == user.id }
                    val label = user.displayName?.takeIf { it.isNotBlank() } ?: user.username
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Checkbox) { viewModel.toggleCandidate(user) }
                            .defaultMinSize(minHeight = Tokens.Size.rowHeight)
                            .padding(vertical = Tokens.Space.sm)
                            .semantics(mergeDescendants = true) {
                                contentDescription =
                                    "$label, ${if (selected) "selected" else "not selected"}"
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(name = label, avatarUrl = user.avatarUrl, size = 32.dp)
                        Spacer(Modifier.width(Tokens.Space.md))
                        Text(
                            label,
                            style = Tokens.Type.rowTitle,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(Tokens.Size.icon)
                            )
                        }
                    }
                }
            }
        }
    }
}

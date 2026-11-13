package com.v2ray.ang.ui.main

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import com.v2ray.ang.ui.compose.rxScreenBrush
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.RxDustBackground
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.ui.compose.QRCodeDialog
import com.v2ray.ang.ui.perappproxy.PerAppRoutingMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    dnsModeViewModel: DnsModeViewModel,
    onAction: (MainAction) -> Unit,
    onNavigate: (MainDestination) -> Unit,
) {
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val groups = uiState.groups
    val isLoading by mainViewModel.isLoading.collectAsStateWithLifecycle()
    val isRunning = uiState.isRunning
    val updatePending by mainViewModel.updatePending.collectAsStateWithLifecycle()
    val displayText = mainViewModel.formatStatus(uiState.status)
    // The status line names the connection state; bulk test progress takes it over while it runs.
    val statusText = when {
        uiState.connectPhase == ConnectPhase.Connecting -> stringResource(R.string.rx_connecting)
        uiState.status is MainStatus.TestProgress -> displayText
        uiState.status is MainStatus.Testing && uiState.connectPhase != ConnectPhase.Connected -> displayText
        uiState.connectPhase == ConnectPhase.Connected -> stringResource(R.string.connection_connected)
        else -> stringResource(R.string.connection_not_connected)
    }
    val selectedGuid = uiState.selectedGuid
    val doubleColumnDisplay = uiState.doubleColumnDisplay
    val confirmRemove = uiState.confirmRemove
    val shareQRCodeBitmap = uiState.shareQRCodeBitmap

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showDelAllConfirm by remember { mutableStateOf(false) }
    var showDelDuplicateConfirm by remember { mutableStateOf(false) }
    var showDelInvalidConfirm by remember { mutableStateOf(false) }
    var showQuickSheet by remember { mutableStateOf(false) }
    var showDnsMode by remember { mutableStateOf(false) }
    val connectBestTick by mainViewModel.connectBestTick.collectAsStateWithLifecycle()
    LaunchedEffect(connectBestTick) {
        if (connectBestTick > 0) onAction(MainAction.RestartService)
    }
    var showCopySelected by remember { mutableStateOf(false) }
    var showRemoveConfirm by rememberSaveable(stateSaver = ServerDeleteTarget.Saver) {
        mutableStateOf<ServerDeleteTarget?>(null)
    }

    var shareTarget by remember { mutableStateOf<Triple<String, ProfileItem, Boolean>?>(null) }
    val removeServer: (String, String) -> Unit = { guid, profileName ->
        if (confirmRemove) {
            showRemoveConfirm = ServerDeleteTarget(guid, profileName)
        } else {
            onAction(MainAction.RemoveServer(guid))
        }
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { groups.size.coerceAtLeast(1) }
    )

    val lazyListStates = remember { mutableStateMapOf<String, LazyListState>() }
    val lazyGridStates = remember { mutableStateMapOf<String, LazyGridState>() }

    LaunchedEffect(groups) {
        val validGroupIds = groups.map { it.id }.toSet()
        lazyListStates.keys.retainAll(validGroupIds)
        lazyGridStates.keys.retainAll(validGroupIds)
    }

    LaunchedEffect(groups, uiState.selectedGroupId) {
        if (groups.isEmpty()) return@LaunchedEffect
        val selectedIndex = groups.indexOfFirst { it.id == uiState.selectedGroupId }
            .takeIf { it >= 0 } ?: 0
        if (!pagerState.isScrollInProgress && pagerState.settledPage != selectedIndex) {
            pagerState.scrollToPage(selectedIndex)
        }
    }

    val latestGroups by rememberUpdatedState(groups)

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val currentGroups = latestGroups
                if (page in currentGroups.indices) {
                    onAction(MainAction.SelectGroup(currentGroups[page].id))
                }
            }
    }

    MainDialogs(
        showDelAllConfirm = showDelAllConfirm,
        onDismissDelAll = { showDelAllConfirm = false },
        onConfirmDelAll = { showDelAllConfirm = false; onAction(MainAction.RemoveAllServers) },
        showDelDuplicateConfirm = showDelDuplicateConfirm,
        onDismissDelDuplicate = { showDelDuplicateConfirm = false },
        onConfirmDelDuplicate = { showDelDuplicateConfirm = false; onAction(MainAction.RemoveDuplicateServers) },
        showDelInvalidConfirm = showDelInvalidConfirm,
        onDismissDelInvalid = { showDelInvalidConfirm = false },
        onConfirmDelInvalid = { showDelInvalidConfirm = false; onAction(MainAction.PingThenRemoveInvalid) },
        showRemoveConfirm = showRemoveConfirm,
        onDismissRemove = { showRemoveConfirm = null },
        onConfirmRemove = { guid -> showRemoveConfirm = null; onAction(MainAction.RemoveServer(guid)) }
    )

    if (uiState.subNameRequest != null) {
        SubscriptionNamePrompt(onAnswer = { name -> onAction(MainAction.AnswerSubName(name)) })
    }

    if (shareTarget != null) {
        val (guid, profile, more) = shareTarget!!
        ShareMethodDialog(
            guid = guid,
            profile = profile,
            more = more,
            onDismiss = { shareTarget = null },
            onAction = onAction,
            onRemove = removeServer,
        )
    }
    if (shareQRCodeBitmap != null) {
        QRCodeDialog(bitmap = shareQRCodeBitmap, onDismiss = { onAction(MainAction.DismissQRCodeDialog) })
    }

    if (showCopySelected) {
        val copyFlow = remember(uiState.selectedGroupId) {
            mainViewModel.serversForGroup(uiState.selectedGroupId)
        }
        val copyServers by copyFlow.collectAsStateWithLifecycle()
        CopySelectedDialog(
            servers = copyServers,
            onDismiss = { showCopySelected = false },
            onCopy = { guids ->
                showCopySelected = false
                onAction(MainAction.CopySelected(guids))
            },
        )
    }

    Box(
        modifier = Modifier.fillMaxSize().background(rxScreenBrush())
    ) {
        // Live wallpaper, behind everything on the screen.
        RxDustBackground()
        // Nothing here sits in a Surface, so text without its own colour would be drawn black.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                MainDrawerContent(
                    drawerState = drawerState,
                    updatePending = updatePending,
                    onNavigate = { route ->
                        scope.launch { drawerState.close() }
                        onNavigate(route)
                    }
                )
            }
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
                topBar = {
                    MainTopBar(
                        isLoading = isLoading,
                        showSearch = showSearch,
                        searchQuery = searchQuery,
                        updatePending = updatePending,
                        onSearchQueryChange = { query: String ->
                            searchQuery = query
                            onAction(MainAction.Search(query))
                        },
                        onSearchClose = {
                            searchQuery = ""
                            onAction(MainAction.Search(""))
                            showSearch = false
                        },
                        onSearchToggle = { show: Boolean -> showSearch = show },
                        onMenuClick = { scope.launch { drawerState.open() } },
                        onAction = onAction,
                        onMoreMenuAction = { action ->
                            when (action) {
                                MainMoreMenuAction.RestartService -> onAction(MainAction.RestartService)
                                MainMoreMenuAction.DeleteAll -> showDelAllConfirm = true
                                MainMoreMenuAction.DeleteDuplicate -> showDelDuplicateConfirm = true
                                MainMoreMenuAction.DeleteInvalid -> showDelInvalidConfirm = true
                                MainMoreMenuAction.ExportAll -> onAction(MainAction.ExportAll)
                                MainMoreMenuAction.CopySelected -> showCopySelected = true
                            }
                        }
                    )
                },
                bottomBar = {
                    MainBottomBar(
                        statusText = statusText,
                        phase = uiState.connectPhase,
                        failureTick = uiState.failureTick,
                        isTesting = uiState.isTesting,
                        pingMillis = uiState.pingMillis,
                        exitIp = uiState.exitIp,
                        exitCountry = uiState.exitCountry,
                        canConnect = MainConnectionPolicy.canConnect(
                            isRunning = isRunning,
                            selectedGuid = selectedGuid,
                            routingMode = uiState.routingMode,
                            routedAppCount = uiState.routedAppCount,
                        ),
                        needsAppSelection = uiState.routingMode == PerAppRoutingMode.ONLY_SELECTED && uiState.routedAppCount == 0,
                        onAction = onAction,
                        onOpenQuickSheet = { showQuickSheet = true },
                    )
                },
                floatingActionButton = {},
            ) { innerPadding ->
                val layoutDirection = LocalLayoutDirection.current

                if (groups.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (groups.size > 1) {
                            GroupTabBar(
                                groups = groups,
                                selectedTabIndex = pagerState.currentPage.coerceIn(0, groups.lastIndex),
                                mainViewModel = mainViewModel,
                                onTabClick = { targetIndex ->
                                    scope.launch {
                                        pagerState.navigateToPageOptimized(
                                            targetPage = targetIndex,
                                            animateAdjacentPage = true
                                        )
                                    }
                                },
                                onUpdateSubscription = { groupId ->
                                    onAction(MainAction.UpdateGroupSubscription(groupId))
                                },
                                onPingGroup = { groupId ->
                                    onAction(MainAction.TestGroupRealPing(groupId))
                                },
                            )
                        }

                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            userScrollEnabled = true,
                            beyondViewportPageCount = 1,
                            key = { page -> groups.getOrNull(page)?.id ?: "group-page-$page" }
                        ) { page ->
                            val group = groups.getOrNull(page) ?: return@HorizontalPager

                            GroupPagerPage(
                                groupId = group.id,
                                mainViewModel = mainViewModel,
                                selectedGuid = selectedGuid,
                                locateTarget = uiState.locateTarget,
                                doubleColumnDisplay = doubleColumnDisplay,
                                searchQuery = searchQuery,
                                lazyListStates = lazyListStates,
                                lazyGridStates = lazyGridStates,
                                onSelectServer = { guid -> onAction(MainAction.SelectServer(guid)) },
                                onEditServer = { guid, profile -> onAction(MainAction.EditServer(guid, profile)) },
                                onShareServer = { guid, profile ->
                                    shareTarget = Triple(guid, profile, false)
                                },
                                onMoreServer = { guid, profile ->
                                    shareTarget = Triple(guid, profile, true)
                                },
                                onRemoveServer = removeServer,
                                usageInfo = group.usageInfo,
                                groupTitle = group.remarks,
                                onOpenLink = { url -> onAction(MainAction.OpenSubscriptionLink(url)) },
                                onImportConfig = { onAction(MainAction.ImportConfigLocal) },
                                onImportClipboard = { onAction(MainAction.ImportClipboard) },
                                contentPadding = PaddingValues(
                                    start = 0.dp,
                                    top = 0.dp,
                                    end = 0.dp,
                                    bottom = 16.dp
                                )
                            )
                        }
                    }
                } else {
                    MainEmptyState(
                        onImportConfig = { onAction(MainAction.ImportConfigLocal) },
                        onImportClipboard = { onAction(MainAction.ImportClipboard) },
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                    )
                }
            }
        }
        }

        // Rises out of the connect button; the only message shown around connecting.
        ConnectFailureMessage(
            visible = uiState.connectPhase == ConnectPhase.Failed,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 150.dp),
        )

        if (showQuickSheet) {
            MainQuickSheet(
                onDismiss = { showQuickSheet = false },
                onNavigate = { destination ->
                    showQuickSheet = false
                    if (destination == MainDestination.DnsMode) {
                        dnsModeViewModel.refresh()
                        showDnsMode = true
                    } else {
                        onNavigate(destination)
                    }
                },
            )
        }

        if (showDnsMode) {
            val dnsState by dnsModeViewModel.state.collectAsStateWithLifecycle()
            MainDnsModeCard(
                state = dnsState,
                onSetEnabled = { enabled ->
                    dnsModeViewModel.setEnabled(enabled)
                    // Switching on: find the best config and connect to it. The plain restart
                    // right after this covers the case where there is nothing to test.
                    if (enabled) mainViewModel.testAllAndConnectBest()
                },
                onProbeConsoleDns = dnsModeViewModel::probeConsoleDns,
                onAddDomain = dnsModeViewModel::addDomain,
                onClearDomains = dnsModeViewModel::clearDomains,
                onSetSharing = dnsModeViewModel::setSharing,
                // A running tunnel is rebuilt with the new rules; a stopped one is left alone.
                onChanged = { if (isRunning) onAction(MainAction.RestartService) },
                onDismiss = { showDnsMode = false },
            )
        }
    }
}
